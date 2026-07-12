package today.qdreader.auto.automation

import android.graphics.Bitmap
import android.graphics.Rect
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import today.qdreader.auto.accessibility.ScreenPoint
import today.qdreader.auto.accessibility.AccessibilityBridge
import today.qdreader.auto.accessibility.UiTreeSnapshot
import today.qdreader.auto.accessibility.clickPointFor
import today.qdreader.auto.accessibility.findNode
import today.qdreader.auto.accessibility.hasNode
import today.qdreader.auto.core.AppConstants
import today.qdreader.auto.logs.AppLogStore
import today.qdreader.auto.vision.CloseButtonDetector
import today.qdreader.auto.vision.CloseButtonMatch
import today.qdreader.auto.vision.OcrActionTextMatch
import today.qdreader.auto.vision.OcrEngine
import today.qdreader.auto.vision.OcrResult
import today.qdreader.auto.vision.findActionAfterAnyText
import today.qdreader.auto.vision.findAnyTextCenter
import today.qdreader.auto.vision.findBlocksContaining
import today.qdreader.auto.vision.hasText
import today.qdreader.auto.vision.hasAnyText
import kotlin.math.abs

sealed interface CheckInStep {
    data object FrameworkOnly : CheckInStep
    data object QidianHome : CheckInStep
    data object MyPageLoggedOut : CheckInStep
    data object MyPageLoggedIn : CheckInStep
    data object WelfareCenter : CheckInStep
    data object Unsupported : CheckInStep
}

data class FlowExecutionResult(
    val completed: Boolean,
    val message: String,
    val restartRequested: Boolean = false
)

interface CheckInFlow {
    suspend fun run(bridge: AccessibilityBridge, executor: ActionExecutor): FlowExecutionResult
    suspend fun detectCurrentStep(analysis: ScreenAnalysis?): CheckInStep
    suspend fun nextAction(step: CheckInStep): AutomationAction
    suspend fun executeAction(action: AutomationAction, executor: ActionExecutor): FlowExecutionResult
}

class PlaceholderCheckInFlow : CheckInFlow {
    override suspend fun run(
        bridge: AccessibilityBridge,
        executor: ActionExecutor
    ): FlowExecutionResult {
        val step = detectCurrentStep(null)
        val action = nextAction(step)
        return executeAction(action, executor)
    }

    override suspend fun detectCurrentStep(analysis: ScreenAnalysis?): CheckInStep {
        return CheckInStep.FrameworkOnly
    }

    override suspend fun nextAction(step: CheckInStep): AutomationAction {
        return AutomationAction.NoOp
    }

    override suspend fun executeAction(
        action: AutomationAction,
        executor: ActionExecutor
    ): FlowExecutionResult {
        executor.execute(action).getOrThrow()
        return FlowExecutionResult(
            completed = false,
            message = "v0.1 仅包含框架，具体签到点击步骤尚未实现"
        )
    }
}

