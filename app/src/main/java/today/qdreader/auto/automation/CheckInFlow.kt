package today.qdreader.auto.automation

import android.graphics.Bitmap
import kotlinx.coroutines.delay
import today.qdreader.auto.accessibility.ScreenPoint
import today.qdreader.auto.accessibility.AccessibilityBridge
import today.qdreader.auto.accessibility.UiTreeSnapshot
import today.qdreader.auto.accessibility.clickPointFor
import today.qdreader.auto.accessibility.findNode
import today.qdreader.auto.accessibility.hasAllTexts
import today.qdreader.auto.accessibility.hasNode
import today.qdreader.auto.core.AppConstants
import today.qdreader.auto.logs.AppLogStore
import today.qdreader.auto.vision.CloseButtonDetector
import today.qdreader.auto.vision.CloseButtonMatch
import today.qdreader.auto.vision.OcrActionTextMatch
import today.qdreader.auto.vision.OcrEngine
import today.qdreader.auto.vision.OcrResult
import today.qdreader.auto.vision.findActionAfterText
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
        val meNode = home.findNode(text = "我", viewId = TAB_TITLE_ID)
            ?: return FlowExecutionResult(false, "未找到“我”tab")
        executor.execute(AutomationAction.TapPoint(home.clickPointFor(meNode))).getOrThrow()

        val myPage = waitForTree(bridge, "我的界面", timeoutMillis = 8_000) { tree ->
            tree.packageName == AppConstants.QIDIAN_PACKAGE &&
                (tree.isLoggedOutMyPage() || tree.findNode(text = "福利中心", viewId = WELFARE_TITLE_ID) != null)
        } ?: return FlowExecutionResult(false, "点击“我”后未进入我的界面")

        AppLogStore.add("步骤 3：检查登录状态")
        if (myPage.isLoggedOutMyPage()) {
            bridge.launchAutomationApp()
            return FlowExecutionResult(false, "起点读书未登录：检测到“登录/注册”，请先登录后再运行")
        }

        AppLogStore.add("步骤 4：点击“福利中心”")
        val welfareNode = myPage.findNode(text = "福利中心", viewId = WELFARE_TITLE_ID)
            ?: return FlowExecutionResult(false, "已登录，但未找到“福利中心”入口")
        executor.execute(AutomationAction.TapPoint(myPage.clickPointFor(welfareNode))).getOrThrow()

        val welfareCenter = waitForOcr(bridge, "福利中心", timeoutMillis = 15_000) { ocr ->
            ocr.hasWelfareCenterMarkers()
        } ?: return FlowExecutionResult(false, "OCR 未确认进入福利中心：未同时识别到“本周收益 / 积分商城 / 完成任务得奖励”")

        val markerCount = WELFARE_CENTER_MARKERS.count { marker ->
            welfareCenter.ocr.hasText(marker)
        }
        AppLogStore.add("步骤 5：OCR 已确认福利中心，命中 $markerCount 个验证文本")

        AppLogStore.add("步骤 6：上滑福利中心页面")
        executor.execute(welfareCenter.upSwipeAction()).getOrThrow()
        delay(900)

        AppLogStore.add("步骤 7-13：开始按 OCR 处理广告奖励任务")
        for (task in WELFARE_AD_TASKS) {
            val taskResult = completeWelfareAdTask(task, bridge, executor)
            if (!taskResult.completed) {
                return taskResult
            }
        }

        return FlowExecutionResult(
            completed = true,
            message = "已完成当前配置的福利中心广告奖励任务"
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

    private suspend fun completeWelfareAdTask(
        task: WelfareAdTask,
        bridge: AccessibilityBridge,
        executor: ActionExecutor
    ): FlowExecutionResult {
        AppLogStore.add("开始处理福利任务：“${task.title}”")

        var round = 0
        while (round < task.maxRounds) {
            val action = findTaskActionWithScroll(task, bridge, executor)
                ?: return FlowExecutionResult(false, "OCR 未找到“${task.title}”后面的“去完成 / 已完成”状态")

            when (action.actionText) {
                COMPLETED_TEXT -> {
                    AppLogStore.add("福利任务“${task.title}”已完成")
                    return FlowExecutionResult(true, "福利任务“${task.title}”已完成")
                }

                GO_COMPLETE_TEXT -> {
                    round += 1
                    AppLogStore.add("福利任务“${task.title}”：第 $round 轮点击“去完成”")
                    executor.execute(AutomationAction.TapPoint(action.point)).getOrThrow()
                    val adResult = completeAdRewardRound(task, round, bridge, executor)
                    if (!adResult.completed) {
                        return adResult
                    }
                    delay(1_200)
                }

                else -> return FlowExecutionResult(false, "福利任务“${task.title}”状态不可识别：${action.actionText}")
            }
        }

        return FlowExecutionResult(false, "福利任务“${task.title}”已执行 ${task.maxRounds} 轮，仍未显示“已完成”")
    }

    private suspend fun findTaskActionWithScroll(
        task: WelfareAdTask,
        bridge: AccessibilityBridge,
        executor: ActionExecutor
    ): OcrActionTextMatch? {
        repeat(TASK_SEARCH_ATTEMPTS) { index ->
            val screen = captureOcrScreen(bridge).getOrNull()
            val action = screen?.ocr?.findActionAfterText(task.title, TASK_ACTION_TEXTS)
            if (action != null) {
                AppLogStore.add("OCR 已定位“${task.title}”状态：${action.actionText}")
                return action
            }

            if (screen != null && index < TASK_SEARCH_ATTEMPTS - 1) {
                AppLogStore.add("当前屏未找到“${task.title}”，继续上滑查找")
                executor.execute(screen.upSwipeAction()).getOrThrow()
                delay(850)
            } else {
                delay(600)
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
        AppLogStore.add("步骤 8：等待广告页加载并寻找右上角关闭按钮")
        delay(2_000)
        tapCloseButtonOrFail(bridge, executor, "广告奖励选择前")
            ?: return FlowExecutionResult(false, "“${task.title}”第 $round 轮未识别到广告页右上角关闭按钮")

        AppLogStore.add("步骤 9：等待“点击去浏览 / 放弃奖励”弹窗")
        val browseDialog = waitForOcr(bridge, "点击去浏览弹窗", timeoutMillis = 10_000) { ocr ->
            ocr.hasAnyText(CLICK_TO_BROWSE_TEXTS)
        } ?: return FlowExecutionResult(false, "“${task.title}”第 $round 轮未识别到“点击去浏览”弹窗")

        val browsePoint = browseDialog.ocr.findAnyTextCenter(CLICK_TO_BROWSE_TEXTS)
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

    private suspend fun tapCloseButtonOrFail(
        bridge: AccessibilityBridge,
        executor: ActionExecutor,
        phase: String
    ): CloseButtonMatch? {
        val closeMatch = waitForCloseButton(bridge, timeoutMillis = 8_000) ?: return null
        executor.execute(AutomationAction.TapPoint(closeMatch.point)).getOrThrow()
        AppLogStore.add(
            "已点击右上角关闭按钮（$phase）：${closeMatch.templateName}，分数 ${"%.3f".format(closeMatch.score)}"
        )
        delay(700)
        return closeMatch
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

    private fun UiTreeSnapshot.hasWelfareCenterMarkers(): Boolean {
        return hasAllTexts(WELFARE_CENTER_MARKERS)
    }

    private fun OcrResult.hasWelfareCenterMarkers(): Boolean {
        return WELFARE_CENTER_MARKERS.all { marker -> hasText(marker) }
    }

    private fun OcrScreen.upSwipeAction(): AutomationAction.SwipePoints {
        val centerX = width * 0.5f
        return AutomationAction.SwipePoints(
            start = ScreenPoint(centerX, height * 0.78f),
            end = ScreenPoint(centerX, height * 0.36f),
            durationMillis = 520
        )
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
        private const val COMPLETED_TEXT = "已完成"
        private const val KNOW_TEXT = "知道了"

        private val BOTTOM_TABS = listOf("书架", "精选", "发现", "我")
        private val WELFARE_CENTER_MARKERS = listOf("本周收益", "积分商城", "完成任务得奖励")
        private val TASK_ACTION_TEXTS = listOf(COMPLETED_TEXT, GO_COMPLETE_TEXT)
        private val CLICK_TO_BROWSE_TEXTS = listOf("点击去浏览", "去浏览")
        private val REWARD_GRANTED_TEXTS = listOf("恭喜已获得奖励", "恭喜获得奖励", "恭喜获得")
        private val REWARD_DIALOG_TEXTS = listOf("恭喜获得", "恭喜已获得奖励", "恭喜获得奖励")
        private val WELFARE_AD_TASKS = listOf(
            WelfareAdTask(INCENTIVE_TASK_TEXT, maxRounds = 5),
            WelfareAdTask(THREE_AD_TASK_TEXT, maxRounds = 5),
            WelfareAdTask(ONE_AD_TASK_TEXT, maxRounds = 3)
        )
        private const val TASK_SEARCH_ATTEMPTS = 4
    }
}

private data class OcrScreen(
    val ocr: OcrResult,
    val width: Int,
    val height: Int
)

private data class WelfareAdTask(
    val title: String,
    val maxRounds: Int
)
