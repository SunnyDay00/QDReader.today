package today.qdreader.auto.automation

import android.graphics.Bitmap
import kotlinx.coroutines.delay
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
import today.qdreader.auto.vision.hasText
import today.qdreader.auto.vision.hasAnyText

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
    val message: String
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
        AppLogStore.add("步骤 1：重新启动起点读书")
        if (!bridge.restartTargetApp()) {
            return FlowExecutionResult(false, "无法启动起点读书")
        }

        val home = waitForTree(bridge, "起点首页", timeoutMillis = 12_000) { tree ->
            tree.packageName == AppConstants.QIDIAN_PACKAGE && tree.hasBottomTabs()
        } ?: return FlowExecutionResult(false, "未进入起点读书首页：未检测到底部 4 个 tab")

        AppLogStore.add("步骤 2：已检测到底部 tab，点击“我”")
        val myPage = tapMeTabWithRetry(home, bridge, executor)
            ?: return FlowExecutionResult(false, "连续点击“我”$MAX_CLICK_ATTEMPTS 次后仍未进入我的界面")

        AppLogStore.add("步骤 3：检查登录状态")
        if (myPage.isLoggedOutMyPage()) {
            bridge.launchAutomationApp()
            return FlowExecutionResult(false, "起点读书未登录：检测到“登录/注册”，请先登录后再运行")
        }

        AppLogStore.add("已进入我的界面，等待 ${MY_PAGE_READY_DELAY_MILLIS / 1_000} 秒后点击“福利中心”")
        delay(MY_PAGE_READY_DELAY_MILLIS)

        AppLogStore.add("步骤 4：点击“福利中心”")
        val welfareCenter = tapWelfareCenterWithRetry(myPage, bridge, executor)
            ?: return FlowExecutionResult(false, "连续点击“福利中心”$MAX_CLICK_ATTEMPTS 次后仍未进入福利中心界面")

        AppLogStore.add("步骤 5：OCR 已确认福利中心，命中：${welfareCenter.ocr.welfareCenterMarkerSummary()}")

        AppLogStore.add("步骤 6：上滑福利中心页面，仅执行一次")
        executor.execute(welfareCenter.upSwipeAction()).getOrThrow()
        delay(900)

        AppLogStore.add("步骤 7-13：开始按 OCR 处理广告奖励任务")
        var successfulTaskCount = 0
        val failedTasks = mutableListOf<String>()
        for (task in WELFARE_AD_TASKS) {
            val taskResult = completeWelfareAdTask(task, bridge, executor)
            if (taskResult.completed) {
                successfulTaskCount += 1
            } else {
                failedTasks += task.title
                AppLogStore.add("福利任务“${task.title}”处理失败：${taskResult.message}；继续处理下一个独立任务")
            }
        }

        return if (failedTasks.isEmpty()) {
            FlowExecutionResult(
                completed = true,
                message = "已完成当前配置的福利中心广告奖励任务"
            )
        } else {
            FlowExecutionResult(
                completed = false,
                message = "福利中心广告奖励任务已执行完毕，成功 $successfulTaskCount/${WELFARE_AD_TASKS.size}，失败：${failedTasks.joinToString("、")}"
            )
        }
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
        initialMyPage: UiTreeSnapshot,
        bridge: AccessibilityBridge,
        executor: ActionExecutor
    ): OcrScreen? {
        var myPage: UiTreeSnapshot? = initialMyPage
        for (attempt in 1..MAX_CLICK_ATTEMPTS) {
            val activeMyPage = myPage ?: waitForTree(bridge, "我的界面", timeoutMillis = SHORT_VERIFY_TIMEOUT_MILLIS) { tree ->
                tree.isMyPageCandidate()
            }
            val welfareNode = activeMyPage?.findNode(text = "福利中心", viewId = WELFARE_TITLE_ID)
            if (activeMyPage == null || welfareNode == null) {
                AppLogStore.add("第 $attempt/$MAX_CLICK_ATTEMPTS 次点击“福利中心”前未找到入口")
                delay(RETRY_DELAY_MILLIS)
                myPage = null
                continue
            }

            AppLogStore.add("点击“福利中心”（$attempt/$MAX_CLICK_ATTEMPTS）")
            executor.execute(AutomationAction.TapPoint(activeMyPage.clickPointFor(welfareNode))).getOrThrow()

            val welfareCenter = waitForWelfareCenter(bridge, timeoutMillis = WELFARE_VERIFY_TIMEOUT_MILLIS)
            if (welfareCenter != null) {
                return welfareCenter
            }

            if (attempt < MAX_CLICK_ATTEMPTS) {
                AppLogStore.add("点击“福利中心”后未进入福利中心界面，等待后重试")
                delay(RETRY_DELAY_MILLIS)
                myPage = waitForTree(bridge, "我的界面", timeoutMillis = SHORT_VERIFY_TIMEOUT_MILLIS) { tree ->
                    tree.isMyPageCandidate()
                }
            }
        }
        return null
    }

    private suspend fun completeWelfareAdTask(
        task: WelfareAdTask,
        bridge: AccessibilityBridge,
        executor: ActionExecutor
    ): FlowExecutionResult {
        AppLogStore.add("开始处理福利任务：“${task.title}”")

        var round = 0
        while (round < task.maxRounds) {
            val action = findTaskActionOnCurrentScreen(task, bridge)
                ?: return FlowExecutionResult(false, "OCR 未找到“${task.title}”后面的“去完成 / 已领取”状态")

            when {
                action.actionText in TASK_DONE_TEXTS -> {
                    AppLogStore.add("福利任务“${task.title}”已领取")
                    return FlowExecutionResult(true, "福利任务“${task.title}”已领取")
                }

                action.actionText in GO_COMPLETE_TEXTS -> {
                    round += 1
                    val adResult = completeAdRewardRound(task, round, action, bridge, executor)
                    if (!adResult.completed) {
                        return adResult
                    }
                    delay(1_200)
                }

                else -> return FlowExecutionResult(false, "福利任务“${task.title}”状态不可识别：${action.actionText}")
            }
        }

        return FlowExecutionResult(false, "福利任务“${task.title}”已执行 ${task.maxRounds} 轮，仍未显示“已领取”")
    }

    private suspend fun findTaskActionOnCurrentScreen(
        task: WelfareAdTask,
        bridge: AccessibilityBridge
    ): OcrActionTextMatch? {
        repeat(TASK_STATUS_POLL_ATTEMPTS) { index ->
            val screen = captureOcrScreen(bridge).getOrNull()
            val action = screen?.ocr?.findActionAfterAnyText(task.matchTexts, TASK_ACTION_TEXTS)
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
        AppLogStore.add("步骤 8：点击“去完成”并验证广告界面")
        val initialCloseMatch = tapGoCompleteAndWaitForAd(task, round, initialAction, bridge, executor)
            ?: return FlowExecutionResult(false, "“${task.title}”第 $round 轮未识别到广告页右上角关闭按钮")

        AppLogStore.add("步骤 9：关闭广告并等待“点击去浏览 / 放弃奖励”弹窗")
        val browseDialog = openBrowseDialogWithRetry(task, round, initialCloseMatch, bridge, executor)
            ?: return FlowExecutionResult(false, "“${task.title}”第 $round 轮未识别到“点击去浏览”弹窗")

        val browsePoint = browseDialog.findBrowseActionPoint()
            ?: return FlowExecutionResult(false, "“${task.title}”第 $round 轮未定位到“点击去浏览”坐标")
        executor.execute(AutomationAction.TapPoint(browsePoint)).getOrThrow()
        AppLogStore.add("已点击“点击去浏览”")

        AppLogStore.add("步骤 10：等待 18 秒后查询广告奖励完成文字")
        delay(18_000)
        val rewardCompleted = waitForOcr(bridge, "恭喜已获得奖励", timeoutMillis = 15_000) { ocr ->
            ocr.hasAnyText(REWARD_GRANTED_TEXTS)
        } ?: return FlowExecutionResult(false, "“${task.title}”第 $round 轮等待后未识别到“恭喜已获得奖励”")
        AppLogStore.add("广告奖励完成提示已出现，OCR 耗时 ${rewardCompleted.ocr.elapsedMillis} ms")

        executor.execute(AutomationAction.Back).getOrThrow()
        AppLogStore.add("已返回广告界面，准备再次关闭广告")
        delay(1_000)
        tapCloseButtonOrFail(bridge, executor, "广告奖励完成后")
            ?: return FlowExecutionResult(false, "“${task.title}”第 $round 轮返回后未识别到广告关闭按钮")

        AppLogStore.add("步骤 11：检查奖励确认弹窗")
        val rewardDialog = waitForOcr(bridge, "奖励确认弹窗", timeoutMillis = 8_000) { ocr ->
            ocr.hasText(KNOW_TEXT) && ocr.hasAnyText(REWARD_DIALOG_TEXTS)
        }
        if (rewardDialog != null) {
            val knowPoint = rewardDialog.ocr.findAnyTextCenter(listOf(KNOW_TEXT))
                ?: return FlowExecutionResult(false, "识别到奖励弹窗，但未定位到“知道了”按钮")
            executor.execute(AutomationAction.TapPoint(knowPoint)).getOrThrow()
            AppLogStore.add("已点击“知道了”")
            delay(900)
        } else {
            AppLogStore.add("未检测到“知道了 / 恭喜获得”奖励弹窗，继续复查任务状态")
        }

        return FlowExecutionResult(true, "“${task.title}”第 $round 轮广告奖励已处理")
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
        for (attempt in 1..MAX_CLICK_ATTEMPTS) {
            AppLogStore.add("福利任务“${task.title}”：第 $round 轮点击“去完成”（$attempt/$MAX_CLICK_ATTEMPTS）")
            executor.execute(AutomationAction.TapPoint(action.point)).getOrThrow()

            val closeMatch = waitForCloseButton(bridge, timeoutMillis = AD_ENTRY_VERIFY_TIMEOUT_MILLIS)
            if (closeMatch != null) {
                AppLogStore.add("已通过右上角关闭按钮确认进入广告界面")
                return closeMatch
            }

            if (attempt < MAX_CLICK_ATTEMPTS) {
                AppLogStore.add("点击“去完成”后未检测到广告关闭按钮，等待后重试")
                delay(RETRY_DELAY_MILLIS)
                val refreshedAction = findTaskActionOnCurrentScreen(task, bridge)
                if (refreshedAction != null && refreshedAction.actionText in GO_COMPLETE_TEXTS) {
                    action = refreshedAction
                } else {
                    AppLogStore.add("未重新定位到“去完成”，停止使用旧坐标重试")
                    return null
                }
            }
        }
        return null
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

    companion object {
        private const val TAB_TITLE_ID = "com.qidian.QDReader:id/view_tab_title_title"
        private const val LOGIN_HINT_ID = "com.qidian.QDReader:id/tvLoginHint"
        private const val NEW_USER_TAG_ID = "com.qidian.QDReader:id/newUserTag"
        private const val WELFARE_TITLE_ID = "com.qidian.QDReader:id/tvTitle"
        private const val INCENTIVE_TASK_TEXT = "激励任务"
        private const val THREE_AD_TASK_TEXT = "完成3个广告任务得奖励"
        private const val ONE_AD_TASK_TEXT = "完成1个广告任务得奖励"
        private const val GO_COMPLETE_TEXT = "去完成"
        private const val CLAIMED_TEXT = "已领取"
        private const val COMPLETED_TEXT = "已完成"
        private const val KNOW_TEXT = "知道了"
        private const val MAX_CLICK_ATTEMPTS = 3
        private const val SHORT_VERIFY_TIMEOUT_MILLIS = 2_000L
        private const val NAVIGATION_VERIFY_TIMEOUT_MILLIS = 4_000L
        private const val WELFARE_VERIFY_TIMEOUT_MILLIS = 8_000L
        private const val AD_ENTRY_VERIFY_TIMEOUT_MILLIS = 6_000L
        private const val BROWSE_DIALOG_VERIFY_TIMEOUT_MILLIS = 5_000L
        private const val MY_PAGE_READY_DELAY_MILLIS = 2_000L
        private const val RETRY_DELAY_MILLIS = 1_000L

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
        private val REWARD_GRANTED_TEXTS = listOf("恭喜已获得奖励", "恭喜获得奖励", "恭喜获得")
        private val REWARD_DIALOG_TEXTS = listOf("恭喜获得", "恭喜已获得奖励", "恭喜获得奖励")
        private val WELFARE_AD_TASKS = listOf(
            WelfareAdTask(
                title = INCENTIVE_TASK_TEXT,
                matchTexts = listOf(INCENTIVE_TASK_TEXT, "激励", "完成广告任务", "多重好礼"),
                maxRounds = 5
            ),
            WelfareAdTask(
                title = THREE_AD_TASK_TEXT,
                matchTexts = listOf(THREE_AD_TASK_TEXT, "完成3个广告", "再完成3次", "10点章节卡"),
                maxRounds = 5
            ),
            WelfareAdTask(
                title = ONE_AD_TASK_TEXT,
                matchTexts = listOf(ONE_AD_TASK_TEXT, "完成1个广告", "满10点", "3点订阅券"),
                maxRounds = 3
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
    val maxRounds: Int
)

private fun OcrResult.logPreview(): String {
    val lines = blocks
        .map { block -> block.text.trim() }
        .filter { text -> text.isNotEmpty() }
        .take(16)
    val preview = if (lines.isNotEmpty()) lines.joinToString(" | ") else rawText.replace('\n', '|')
    return preview.take(220)
}