class QidianPartialCheckInFlow(
    private val ocrEngine: OcrEngine,
    private val closeButtonDetector: CloseButtonDetector
) : CheckInFlow, AutoCloseable {
    override suspend fun run(
        bridge: AccessibilityBridge,
        executor: ActionExecutor
    ): FlowExecutionResult {
        AppLogStore.add("步骤 1：验证重新启动后的起点读书首页")
        val home = waitForTree(bridge, "起点首页", timeoutMillis = 12_000) { tree ->
            tree.packageName == AppConstants.QIDIAN_PACKAGE && tree.hasBottomTabs()
        } ?: return restartableFailure("未进入起点读书首页：未检测到底部 4 个 tab")
        AppLogStore.add("步骤 1：已确认起点首页和底部 4 个 tab")

        AppLogStore.add("步骤 2：已检测到底部 tab，点击“我”")
        val myPage = tapMeTabWithRetry(home, bridge, executor)
            ?: return restartableFailure("连续点击“我”$MAX_CLICK_ATTEMPTS 次后仍未进入我的界面")

        AppLogStore.add("步骤 3：检查登录状态")
        if (myPage.isLoggedOutMyPage()) {
            bridge.launchAutomationApp()
            return FlowExecutionResult(false, "起点读书未登录：检测到“登录/注册”，请先登录后再运行")
        }

        AppLogStore.add("已进入我的界面，等待 ${MY_PAGE_READY_DELAY_MILLIS / 1_000} 秒后点击“福利中心”")
        delay(MY_PAGE_READY_DELAY_MILLIS)

        AppLogStore.add("步骤 4：点击“福利中心”")
        val welfareCenter = tapWelfareCenterWithRetry(bridge, executor)
            ?: return restartableFailure("连续点击“福利中心”$MAX_CLICK_ATTEMPTS 次后仍未进入福利中心界面")

        AppLogStore.add("步骤 5：OCR 已确认福利中心，命中：${welfareCenter.ocr.welfareCenterMarkerSummary()}")

        AppLogStore.add("步骤 6：上滑福利中心页面，仅执行一次")
        executor.execute(welfareCenter.upSwipeAction()).getOrThrow()
        delay(900)

        AppLogStore.add("步骤 7-13：开始按 OCR 处理广告奖励任务")
        var successfulTaskCount = 0
        for (task in WELFARE_AD_TASKS) {
            val taskResult = completeWelfareAdTask(task, bridge, executor)
            if (taskResult.completed) {
                successfulTaskCount += 1
            } else {
                return taskResult
            }
        }

        return FlowExecutionResult(
            completed = true,
            message = "已完成当前配置的福利中心广告奖励任务，成功 $successfulTaskCount/${WELFARE_AD_TASKS.size}"
        )
    }

    override fun close() {
        ocrEngine.close()
    }

    override suspend fun detectCurrentStep(analysis: ScreenAnalysis?): CheckInStep {
        val tree = analysis?.uiTree ?: return CheckInStep.Unsupported
        return when {
            tree.hasWelfareCenterMarkers() -> CheckInStep.WelfareCenter
            tree.isLoggedOutMyPage() -> CheckInStep.MyPageLoggedOut
            tree.findNode(text = "福利中心", viewId = WELFARE_TITLE_ID) != null -> CheckInStep.MyPageLoggedIn
            tree.hasBottomTabs() -> CheckInStep.QidianHome
            else -> CheckInStep.Unsupported
        }
    }

    override suspend fun nextAction(step: CheckInStep): AutomationAction {
        return AutomationAction.NoOp
    }

    override suspend fun executeAction(
        action: AutomationAction,
        executor: ActionExecutor
    ): FlowExecutionResult {
        executor.execute(action).getOrThrow()
        return FlowExecutionResult(false, "起点部分流程通过 run() 执行")
    }

    private suspend fun tapMeTabWithRetry(
        initialHome: UiTreeSnapshot,
        bridge: AccessibilityBridge,
        executor: ActionExecutor
    ): UiTreeSnapshot? {
        var tabTree: UiTreeSnapshot? = initialHome
        for (attempt in 1..MAX_CLICK_ATTEMPTS) {
            val activeTabTree = tabTree ?: waitForTree(bridge, "起点首页底部 tab", timeoutMillis = SHORT_VERIFY_TIMEOUT_MILLIS) { tree ->
                tree.packageName == AppConstants.QIDIAN_PACKAGE && tree.hasBottomTabs()
            }
            val meNode = activeTabTree?.findNode(text = "我", viewId = TAB_TITLE_ID)
            if (activeTabTree == null || meNode == null) {
                AppLogStore.add("第 $attempt/$MAX_CLICK_ATTEMPTS 次点击“我”前未找到底部 tab")
                delay(RETRY_DELAY_MILLIS)
                tabTree = null
                continue
            }

            AppLogStore.add("点击“我”（$attempt/$MAX_CLICK_ATTEMPTS）")
            executor.execute(AutomationAction.TapPoint(activeTabTree.clickPointFor(meNode))).getOrThrow()

            val myPage = waitForTree(bridge, "我的界面", timeoutMillis = NAVIGATION_VERIFY_TIMEOUT_MILLIS) { tree ->
                tree.isMyPageCandidate()
            }
            if (myPage != null) {
                return myPage
            }

            if (attempt < MAX_CLICK_ATTEMPTS) {
                AppLogStore.add("点击“我”后未进入我的界面，等待后重试")
                delay(RETRY_DELAY_MILLIS)
                tabTree = waitForTree(bridge, "起点首页底部 tab", timeoutMillis = SHORT_VERIFY_TIMEOUT_MILLIS) { tree ->
                    tree.packageName == AppConstants.QIDIAN_PACKAGE && tree.hasBottomTabs()
                }
            }
        }
        return null
    }

    private suspend fun tapWelfareCenterWithRetry(
        bridge: AccessibilityBridge,
        executor: ActionExecutor
    ): OcrScreen? {
        for (attempt in 1..MAX_CLICK_ATTEMPTS) {
            val stableEntry = waitForStableWelfareEntry(bridge)
            if (stableEntry == null) {
                AppLogStore.add("第 $attempt/$MAX_CLICK_ATTEMPTS 次点击“福利中心”前，入口位置未稳定")
                delay(RETRY_DELAY_MILLIS)
                continue
            }

            val (activeMyPage, welfareNode) = stableEntry
            val fallbackPoint = activeMyPage.clickPointFor(welfareNode)
            AppLogStore.add(
                "福利中心入口已稳定在 y=${welfareNode.bounds.exactCenterY().toInt()}，" +
                    "执行实时组件点击（$attempt/$MAX_CLICK_ATTEMPTS）"
            )
            val componentClick = executor.execute(
                AutomationAction.ClickNode(text = "福利中心", viewId = WELFARE_TITLE_ID)
            )
            if (componentClick.isFailure) {
                AppLogStore.add("实时组件点击失败，使用稳定坐标兜底：${componentClick.exceptionOrNull()?.message}")
                executor.execute(AutomationAction.TapPoint(fallbackPoint)).getOrThrow()
            }

            val welfareCenter = waitForWelfareCenter(bridge, timeoutMillis = WELFARE_VERIFY_TIMEOUT_MILLIS)
            if (welfareCenter != null) {
                return welfareCenter
            }

            if (attempt < MAX_CLICK_ATTEMPTS) {
                AppLogStore.add("点击“福利中心”后 10 秒仍未确认进入，开始恢复“我的”界面")
                if (!recoverMyPageAfterWelfareMiss(bridge, executor)) {
                    AppLogStore.add("未能恢复到“我的”界面")
                    return null
                }
            }
        }
        return null
    }

    private suspend fun waitForStableWelfareEntry(
        bridge: AccessibilityBridge
    ): Pair<UiTreeSnapshot, today.qdreader.auto.accessibility.UiNodeSnapshot>? {
        val deadline = System.currentTimeMillis() + WELFARE_ENTRY_STABLE_TIMEOUT_MILLIS
        var previousBounds: Rect? = null
        var stableSampleCount = 0

        while (System.currentTimeMillis() < deadline) {
            val tree = bridge.readActiveWindow()
            val node = tree
                ?.takeIf { it.isMyPageCandidate() }
                ?.findNode(text = "福利中心", viewId = WELFARE_TITLE_ID)
            if (tree != null && node != null) {
                val isStable = previousBounds?.isNear(node.bounds, WELFARE_ENTRY_MAX_POSITION_DRIFT_PX) == true
                stableSampleCount = if (isStable) stableSampleCount + 1 else 1
                previousBounds = Rect(node.bounds)
                if (stableSampleCount >= WELFARE_ENTRY_REQUIRED_STABLE_SAMPLES) {
                    return tree to node
                }
            } else {
                previousBounds = null
                stableSampleCount = 0
            }
            delay(WELFARE_ENTRY_STABLE_SAMPLE_INTERVAL_MILLIS)
        }
        return null
    }

    private suspend fun recoverMyPageAfterWelfareMiss(
        bridge: AccessibilityBridge,
        executor: ActionExecutor
    ): Boolean {
        val currentTree = bridge.readActiveWindow()
        if (currentTree?.isMyPageCandidate() == true) {
            AppLogStore.add("当前仍在“我的”界面，不执行返回；等待页面重新稳定")
            delay(RETRY_DELAY_MILLIS)
            return true
        }

        AppLogStore.add("可能误入“我的阅历”等子页面，执行返回后重新定位福利中心")
        if (executor.execute(AutomationAction.Back).isFailure) return false
        delay(RETRY_DELAY_MILLIS)

        val myPage = waitForTree(bridge, "返回后的我的界面", timeoutMillis = NAVIGATION_VERIFY_TIMEOUT_MILLIS) { tree ->
            tree.isMyPageCandidate()
        }
        if (myPage != null) return true

        AppLogStore.add("返回后仍无法确认“我的”界面，交给整轮重启恢复")
        return false
    }

    private fun Rect.isNear(other: Rect, maxDriftPx: Int): Boolean {
        return abs(left - other.left) <= maxDriftPx &&
            abs(top - other.top) <= maxDriftPx &&
            abs(right - other.right) <= maxDriftPx &&
            abs(bottom - other.bottom) <= maxDriftPx
    }

    private suspend fun completeWelfareAdTask(
        task: WelfareAdTask,
        bridge: AccessibilityBridge,
        executor: ActionExecutor
    ): FlowExecutionResult {
        AppLogStore.add("开始处理福利任务：“${task.title}”")

        var round = 0
        val taskStartedAtMillis = System.currentTimeMillis()
        while (true) {
            if (System.currentTimeMillis() - taskStartedAtMillis > TASK_ATTEMPT_TIMEOUT_MILLIS) {
                return restartableFailure("福利任务“${task.title}”超过 ${TASK_ATTEMPT_TIMEOUT_MILLIS / 60_000} 分钟仍未结束，按步骤卡住处理")
            }

            clearRewardDialogBeforeTaskAction(bridge, executor)
            val action = findTaskActionOnCurrentScreen(task, bridge)
                ?: return restartableFailure("OCR 未找到“${task.title}”后面的“去完成 / 已领取”状态")

            when {
                action.actionText in TASK_DONE_TEXTS -> {
                    AppLogStore.add("福利任务“${task.title}”已领取")
                    return FlowExecutionResult(true, "福利任务“${task.title}”已领取")
                }

                action.isGoComplete() -> {
                    round += 1
                    AppLogStore.add("福利任务“${task.title}”当前仍是“去完成”，继续执行第 $round 轮")
                    val adResult = completeAdRewardRound(task, round, action, bridge, executor)
                    if (!adResult.completed) {
                        return adResult
                    }
                    delay(1_200)
                }

                else -> return restartableFailure("福利任务“${task.title}”状态不可识别：${action.actionText}")
            }
        }
    }

    private suspend fun findTaskActionOnCurrentScreen(
        task: WelfareAdTask,
        bridge: AccessibilityBridge
    ): OcrActionTextMatch? {
        repeat(TASK_STATUS_POLL_ATTEMPTS) { index ->
            val screen = captureOcrScreen(bridge).getOrNull()
            val action = screen?.ocr?.findActionAfterAnyText(task.matchTexts, TASK_ACTION_TEXTS)
                ?: screen?.findTaskActionByRowFallback(task)
            if (action != null) {
                AppLogStore.add("OCR 已定位“${task.title}”状态：${action.actionText}")
                return action
            }

            if (index < TASK_STATUS_POLL_ATTEMPTS - 1) {
                AppLogStore.add("当前屏未找到“${task.title}”，不额外上滑，仅等待后重试 OCR")
                delay(600)
            } else if (screen != null) {
                AppLogStore.add(
                    "OCR 匹配失败：“${task.title}”；锚点=${screen.ocr.hasAnyText(task.matchTexts)}，状态=${screen.ocr.hasAnyText(TASK_ACTION_TEXTS)}"
                )
                AppLogStore.add("OCR 文本预览：${screen.ocr.logPreview()}")
            }
        }
        return null
    }

    private suspend fun completeAdRewardRound(
        task: WelfareAdTask,
        round: Int,
        initialAction: OcrActionTextMatch,
        bridge: AccessibilityBridge,
        executor: ActionExecutor
    ): FlowExecutionResult {
        return try {
            withTimeout(GO_COMPLETE_TO_REWARD_CONFIRM_TIMEOUT_MILLIS) {
                completeAdRewardRoundWithinWatchdog(task, round, initialAction, bridge, executor)
            }
        } catch (exception: TimeoutCancellationException) {
            restartableFailure("“${task.title}”第 $round 轮点击“去完成”后 ${GO_COMPLETE_TO_REWARD_CONFIRM_TIMEOUT_MILLIS / 1_000} 秒内未完成“知道了”确认，按卡住处理")
        }
    }

    private suspend fun completeAdRewardRoundWithinWatchdog(
        task: WelfareAdTask,
        round: Int,
        initialAction: OcrActionTextMatch,
        bridge: AccessibilityBridge,
        executor: ActionExecutor
    ): FlowExecutionResult {
        AppLogStore.add("步骤 8：点击“去完成”并验证广告界面")
        val initialCloseMatch = tapGoCompleteAndWaitForAd(task, round, initialAction, bridge, executor)
            ?: return restartableFailure("“${task.title}”第 $round 轮未识别到广告页右上角关闭按钮")

        AppLogStore.add("步骤 9：关闭广告并等待“点击去浏览 / 放弃奖励”弹窗")
        val browseDialog = openBrowseDialogWithRetry(task, round, initialCloseMatch, bridge, executor)
            ?: return restartableFailure("“${task.title}”第 $round 轮未识别到“点击去浏览”弹窗")

        val browsePoint = browseDialog.findBrowseActionPoint()
            ?: return restartableFailure("“${task.title}”第 $round 轮未定位到“点击去浏览”坐标")
        executor.execute(AutomationAction.TapPoint(browsePoint)).getOrThrow()
        AppLogStore.add("已点击“点击去浏览”")

        AppLogStore.add("步骤 10：等待 18 秒后查询广告奖励完成文字")
        delay(18_000)
        val rewardCompleted = waitForOcr(bridge, "恭喜已获得奖励", timeoutMillis = 15_000) { ocr ->
            ocr.hasAnyText(REWARD_GRANTED_TEXTS)
        } ?: return restartableFailure("“${task.title}”第 $round 轮等待后未识别到“恭喜已获得奖励”")
        AppLogStore.add("广告奖励完成提示已出现，OCR 耗时 ${rewardCompleted.ocr.elapsedMillis} ms")

        executor.execute(AutomationAction.Back).getOrThrow()
        AppLogStore.add("已返回广告界面，准备再次关闭广告")
        delay(1_000)
        tapCloseButtonOrFail(bridge, executor, "广告奖励完成后")
            ?: return restartableFailure("“${task.title}”第 $round 轮返回后未识别到广告关闭按钮")

        AppLogStore.add("步骤 11：检查奖励确认弹窗")
        val rewardDialogHandled = tapRewardConfirmIfPresent(bridge, executor)
        if (rewardDialogHandled) {
            AppLogStore.add("奖励确认弹窗已消失，允许重新检查任务状态")
        } else {
            AppLogStore.add("未识别到“知道了”或奖励弹窗，直接点击“知道了”固定兜底位置")
            tapDefaultRewardConfirm(bridge, executor)
        }

        return FlowExecutionResult(true, "“${task.title}”第 $round 轮广告奖励已处理")
    }

    private suspend fun tapRewardConfirmIfPresent(
        bridge: AccessibilityBridge,
        executor: ActionExecutor
    ): Boolean {
        val dialog = waitForRewardConfirmDialog(bridge, timeoutMillis = REWARD_CONFIRM_TIMEOUT_MILLIS)
            ?: return false
        return dismissRewardConfirmDialog(dialog, bridge, executor)
    }

    private suspend fun clearRewardDialogBeforeTaskAction(
        bridge: AccessibilityBridge,
        executor: ActionExecutor
    ) {
        val screen = captureOcrScreen(bridge).getOrNull() ?: return
        if (!screen.hasRewardConfirmDialogMarkers()) return

        AppLogStore.add("点击任务按钮前检测到残留奖励弹窗，优先查找并点击“知道了”")
        if (!dismissRewardConfirmDialog(screen, bridge, executor)) {
            AppLogStore.add("奖励弹窗连续处理仍未确认消失；按规则重新识别当前任务，找不到弹窗时才点击“去完成”")
        }
    }

    private suspend fun dismissRewardConfirmDialog(
        initialScreen: OcrScreen,
        bridge: AccessibilityBridge,
        executor: ActionExecutor
    ): Boolean {
        var screen = initialScreen
        for (attempt in 1..REWARD_CONFIRM_MAX_TAP_ATTEMPTS) {
            val knowPoint = screen.ocr.findAnyTextCenter(KNOW_BUTTON_TEXTS)
            val tapPoint = knowPoint ?: screen.inferRewardConfirmButtonPoint()
            executor.execute(AutomationAction.TapPoint(tapPoint)).getOrThrow()

            if (knowPoint != null) {
                AppLogStore.add(
                    "已识别并点击“知道了”，坐标=(${knowPoint.x.toInt()},${knowPoint.y.toInt()})" +
                        "（$attempt/$REWARD_CONFIRM_MAX_TAP_ATTEMPTS）"
                )
            } else {
                AppLogStore.add("识别到奖励弹窗但未定位“知道了”，点击确认按钮兜底坐标（$attempt/$REWARD_CONFIRM_MAX_TAP_ATTEMPTS）")
            }

            delay(REWARD_CONFIRM_DISMISS_VERIFY_DELAY_MILLIS)
            val nextScreen = captureOcrScreen(bridge).getOrNull() ?: return true
            if (!nextScreen.hasRewardConfirmDialogMarkers()) {
                AppLogStore.add("已确认奖励弹窗消失")
                return true
            }
            AppLogStore.add("奖励弹窗仍存在，继续优先点击“知道了”")
            screen = nextScreen
        }
        return false
    }

    private suspend fun tapDefaultRewardConfirm(
        bridge: AccessibilityBridge,
        executor: ActionExecutor
    ) {
        val bitmap = bridge.captureScreenshot().getOrNull()
        val treeBounds = bridge.readActiveWindow()?.root?.bounds
        val width = bitmap?.width ?: treeBounds?.width() ?: 0
        val height = bitmap?.height ?: treeBounds?.height() ?: 0
        bitmap?.recycle()
        if (width <= 0 || height <= 0) {
            AppLogStore.add("奖励确认固定兜底失败：无法获取当前屏幕尺寸")
            return
        }
        val point = ScreenPoint(
            x = width * DEFAULT_REWARD_CONFIRM_X_FRACTION,
            y = height * DEFAULT_REWARD_CONFIRM_Y_FRACTION
        )
        executor.execute(AutomationAction.TapPoint(point)).getOrThrow()
        AppLogStore.add("已点击“知道了”固定兜底坐标=(${point.x.toInt()},${point.y.toInt()})")
        delay(REWARD_CONFIRM_DISMISS_VERIFY_DELAY_MILLIS)
    }

    private suspend fun waitForRewardConfirmDialog(
        bridge: AccessibilityBridge,
        timeoutMillis: Long
    ): OcrScreen? {
        val deadline = System.currentTimeMillis() + timeoutMillis
        var lastPreview = ""
        while (System.currentTimeMillis() < deadline) {
            val screen = captureOcrScreen(bridge).getOrNull()
            if (screen != null) {
                if (screen.hasRewardConfirmDialogMarkers()) {
                    AppLogStore.add("OCR 已识别：奖励确认弹窗，预览：${screen.ocr.logPreview()}")
                    return screen
                }
                lastPreview = screen.ocr.logPreview()
            }
            delay(600)
        }

        if (lastPreview.isNotBlank()) {
            AppLogStore.add("奖励确认弹窗 OCR 未命中，最近预览：$lastPreview")
        }
        return null
    }

    private fun OcrScreen.hasRewardConfirmDialogMarkers(): Boolean {
        return ocr.findAnyTextCenter(KNOW_BUTTON_TEXTS) != null ||
            findCenteredRewardStrongTextPoint() != null ||
            findCenteredRewardConfirmTextPoint() != null
    }

    private suspend fun openBrowseDialogWithRetry(
        task: WelfareAdTask,
        round: Int,
        initialCloseMatch: CloseButtonMatch,
        bridge: AccessibilityBridge,
        executor: ActionExecutor
    ): OcrScreen? {
        var closeMatch: CloseButtonMatch? = initialCloseMatch
        for (attempt in 1..MAX_CLICK_ATTEMPTS) {
            val activeCloseMatch = closeMatch ?: waitForCloseButton(bridge, timeoutMillis = SHORT_VERIFY_TIMEOUT_MILLIS)
            if (activeCloseMatch == null) {
                AppLogStore.add("第 $attempt/$MAX_CLICK_ATTEMPTS 次打开浏览弹窗前未找到广告关闭按钮")
                logCurrentOcrPreview("浏览弹窗关闭按钮缺失", bridge)
            } else {
                tapDetectedCloseButton(activeCloseMatch, executor, "广告奖励选择前 $attempt/$MAX_CLICK_ATTEMPTS")
            }

            val browseDialog = waitForOcr(bridge, "点击去浏览弹窗", timeoutMillis = BROWSE_DIALOG_VERIFY_TIMEOUT_MILLIS) { ocr ->
                ocr.hasAnyText(BROWSE_ACTION_TEXTS) || ocr.hasAnyText(GIVE_UP_REWARD_TEXTS)
            }
            if (browseDialog != null) {
                return browseDialog
            }

            val hintScreen = captureOcrScreen(bridge).getOrNull()
            if (hintScreen != null) {
                AppLogStore.add(
                    "“${task.title}”第 $round 轮未识别到浏览按钮；弹窗提示=${hintScreen.ocr.hasAnyText(BROWSE_DIALOG_HINT_TEXTS)}"
                )
                AppLogStore.add("广告弹窗 OCR 预览：${hintScreen.ocr.logPreview()}")
            }

            if (attempt < MAX_CLICK_ATTEMPTS) {
                AppLogStore.add("未识别到“点击去浏览”弹窗，等待后重试关闭按钮")
                delay(RETRY_DELAY_MILLIS)
                closeMatch = waitForCloseButton(bridge, timeoutMillis = SHORT_VERIFY_TIMEOUT_MILLIS)
            }
        }
        return null
    }

    private suspend fun tapGoCompleteAndWaitForAd(
        task: WelfareAdTask,
        round: Int,
        initialAction: OcrActionTextMatch,
        bridge: AccessibilityBridge,
        executor: ActionExecutor
    ): CloseButtonMatch? {
        var action = initialAction
        var attempt = 0
        val startedAtMillis = System.currentTimeMillis()
        while (true) {
            attempt += 1
            AppLogStore.add("福利任务“${task.title}”：第 $round 轮点击“去完成”（第 $attempt 次）")
            executor.execute(AutomationAction.TapPoint(action.point)).getOrThrow()

            val closeMatch = waitForCloseButton(bridge, timeoutMillis = AD_ENTRY_VERIFY_TIMEOUT_MILLIS)
            if (closeMatch != null) {
                AppLogStore.add("已通过右上角关闭按钮确认进入广告界面")
                return closeMatch
            }

            AppLogStore.add("点击“去完成”后未检测到广告关闭按钮，等待后重新确认任务状态")
            delay(RETRY_DELAY_MILLIS)
            val refreshedAction = findTaskActionOnCurrentScreen(task, bridge)
            if (refreshedAction != null && refreshedAction.isGoComplete()) {
                val elapsedMillis = System.currentTimeMillis() - startedAtMillis
                if (
                    attempt >= MAX_GO_COMPLETE_STILL_VISIBLE_ATTEMPTS ||
                    elapsedMillis >= GO_COMPLETE_STILL_VISIBLE_WINDOW_MILLIS
                ) {
                    AppLogStore.add(
                        "任务状态仍是“去完成”，但 ${attempt} 次点击 / ${elapsedMillis / 1_000} 秒内始终未进入广告页，交给整轮重启恢复"
                    )
                    return null
                }
                AppLogStore.add("任务状态仍是“去完成”，继续点击；当前仍在安全重试窗口内")
                action = refreshedAction
            } else {
                AppLogStore.add("未重新定位到“去完成”，停止使用旧坐标重试")
                return null
            }
        }
    }

    private suspend fun tapCloseButtonOrFail(
        bridge: AccessibilityBridge,
        executor: ActionExecutor,
        phase: String
    ): CloseButtonMatch? {
        val closeMatch = waitForCloseButton(bridge, timeoutMillis = 8_000) ?: return null
        tapDetectedCloseButton(closeMatch, executor, phase)
        return closeMatch
    }

    private suspend fun tapDetectedCloseButton(
        closeMatch: CloseButtonMatch,
        executor: ActionExecutor,
        phase: String
    ) {
        executor.execute(AutomationAction.TapPoint(closeMatch.point)).getOrThrow()
        AppLogStore.add(
            "已点击右上角关闭按钮（$phase）：${closeMatch.templateName}，分数 ${"%.3f".format(closeMatch.score)}"
        )
        delay(700)
    }

    private suspend fun logCurrentOcrPreview(
        label: String,
        bridge: AccessibilityBridge
    ) {
        captureOcrScreen(bridge).getOrNull()?.let { screen ->
            AppLogStore.add("$label OCR 预览：${screen.ocr.logPreview()}")
        }
    }

    private suspend fun waitForTree(
        bridge: AccessibilityBridge,
        label: String,
        timeoutMillis: Long,
        predicate: (UiTreeSnapshot) -> Boolean
    ): UiTreeSnapshot? {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val tree = bridge.readActiveWindow()
            if (tree != null && predicate(tree)) {
                AppLogStore.add("已识别：$label")
                return tree
            }
            delay(500)
        }
        return null
    }

    private suspend fun waitForOcr(
        bridge: AccessibilityBridge,
        label: String,
        timeoutMillis: Long,
        predicate: (OcrResult) -> Boolean
    ): OcrScreen? {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val screen = captureOcrScreen(bridge).getOrNull()
            if (screen != null && predicate(screen.ocr)) {
                AppLogStore.add("OCR 已识别：$label，耗时 ${screen.ocr.elapsedMillis} ms")
                return screen
            }
            delay(700)
        }
        return null
    }

    private suspend fun waitForWelfareCenter(
        bridge: AccessibilityBridge,
        timeoutMillis: Long
    ): OcrScreen? {
        val deadline = System.currentTimeMillis() + timeoutMillis
        var lastPreview = ""
        while (System.currentTimeMillis() < deadline) {
            val screen = captureOcrScreen(bridge).getOrNull()
            if (screen != null) {
                if (screen.ocr.hasWelfareCenterMarkers()) {
                    AppLogStore.add(
                        "OCR 已识别：福利中心，命中：${screen.ocr.welfareCenterMarkerSummary()}，耗时 ${screen.ocr.elapsedMillis} ms"
                    )
                    return screen
                }
                lastPreview = screen.ocr.logPreview()
            }
            delay(700)
        }
        if (lastPreview.isNotBlank()) {
            AppLogStore.add("福利中心 OCR 未满足验证条件，最近预览：$lastPreview")
        }
        return null
    }

    private suspend fun captureOcrScreen(bridge: AccessibilityBridge): Result<OcrScreen> {
        var bitmap: Bitmap? = null
        return runCatching {
            bitmap = bridge.captureScreenshot().getOrThrow()
            val activeBitmap = bitmap ?: error("截图为空")
            val ocr = ocrEngine.recognize(activeBitmap).getOrThrow()
            OcrScreen(
                ocr = ocr,
                width = activeBitmap.width,
                height = activeBitmap.height
            )
        }.also {
            bitmap?.recycle()
        }
    }

    private suspend fun detectCloseButton(bridge: AccessibilityBridge): today.qdreader.auto.vision.CloseButtonMatch? {
        var bitmap: Bitmap? = null
        return try {
            bitmap = bridge.captureScreenshot().getOrThrow()
            closeButtonDetector.detectCloseButton(bitmap).getOrThrow()
        } finally {
            bitmap?.recycle()
        }
    }

    private suspend fun waitForCloseButton(
        bridge: AccessibilityBridge,
        timeoutMillis: Long
    ): CloseButtonMatch? {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val match = runCatching { detectCloseButton(bridge) }.getOrNull()
            if (match != null) {
                return match
            }
            delay(600)
        }
        return inferCloseButtonFromAdOcrFallback(bridge)
    }

    private suspend fun inferCloseButtonFromAdOcrFallback(
        bridge: AccessibilityBridge
    ): CloseButtonMatch? {
        val screen = captureOcrScreen(bridge).getOrNull() ?: return null
        if (!screen.ocr.hasAdCloseFallbackHints()) {
            AppLogStore.add("关闭按钮 OpenCV 未命中，广告页 OCR 兜底也未满足；OCR 预览：${screen.ocr.logPreview()}")
            return null
        }

        val point = ScreenPoint(screen.width * 0.94f, screen.height * 0.10f)
        val radius = (screen.width * 0.045f).coerceIn(20f, 42f).toInt()
        AppLogStore.add("关闭按钮 OpenCV 未命中，但 OCR 命中广告页提示，使用右上角兜底坐标")
        return CloseButtonMatch(
            point = point,
            bounds = android.graphics.Rect(
                (point.x - radius).toInt(),
                (point.y - radius).toInt(),
                (point.x + radius).toInt(),
                (point.y + radius).toInt()
            ),
            score = AD_OCR_CLOSE_FALLBACK_SCORE,
            templateName = "ocr_ad_hint_top_right_fallback"
        )
    }

    private fun UiTreeSnapshot.hasBottomTabs(): Boolean {
        return BOTTOM_TABS.all { title -> hasNode(title, TAB_TITLE_ID) }
    }

    private fun UiTreeSnapshot.isLoggedOutMyPage(): Boolean {
        return hasNode("登录/注册", LOGIN_HINT_ID) ||
            hasNode("登录解锁更多精彩功能", NEW_USER_TAG_ID)
    }

    private fun UiTreeSnapshot.isMyPageCandidate(): Boolean {
        return packageName == AppConstants.QIDIAN_PACKAGE &&
            (isLoggedOutMyPage() || findNode(text = "福利中心", viewId = WELFARE_TITLE_ID) != null)
    }

    private fun UiTreeSnapshot.hasWelfareCenterMarkers(): Boolean {
        val matchedGroups = WELFARE_CENTER_MARKER_GROUPS.mapNotNull { (groupName, aliases) ->
            if (aliases.any { alias -> hasNode(alias) }) groupName else null
        }
        val strongGroupCount = matchedGroups.count { group -> group != WELFARE_CENTER_TITLE_MARKER }
        val hasTitle = matchedGroups.contains(WELFARE_CENTER_TITLE_MARKER)
        return strongGroupCount >= 2 || (hasTitle && strongGroupCount >= 1)
    }

    private fun OcrResult.hasWelfareCenterMarkers(): Boolean {
        val matchedGroups = matchedWelfareCenterMarkerGroups()
        val strongGroupCount = matchedGroups.count { group -> group != WELFARE_CENTER_TITLE_MARKER }
        val hasTitle = matchedGroups.contains(WELFARE_CENTER_TITLE_MARKER)
        return strongGroupCount >= 2 ||
            (hasTitle && strongGroupCount >= 1) ||
            hasWelfareTaskAreaMarkers()
    }

    private fun OcrResult.welfareCenterMarkerSummary(): String {
        val matchedGroups = matchedWelfareCenterMarkerGroups().toMutableList()
        if (hasWelfareTaskAreaMarkers()) {
            matchedGroups += "任务区域"
        }
        return if (matchedGroups.isEmpty()) "无" else matchedGroups.joinToString("、")
    }

    private fun OcrResult.matchedWelfareCenterMarkerGroups(): List<String> {
        return WELFARE_CENTER_MARKER_GROUPS.mapNotNull { (groupName, aliases) ->
            if (aliases.any { alias -> hasText(alias) }) groupName else null
        }
    }

    private fun OcrResult.hasWelfareTaskAreaMarkers(): Boolean {
        val hasTaskAnchor = WELFARE_AD_TASKS.any { task -> hasAnyText(task.matchTexts) }
        val hasTaskState = hasAnyText(TASK_ACTION_TEXTS)
        val hasRewardHint = hasAnyText(WELFARE_TASK_REWARD_HINT_TEXTS)
        return hasTaskAnchor && (hasTaskState || hasRewardHint)
    }

    private fun OcrResult.hasAdCloseFallbackHints(): Boolean {
        return hasAnyText(AD_CLOSE_FALLBACK_REWARD_HINT_TEXTS) &&
            hasAnyText(AD_CLOSE_FALLBACK_CONTEXT_TEXTS)
    }

    private fun OcrActionTextMatch.isGoComplete(): Boolean {
        return actionText in GO_COMPLETE_TEXTS || actionText == INFERRED_GO_COMPLETE_TEXT
    }

    private fun restartableFailure(message: String): FlowExecutionResult {
        return FlowExecutionResult(
            completed = false,
            message = message,
            restartRequested = true
        )
    }

    private fun OcrScreen.upSwipeAction(): AutomationAction.SwipePoints {
        val centerX = width * 0.5f
        return AutomationAction.SwipePoints(
            start = ScreenPoint(centerX, height * 0.78f),
            end = ScreenPoint(centerX, height * 0.36f),
            durationMillis = 520
        )
    }

    private fun OcrScreen.findBrowseActionPoint(): ScreenPoint? {
        val directPoint = ocr.findAnyTextCenter(BROWSE_ACTION_TEXTS)
        if (directPoint != null) {
            return directPoint
        }

        val giveUpPoint = ocr.findAnyTextCenter(GIVE_UP_REWARD_TEXTS) ?: return null
        val inferredActionX = if (giveUpPoint.x < width * 0.5f) width * 0.72f else width * 0.28f
        AppLogStore.add("仅识别到“放弃奖励”，按同一行另一侧推断“点击去浏览”坐标")
        return ScreenPoint(inferredActionX, giveUpPoint.y)
    }

    private fun OcrScreen.findTaskActionByRowFallback(task: WelfareAdTask): OcrActionTextMatch? {
        val anchor = findTaskAnchorBounds(task) ?: return null
        val doneAction = findNearestTaskAction(anchor, TASK_DONE_TEXTS)
        if (doneAction != null) {
            AppLogStore.add("OCR 行兜底匹配到“${task.title}”已领取状态")
            return doneAction
        }

        val goAction = findNearestTaskAction(anchor, GO_COMPLETE_TEXTS)
        if (goAction != null) {
            AppLogStore.add("OCR 行兜底匹配到“${task.title}”去完成按钮")
            return goAction
        }

        val inferredBounds = inferredTaskButtonBounds(anchor)
        AppLogStore.add("OCR 找到“${task.title}”标题但未读到右侧按钮，按同一行右侧推断“去完成”坐标")
        return OcrActionTextMatch(
            actionText = INFERRED_GO_COMPLETE_TEXT,
            point = ScreenPoint(inferredBounds.exactCenterX(), inferredBounds.exactCenterY()),
            bounds = inferredBounds
        )
    }

    private fun OcrScreen.findTaskAnchorBounds(task: WelfareAdTask): Rect? {
        val minY = height * TASK_ROW_MIN_Y_FRACTION
        val maxY = height * TASK_ROW_MAX_Y_FRACTION
        task.rowAnchorTexts.forEach { anchorText ->
            val match = ocr.findBlocksContaining(anchorText)
                .mapNotNull { block -> block.bounds }
                .filter { bounds ->
                    bounds.exactCenterY() in minY..maxY
                }
                .minByOrNull { bounds -> bounds.top * width + bounds.left }
            if (match != null) return match
        }
        return null
    }

    private fun OcrScreen.findNearestTaskAction(
        anchor: Rect,
        actionTexts: List<String>
    ): OcrActionTextMatch? {
        return actionTexts
            .flatMap { actionText ->
                ocr.findBlocksContaining(actionText).mapNotNull { block ->
                    block.bounds?.let { bounds -> actionText to bounds }
                }
            }
            .filter { (_, bounds) -> bounds.isLikelySameVisibleTaskRow(anchor, width) }
            .minByOrNull { (_, bounds) ->
                abs(bounds.exactCenterY() - anchor.exactCenterY()) * 4 +
                    abs(bounds.exactCenterX() - expectedTaskButtonCenterX())
            }
            ?.let { (actionText, bounds) ->
                OcrActionTextMatch(
                    actionText = actionText,
                    point = ScreenPoint(bounds.exactCenterX(), bounds.exactCenterY()),
                    bounds = bounds
                )
            }
    }

    private fun Rect.isLikelySameVisibleTaskRow(anchor: Rect, screenWidth: Int): Boolean {
        val verticalTolerance = minOf(72f, maxOf(48f, anchor.height() * 1.2f))
        val isRightButtonArea = exactCenterX() >= screenWidth * TASK_BUTTON_MIN_X_FRACTION
        return isRightButtonArea && abs(exactCenterY() - anchor.exactCenterY()) <= verticalTolerance
    }

    private fun OcrScreen.inferredTaskButtonBounds(anchor: Rect): Rect {
        val centerX = expectedTaskButtonCenterX()
        val centerY = anchor.exactCenterY()
        val halfWidth = width * 0.095f
        val halfHeight = maxOf(26f, anchor.height() * 0.9f)
        return Rect(
            (centerX - halfWidth).toInt(),
            (centerY - halfHeight).toInt(),
            (centerX + halfWidth).toInt(),
            (centerY + halfHeight).toInt()
        )
    }

    private fun OcrScreen.expectedTaskButtonCenterX(): Float {
        return width * 0.87f
    }

    private fun OcrScreen.inferRewardConfirmButtonPoint(): ScreenPoint {
        val rewardTextPoint = findCenteredRewardStrongTextPoint()
            ?: findCenteredRewardConfirmTextPoint()
        if (rewardTextPoint != null) {
            return ScreenPoint(rewardTextPoint.x, rewardTextPoint.y + height * 0.11f)
        }
        return ScreenPoint(
            width * DEFAULT_REWARD_CONFIRM_X_FRACTION,
            height * DEFAULT_REWARD_CONFIRM_Y_FRACTION
        )
    }

    private fun OcrScreen.findCenteredRewardStrongTextPoint(): ScreenPoint? {
        val minX = width * 0.15f
        val maxX = width * 0.85f
        val minY = height * 0.28f
        val maxY = height * 0.68f
        return REWARD_DIALOG_STRONG_TEXTS
            .flatMap { text -> ocr.findBlocksContaining(text) }
            .mapNotNull { block -> block.bounds }
            .filter { bounds ->
                bounds.exactCenterX() in minX..maxX &&
                    bounds.exactCenterY() in minY..maxY
            }
            .minByOrNull { bounds -> bounds.top * width + bounds.left }
            ?.let { bounds -> ScreenPoint(bounds.exactCenterX(), bounds.exactCenterY()) }
    }

    private fun OcrScreen.findCenteredRewardConfirmTextPoint(): ScreenPoint? {
        val minX = width * 0.28f
        val maxX = width * 0.72f
        val minY = height * 0.38f
        val maxY = height * 0.60f
        return REWARD_DIALOG_FALLBACK_TEXTS
            .flatMap { text -> ocr.findBlocksContaining(text) }
            .mapNotNull { block -> block.bounds }
            .filter { bounds ->
                bounds.exactCenterX() in minX..maxX &&
                    bounds.exactCenterY() in minY..maxY
            }
            .minByOrNull { bounds -> bounds.top * width + bounds.left }
            ?.let { bounds -> ScreenPoint(bounds.exactCenterX(), bounds.exactCenterY()) }
    }

    companion object {
        private const val TAB_TITLE_ID = "com.qidian.QDReader:id/view_tab_title_title"
        private const val LOGIN_HINT_ID = "com.qidian.QDReader:id/tvLoginHint"
        private const val NEW_USER_TAG_ID = "com.qidian.QDReader:id/newUserTag"
        private const val WELFARE_TITLE_ID = "com.qidian.QDReader:id/tvTitle"
        private const val INCENTIVE_TASK_TEXT = "激励任务"
        private const val THREE_AD_TASK_TEXT = "完成3个广告任务得奖励"
        private const val ONE_AD_TASK_TEXT = "完成1个广告任务得奖励"
        private const val GO_COMPLETE_TEXT = "去完成"
        private const val INFERRED_GO_COMPLETE_TEXT = "去完成(行兜底)"
        private const val CLAIMED_TEXT = "已领取"
        private const val COMPLETED_TEXT = "已完成"
        private const val KNOW_TEXT = "知道了"
        private const val MAX_CLICK_ATTEMPTS = 3
        private const val SHORT_VERIFY_TIMEOUT_MILLIS = 2_000L
        private const val NAVIGATION_VERIFY_TIMEOUT_MILLIS = 4_000L
        private const val WELFARE_VERIFY_TIMEOUT_MILLIS = 10_000L
        private const val AD_ENTRY_VERIFY_TIMEOUT_MILLIS = 6_000L
        private const val BROWSE_DIALOG_VERIFY_TIMEOUT_MILLIS = 5_000L
        private const val REWARD_CONFIRM_TIMEOUT_MILLIS = 6_000L
        private const val REWARD_CONFIRM_MAX_TAP_ATTEMPTS = 3
        private const val REWARD_CONFIRM_DISMISS_VERIFY_DELAY_MILLIS = 900L
        private const val DEFAULT_REWARD_CONFIRM_X_FRACTION = 0.50f
        private const val DEFAULT_REWARD_CONFIRM_Y_FRACTION = 0.66f
        private const val GO_COMPLETE_TO_REWARD_CONFIRM_TIMEOUT_MILLIS = 60_000L
        private const val GO_COMPLETE_STILL_VISIBLE_WINDOW_MILLIS = 60_000L
        private const val MAX_GO_COMPLETE_STILL_VISIBLE_ATTEMPTS = 6
        private const val TASK_ATTEMPT_TIMEOUT_MILLIS = 8 * 60_000L
        private const val MY_PAGE_READY_DELAY_MILLIS = 2_000L
        private const val WELFARE_ENTRY_STABLE_TIMEOUT_MILLIS = 6_000L
        private const val WELFARE_ENTRY_STABLE_SAMPLE_INTERVAL_MILLIS = 450L
        private const val WELFARE_ENTRY_REQUIRED_STABLE_SAMPLES = 3
        private const val WELFARE_ENTRY_MAX_POSITION_DRIFT_PX = 6
        private const val RETRY_DELAY_MILLIS = 1_000L
        private const val AD_OCR_CLOSE_FALLBACK_SCORE = 0.52
        private const val TASK_ROW_MIN_Y_FRACTION = 0.12f
        private const val TASK_ROW_MAX_Y_FRACTION = 0.96f
        private const val TASK_BUTTON_MIN_X_FRACTION = 0.64f

        private val BOTTOM_TABS = listOf("书架", "精选", "发现", "我")
        private const val WELFARE_CENTER_TITLE_MARKER = "福利中心"
        private val WELFARE_CENTER_MARKER_GROUPS = listOf(
            WELFARE_CENTER_TITLE_MARKER to listOf("福利中心"),
            "本周收益" to listOf("本周收益", "周收益", "本周收"),
            "积分商城" to listOf("积分商城", "积分商场", "积分商"),
            "完成任务得奖励" to listOf("完成任务得奖励", "任务得奖励", "完成任务", "得奖励")
        )
        private val GO_COMPLETE_TEXTS = listOf(GO_COMPLETE_TEXT, "去完", "去宪成", "去完咸")
        private val CLAIMED_TEXTS = listOf(CLAIMED_TEXT, "已领", "己领取", "己领")
        private val COMPLETED_TEXTS = listOf(COMPLETED_TEXT, "已完", "己完成", "己完")
        private val TASK_DONE_TEXTS = CLAIMED_TEXTS + COMPLETED_TEXTS
        private val TASK_ACTION_TEXTS = TASK_DONE_TEXTS + GO_COMPLETE_TEXTS
        private val WELFARE_TASK_REWARD_HINT_TEXTS = listOf("广告任务", "章节卡", "订阅券", "多重好礼")
        private val BROWSE_ACTION_TEXTS = listOf(
            "点击去浏览",
            "去浏览",
            "继续浏览",
            "继续观看",
            "继续看视频",
            "继续看",
            "去观看",
            "点击去看",
            "去查看"
        )
        private val GIVE_UP_REWARD_TEXTS = listOf("放弃奖励", "放弃")
        private val BROWSE_DIALOG_HINT_TEXTS = BROWSE_ACTION_TEXTS + GIVE_UP_REWARD_TEXTS + listOf(
            "看15秒",
            "15秒",
            "获得奖励",
            "领取奖励"
        )
        private val AD_CLOSE_FALLBACK_REWARD_HINT_TEXTS = listOf(
            "点击后",
            "看15秒",
            "15秒",
            "可获得奖励",
            "获得奖励"
        )
        private val AD_CLOSE_FALLBACK_CONTEXT_TEXTS = listOf(
            "查看详情",
            "进入详情页",
            "第三方应用",
            "广告"
        )
        private val REWARD_GRANTED_TEXTS = listOf("恭喜已获得奖励", "恭喜获得奖励", "恭喜获得", "已获得奖励", "奖励已到账")
        private val KNOW_BUTTON_TEXTS = listOf(KNOW_TEXT, "我知道了", "知道啦", "知道")
        private val REWARD_DIALOG_STRONG_TEXTS = listOf(
            "恭喜获得",
            "恭喜已获得奖励",
            "恭喜获得奖励",
            "已获得奖励",
            "获得奖励",
            "恭喜"
        )
        private val REWARD_DIALOG_FALLBACK_TEXTS = listOf(
            "订阅券",
            "章节卡",
            "点币"
        )
        private val WELFARE_AD_TASKS = listOf(
            WelfareAdTask(
                title = INCENTIVE_TASK_TEXT,
                matchTexts = listOf(INCENTIVE_TASK_TEXT, "激励", "完成广告任务", "多重好礼"),
                rowAnchorTexts = listOf(INCENTIVE_TASK_TEXT, "激励任务", "激励")
            ),
            WelfareAdTask(
                title = THREE_AD_TASK_TEXT,
                matchTexts = listOf(THREE_AD_TASK_TEXT, "完成3个广告", "再完成3次", "10点章节卡"),
                rowAnchorTexts = listOf(THREE_AD_TASK_TEXT, "完成3个广告")
            ),
            WelfareAdTask(
                title = ONE_AD_TASK_TEXT,
                matchTexts = listOf(ONE_AD_TASK_TEXT, "完成1个广告", "满10点", "3点订阅券"),
                rowAnchorTexts = listOf(ONE_AD_TASK_TEXT, "完成1个广告")
            )
        )
        private const val TASK_STATUS_POLL_ATTEMPTS = 4
    }
}

private data class OcrScreen(
    val ocr: OcrResult,
    val width: Int,
    val height: Int
)

private data class WelfareAdTask(
    val title: String,
    val matchTexts: List<String>,
    val rowAnchorTexts: List<String>
)

private fun OcrResult.logPreview(): String {
    val lines = blocks
        .map { block -> block.text.trim() }
        .filter { text -> text.isNotEmpty() }
        .take(16)
    val preview = if (lines.isNotEmpty()) lines.joinToString(" | ") else rawText.replace('\n', '|')
    return preview.take(220)
}
