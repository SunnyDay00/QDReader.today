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
import today.qdreader.auto.vision.OcrEngine
import today.qdreader.auto.vision.OcrResult
import today.qdreader.auto.vision.findGoCompleteAfterIncentiveTask
import today.qdreader.auto.vision.hasText

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
    private val ocrEngine: OcrEngine
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

        AppLogStore.add("步骤 7：OCR 查找“激励任务”后的“去完成”按钮")
        val incentiveScreen = waitForOcr(bridge, "激励任务", timeoutMillis = 10_000) { ocr ->
            ocr.hasText(INCENTIVE_TASK_TEXT) && ocr.hasText(GO_COMPLETE_TEXT)
        } ?: return FlowExecutionResult(false, "OCR 未找到“激励任务”或“去完成”按钮")

        val goCompletePoint = incentiveScreen.ocr.findGoCompleteAfterIncentiveTask()
            ?: return FlowExecutionResult(false, "OCR 未定位到“激励任务”对应的“去完成”坐标")
        executor.execute(AutomationAction.TapPoint(goCompletePoint)).getOrThrow()
        AppLogStore.add("已通过 OCR 点击“激励任务”后的“去完成”按钮")

        return FlowExecutionResult(
            completed = true,
            message = "已点击激励任务“去完成”；后续任务页面处理步骤尚未实现"
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
        private const val GO_COMPLETE_TEXT = "去完成"

        private val BOTTOM_TABS = listOf("书架", "精选", "发现", "我")
        private val WELFARE_CENTER_MARKERS = listOf("本周收益", "积分商城", "完成任务得奖励")
    }
}

private data class OcrScreen(
    val ocr: OcrResult,
    val width: Int,
    val height: Int
)
