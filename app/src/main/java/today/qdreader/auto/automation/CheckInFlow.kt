package today.qdreader.auto.automation

import android.graphics.Bitmap
import android.graphics.Rect
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import today.qdreader.auto.accessibility.ScreenPoint
import today.qdreader.auto.accessibility.AccessibilityBridge
import today.qdreader.auto.accessibility.UiNodeSnapshot
import today.qdreader.auto.accessibility.UiTreeSnapshot
import today.qdreader.auto.accessibility.clickPointFor
import today.qdreader.auto.accessibility.findNode
import today.qdreader.auto.accessibility.flatten
import today.qdreader.auto.accessibility.hasNode
import today.qdreader.auto.core.AppConstants
import today.qdreader.auto.logs.AppLogStore
import today.qdreader.auto.vision.OcrEngine
import today.qdreader.auto.vision.OcrResult
import today.qdreader.auto.vision.findAnyTextCenter
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
    private val ocrEngine: OcrEngine
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

        AppLogStore.add("步骤 5：组件已确认福利中心，命中“本周收益”和“积分商城”")

        AppLogStore.add("步骤 6：上滑福利中心页面，仅执行一次")
        executor.execute(welfareCenter.upSwipeAction()).getOrThrow()
        delay(900)

        AppLogStore.add("步骤 7-13：开始按组件处理广告奖励任务")
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
    ): UiTreeSnapshot? {
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
                AppLogStore.add("点击“福利中心”后未同时找到“本周收益”和“积分商城”，开始恢复“我的”界面")
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
                ?: return restartableFailure("组件树未找到“${task.title}”同一任务行内的“去完成 / 已领取”状态")

            when {
                action.actionText in TASK_DONE_TEXTS -> {
                    AppLogStore.add("福利任务“${task.title}”已领取")
                    return FlowExecutionResult(true, "福利任务“${task.title}”已领取")
                }

                action.actionText == GO_COMPLETE_TEXT -> {
                    round += 1
                    AppLogStore.add("福利任务“${task.title}”当前仍是“去完成”，继续执行第 $round 轮")
                    val adResult = completeAdRewardRound(task, round, bridge, executor)
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
    ): TaskComponentMatch? {
        repeat(TASK_STATUS_POLL_ATTEMPTS) { index ->
            val tree = bridge.readActiveWindow()
            val action = tree?.findTaskAction(task)
            if (action != null) {
                AppLogStore.add("组件已定位“${task.title}”状态：${action.actionText}")
                return action
            }

            if (index < TASK_STATUS_POLL_ATTEMPTS - 1) {
                AppLogStore.add("当前组件树未找到“${task.title}”任务状态，等待 WebView 稳定后重试")
                delay(COMPONENT_POLL_INTERVAL_MILLIS)
            }
        }
        return null
    }

    private suspend fun completeAdRewardRound(
        task: WelfareAdTask,
        round: Int,
        bridge: AccessibilityBridge,
        executor: ActionExecutor
    ): FlowExecutionResult {
        return try {
            withTimeout(GO_COMPLETE_TO_REWARD_CONFIRM_TIMEOUT_MILLIS) {
                completeAdRewardRoundWithinWatchdog(task, round, bridge, executor)
            }
        } catch (exception: TimeoutCancellationException) {
            restartableFailure("“${task.title}”第 $round 轮点击“去完成”后 ${GO_COMPLETE_TO_REWARD_CONFIRM_TIMEOUT_MILLIS / 1_000} 秒内未完成“知道了”确认，按卡住处理")
        }
    }

    private suspend fun completeAdRewardRoundWithinWatchdog(
        task: WelfareAdTask,
        round: Int,
        bridge: AccessibilityBridge,
        executor: ActionExecutor
    ): FlowExecutionResult {
        AppLogStore.add("步骤 8：点击“去完成”并验证广告界面")
        val initialCloseMatch = tapGoCompleteAndWaitForAd(task, round, bridge, executor)
            ?: return restartableFailure("“${task.title}”第 $round 轮未找到广告页右上角关闭组件")

        AppLogStore.add("步骤 9：关闭广告并等待“点击去浏览 / 放弃奖励”弹窗")
        openBrowseDialogWithRetry(task, round, initialCloseMatch, bridge, executor)
            ?: return restartableFailure("“${task.title}”第 $round 轮未识别到“点击去浏览”弹窗")

        delay(COMPONENT_CLICK_DEBOUNCE_MILLIS)
        val refreshedBrowseDialog = captureOcrScreen(bridge).getOrNull()
            ?: return restartableFailure("“${task.title}”第 $round 轮点击“点击去浏览”前无法刷新界面")
        val browsePoint = refreshedBrowseDialog.findBrowseActionPoint()
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
            ?: return restartableFailure("“${task.title}”第 $round 轮返回后未找到广告关闭组件")

        AppLogStore.add("步骤 11：检查奖励确认弹窗")
        val rewardDialogHandled = tapRewardConfirmIfPresent(bridge, executor)
        if (rewardDialogHandled) {
            AppLogStore.add("奖励确认弹窗已消失，允许重新检查任务状态")
        } else {
            AppLogStore.add("未显示奖励确认弹窗，重新检查“${task.title}”任务状态")
            val currentAction = findTaskActionOnCurrentScreen(task, bridge)
            when (currentAction?.actionText) {
                GO_COMPLETE_TEXT -> AppLogStore.add(
                    "“${task.title}”仍显示“去完成”，本轮不触发整轮重启，继续执行下一轮广告"
                )

                CLAIMED_TEXT -> AppLogStore.add("“${task.title}”已显示“已领取”，本轮正常结束")
                else -> return restartableFailure(
                    "“${task.title}”第 $round 轮既未显示奖励确认弹窗，也未找到当前任务状态"
                )
            }
        }

        return FlowExecutionResult(true, "“${task.title}”第 $round 轮广告奖励已处理")
    }

    private suspend fun tapRewardConfirmIfPresent(
        bridge: AccessibilityBridge,
        executor: ActionExecutor
    ): Boolean {
        waitForRewardConfirmDialog(bridge, timeoutMillis = REWARD_CONFIRM_TIMEOUT_MILLIS)
            ?: return false
        return dismissRewardConfirmDialog(bridge, executor)
    }

    private suspend fun clearRewardDialogBeforeTaskAction(
        bridge: AccessibilityBridge,
        executor: ActionExecutor
    ) {
        bridge.readActiveWindow()?.findRewardConfirmDialog() ?: return

        AppLogStore.add("点击任务按钮前通过组件检测到残留奖励弹窗，优先点击同组内的“知道了”")
        if (!dismissRewardConfirmDialog(bridge, executor)) {
            AppLogStore.add("奖励弹窗连续处理仍未确认消失；按规则重新识别当前任务，找不到弹窗时才点击“去完成”")
        }
    }

    private suspend fun dismissRewardConfirmDialog(
        bridge: AccessibilityBridge,
        executor: ActionExecutor
    ): Boolean {
        for (attempt in 1..REWARD_CONFIRM_MAX_TAP_ATTEMPTS) {
            delay(COMPONENT_CLICK_DEBOUNCE_MILLIS)
            val dialog = bridge.readActiveWindow()?.findRewardConfirmDialog() ?: return true
            val tapPoint = dialog.tree.clickPointFor(dialog.confirmNode)
            executor.execute(AutomationAction.TapPoint(tapPoint)).getOrThrow()
            AppLogStore.add(
                "已点击“${dialog.confirmNode.text ?: KNOW_TEXT}”组件，且同组包含“${dialog.rewardNode.text}”" +
                    "（$attempt/$REWARD_CONFIRM_MAX_TAP_ATTEMPTS）"
            )

            delay(REWARD_CONFIRM_DISMISS_VERIFY_DELAY_MILLIS)
            if (bridge.readActiveWindow()?.findRewardConfirmDialog() == null) {
                AppLogStore.add("已确认奖励弹窗消失")
                return true
            }
            AppLogStore.add("奖励弹窗组件仍存在，继续点击同组内的“知道了”")
        }
        return false
    }

    private suspend fun waitForRewardConfirmDialog(
        bridge: AccessibilityBridge,
        timeoutMillis: Long
    ): RewardConfirmComponent? {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val dialog = bridge.readActiveWindow()?.findRewardConfirmDialog()
            if (dialog != null) {
                AppLogStore.add("组件已识别：奖励确认弹窗“${dialog.rewardNode.text}”及同组“${dialog.confirmNode.text}”")
                return dialog
            }
            delay(COMPONENT_POLL_INTERVAL_MILLIS)
        }
        return null
    }

    private suspend fun openBrowseDialogWithRetry(
        task: WelfareAdTask,
        round: Int,
        initialCloseMatch: AdCloseComponent,
        bridge: AccessibilityBridge,
        executor: ActionExecutor
    ): OcrScreen? {
        var closeMatch: AdCloseComponent? = initialCloseMatch
        for (attempt in 1..MAX_CLICK_ATTEMPTS) {
            val activeCloseMatch = closeMatch ?: waitForAdCloseButton(bridge, timeoutMillis = SHORT_VERIFY_TIMEOUT_MILLIS)
            if (activeCloseMatch == null) {
                AppLogStore.add("第 $attempt/$MAX_CLICK_ATTEMPTS 次打开浏览弹窗前未找到 content-desc=“$AD_CLOSE_CONTENT_DESCRIPTION”的组件")
                logCurrentOcrPreview("浏览弹窗关闭按钮缺失", bridge)
            } else {
                tapAdCloseComponent(bridge, executor, "广告奖励选择前 $attempt/$MAX_CLICK_ATTEMPTS")
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
                closeMatch = waitForAdCloseButton(bridge, timeoutMillis = SHORT_VERIFY_TIMEOUT_MILLIS)
            }
        }
        return null
    }

    private suspend fun tapGoCompleteAndWaitForAd(
        task: WelfareAdTask,
        round: Int,
        bridge: AccessibilityBridge,
        executor: ActionExecutor
    ): AdCloseComponent? {
        var attempt = 0
        val startedAtMillis = System.currentTimeMillis()
        while (true) {
            attempt += 1
            AppLogStore.add("福利任务“${task.title}”：第 $round 轮点击“去完成”（第 $attempt 次）")
            delay(COMPONENT_CLICK_DEBOUNCE_MILLIS)
            val refreshedBeforeTap = bridge.readActiveWindow()?.findTaskAction(task)
            if (refreshedBeforeTap?.actionText != GO_COMPLETE_TEXT) {
                AppLogStore.add("防抖后“${task.title}”的“去完成”组件已刷新或消失，停止点击旧位置")
                return null
            }
            executor.execute(
                AutomationAction.TapPoint(refreshedBeforeTap.tree.clickPointFor(refreshedBeforeTap.actionNode))
            ).getOrThrow()

            val closeMatch = waitForAdCloseButton(bridge, timeoutMillis = AD_ENTRY_VERIFY_TIMEOUT_MILLIS)
            if (closeMatch != null) {
                AppLogStore.add("已通过 content-desc=“$AD_CLOSE_CONTENT_DESCRIPTION”的组件确认进入广告界面")
                return closeMatch
            }

            AppLogStore.add("点击“去完成”后未检测到广告关闭组件，等待后重新确认任务状态")
            delay(RETRY_DELAY_MILLIS)
            val refreshedAction = findTaskActionOnCurrentScreen(task, bridge)
            if (refreshedAction?.actionText == GO_COMPLETE_TEXT) {
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
    ): AdCloseComponent? {
        val closeMatch = waitForAdCloseButton(bridge, timeoutMillis = 8_000) ?: return null
        if (!tapAdCloseComponent(bridge, executor, phase)) return null
        return closeMatch
    }

    private suspend fun tapAdCloseComponent(
        bridge: AccessibilityBridge,
        executor: ActionExecutor,
        phase: String
    ): Boolean {
        delay(COMPONENT_CLICK_DEBOUNCE_MILLIS)
        val refreshed = bridge.readActiveWindow()?.findAdCloseComponent() ?: return false
        executor.execute(
            AutomationAction.TapPoint(refreshed.tree.clickPointFor(refreshed.closeNode))
        ).getOrThrow()
        AppLogStore.add("已点击广告关闭组件（$phase）：content-desc=“$AD_CLOSE_CONTENT_DESCRIPTION”")
        delay(700)
        return true
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
    ): UiTreeSnapshot? {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val tree = bridge.readActiveWindow()
            if (tree?.hasWelfareCenterMarkers() == true) {
                AppLogStore.add("组件已识别：福利中心，同时找到“本周收益”和“积分商城”")
                return tree
            }
            delay(COMPONENT_POLL_INTERVAL_MILLIS)
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

    private suspend fun waitForAdCloseButton(
        bridge: AccessibilityBridge,
        timeoutMillis: Long
    ): AdCloseComponent? {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val match = bridge.readActiveWindow()?.findAdCloseComponent()
            if (match != null) {
                return match
            }
            delay(COMPONENT_POLL_INTERVAL_MILLIS)
        }
        return null
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
        return packageName == AppConstants.QIDIAN_PACKAGE &&
            root.flatten().any { node -> node.isTextViewWithText(WELFARE_WEEKLY_EARNINGS_TEXT) } &&
            root.flatten().any { node -> node.isTextViewWithText(WELFARE_POINTS_MALL_TEXT) }
    }

    private fun restartableFailure(message: String): FlowExecutionResult {
        return FlowExecutionResult(
            completed = false,
            message = message,
            restartRequested = true
        )
    }

    private fun UiTreeSnapshot.upSwipeAction(): AutomationAction.SwipePoints {
        val width = root.bounds.width()
        val height = root.bounds.height()
        val centerX = root.bounds.left + width * 0.5f
        return AutomationAction.SwipePoints(
            start = ScreenPoint(centerX, root.bounds.top + height * 0.78f),
            end = ScreenPoint(centerX, root.bounds.top + height * 0.36f),
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

    private fun UiTreeSnapshot.findTaskAction(task: WelfareAdTask): TaskComponentMatch? {
        if (packageName != AppConstants.QIDIAN_PACKAGE) return null
        val row = findTaskRow(task) ?: return null
        val actionNode = TASK_STATUS_TEXTS.firstNotNullOfOrNull { statusText ->
            row.flatten().firstOrNull { node -> node.isTextViewWithText(statusText) }
        } ?: return null
        return TaskComponentMatch(
            tree = this,
            actionNode = actionNode,
            actionText = actionNode.text.orEmpty()
        )
    }

    private fun UiTreeSnapshot.findTaskRow(task: WelfareAdTask): UiNodeSnapshot? {
        val explicitRow = task.rowViewId?.let { rowViewId ->
            root.flatten().firstOrNull { node ->
                node.viewId.matchesWebViewId(rowViewId) &&
                    node.className == WEB_VIEW_ROW_CLASS_NAME &&
                    node.flatten().any { child -> child.isTextViewWithText(task.title) }
            }
        }
        if (explicitRow != null) return explicitRow

        return root.flatten()
            .filter { container ->
                val descendants = container.flatten().toList()
                descendants.any { node -> node.isTextViewWithText(task.title) } &&
                    descendants.any { node ->
                        TASK_STATUS_TEXTS.any { statusText -> node.isTextViewWithText(statusText) }
                    }
            }
            .minByOrNull { container -> container.bounds.safeArea() }
    }

    private fun UiTreeSnapshot.findAdCloseComponent(): AdCloseComponent? {
        if (packageName != AppConstants.QIDIAN_PACKAGE) return null
        val closeNode = root.flatten().firstOrNull { node ->
            node.contentDescription == AD_CLOSE_CONTENT_DESCRIPTION &&
                node.className == AD_CLOSE_CLASS_NAME &&
                node.enabled
        } ?: return null
        return AdCloseComponent(this, closeNode)
    }

    private fun UiTreeSnapshot.findRewardConfirmDialog(): RewardConfirmComponent? {
        if (packageName != AppConstants.QIDIAN_PACKAGE) return null
        val dialogGroup = root.flatten()
            .mapNotNull { container ->
                val descendants = container.flatten().toList()
                val rewardNode = descendants.firstOrNull { node ->
                    node.className == TEXT_VIEW_CLASS_NAME &&
                        node.text?.startsWith(REWARD_TITLE_PREFIX) == true
                }
                val confirmNode = descendants.firstOrNull { node -> node.isTextViewWithText(KNOW_TEXT) }
                if (rewardNode != null && confirmNode != null) {
                    Triple(container, rewardNode, confirmNode)
                } else {
                    null
                }
            }
            .minByOrNull { (container, _, _) -> container.bounds.safeArea() }
            ?: return null
        return RewardConfirmComponent(
            tree = this,
            rewardNode = dialogGroup.second,
            confirmNode = dialogGroup.third
        )
    }

    private fun Rect.safeArea(): Long {
        return width().coerceAtLeast(0).toLong() * height().coerceAtLeast(0).toLong()
    }

    private fun UiNodeSnapshot.isTextViewWithText(expectedText: String): Boolean {
        return className == TEXT_VIEW_CLASS_NAME && text == expectedText
    }

    private fun String?.matchesWebViewId(expectedId: String): Boolean {
        return this == expectedId || this?.endsWith(":id/$expectedId") == true
    }

    companion object {
        private const val TAB_TITLE_ID = "com.qidian.QDReader:id/view_tab_title_title"
        private const val LOGIN_HINT_ID = "com.qidian.QDReader:id/tvLoginHint"
        private const val NEW_USER_TAG_ID = "com.qidian.QDReader:id/newUserTag"
        private const val WELFARE_TITLE_ID = "com.qidian.QDReader:id/tvTitle"
        private const val INCENTIVE_TASK_TEXT = "激励任务"
        private const val THREE_AD_TASK_TEXT = "完成3个广告任务得奖励"
        private const val ONE_AD_TASK_TEXT = "完成1个广告任务得奖励"
        private const val THREE_AD_TASK_ROW_ID = "task_row_T2024010101"
        private const val ONE_AD_TASK_ROW_ID = "task_row_T2024010102"
        private const val GO_COMPLETE_TEXT = "去完成"
        private const val CLAIMED_TEXT = "已领取"
        private const val KNOW_TEXT = "知道了"
        private const val REWARD_TITLE_PREFIX = "恭喜获得"
        private const val WELFARE_WEEKLY_EARNINGS_TEXT = "本周收益"
        private const val WELFARE_POINTS_MALL_TEXT = "积分商城"
        private const val AD_CLOSE_CONTENT_DESCRIPTION = "点击退出关闭视频"
        private const val AD_CLOSE_CLASS_NAME = "android.view.ViewGroup"
        private const val TEXT_VIEW_CLASS_NAME = "android.widget.TextView"
        private const val WEB_VIEW_ROW_CLASS_NAME = "android.view.View"
        private const val MAX_CLICK_ATTEMPTS = 3
        private const val SHORT_VERIFY_TIMEOUT_MILLIS = 2_000L
        private const val NAVIGATION_VERIFY_TIMEOUT_MILLIS = 4_000L
        private const val WELFARE_VERIFY_TIMEOUT_MILLIS = 10_000L
        private const val AD_ENTRY_VERIFY_TIMEOUT_MILLIS = 6_000L
        private const val BROWSE_DIALOG_VERIFY_TIMEOUT_MILLIS = 5_000L
        private const val REWARD_CONFIRM_TIMEOUT_MILLIS = 6_000L
        private const val REWARD_CONFIRM_MAX_TAP_ATTEMPTS = 3
        private const val REWARD_CONFIRM_DISMISS_VERIFY_DELAY_MILLIS = 900L
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
        private const val COMPONENT_CLICK_DEBOUNCE_MILLIS = 500L
        private const val COMPONENT_POLL_INTERVAL_MILLIS = 500L

        private val BOTTOM_TABS = listOf("书架", "精选", "发现", "我")
        private val TASK_DONE_TEXTS = listOf(CLAIMED_TEXT)
        private val TASK_STATUS_TEXTS = TASK_DONE_TEXTS + GO_COMPLETE_TEXT
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
        private val REWARD_GRANTED_TEXTS = listOf("恭喜已获得奖励", "恭喜获得奖励", "恭喜获得", "已获得奖励", "奖励已到账")
        private val WELFARE_AD_TASKS = listOf(
            WelfareAdTask(
                title = INCENTIVE_TASK_TEXT,
                rowViewId = null
            ),
            WelfareAdTask(
                title = THREE_AD_TASK_TEXT,
                rowViewId = THREE_AD_TASK_ROW_ID
            ),
            WelfareAdTask(
                title = ONE_AD_TASK_TEXT,
                rowViewId = ONE_AD_TASK_ROW_ID
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
    val rowViewId: String?
)

private data class TaskComponentMatch(
    val tree: UiTreeSnapshot,
    val actionNode: UiNodeSnapshot,
    val actionText: String
)

private data class AdCloseComponent(
    val tree: UiTreeSnapshot,
    val closeNode: UiNodeSnapshot
)

private data class RewardConfirmComponent(
    val tree: UiTreeSnapshot,
    val rewardNode: UiNodeSnapshot,
    val confirmNode: UiNodeSnapshot
)

private fun OcrResult.logPreview(): String {
    val lines = blocks
        .map { block -> block.text.trim() }
        .filter { text -> text.isNotEmpty() }
        .take(16)
    val preview = if (lines.isNotEmpty()) lines.joinToString(" | ") else rawText.replace('\n', '|')
    return preview.take(220)
}
