package today.qdreader.auto.automation

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import today.qdreader.auto.accessibility.AccessibilityBridgeImpl
import today.qdreader.auto.core.AutomationTrigger
import today.qdreader.auto.core.DeviceStatus
import today.qdreader.auto.logs.AppLogStore
import today.qdreader.auto.schedule.ScheduleRepository
import today.qdreader.auto.vision.MlKitChineseOcrEngine
import today.qdreader.auto.vision.OpenCvAdCloseButtonDetector

data class AutomationRunResult(
    val success: Boolean,
    val message: String
)

class AutomationController(
    private val context: Context,
    private val flowFactory: () -> CheckInFlow = {
        QidianPartialCheckInFlow(
            ocrEngine = MlKitChineseOcrEngine(),
            closeButtonDetector = OpenCvAdCloseButtonDetector(context)
        )
    }
) {
    suspend fun run(
        trigger: AutomationTrigger,
        maxRestartCount: Int = ScheduleRepository(context).load().maxRestartCount
    ): AutomationRunResult {
        AppLogStore.add("自动化入口触发：${trigger.name}")

        if (!DeviceStatus.isAccessibilityEnabled(context)) {
            return fail("无障碍服务未启用")
        }
        if (!DeviceStatus.isTargetAppInstalled(context)) {
            return fail("未检测到起点读书 App")
        }

        val bridge = AccessibilityBridgeImpl(context)
        if (!bridge.isServiceConnected()) {
            return fail("无障碍服务尚未连接")
        }

        val executor = ActionExecutor(bridge)
        val restartLimit = maxRestartCount.coerceIn(0, MAX_RESTART_LIMIT)
        var restartCount = 0
        var lastMessage = ""

        while (true) {
            val attemptLabel = if (restartLimit > 0) "（重启 ${restartCount}/$restartLimit）" else ""
            AppLogStore.add("开始执行自动化流程$attemptLabel")
            val flow = flowFactory()
            val result = try {
                flow.run(bridge, executor)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                FlowExecutionResult(
                    completed = false,
                    message = "自动化流程异常：${exception.message ?: exception::class.java.simpleName}",
                    restartRequested = true
                )
            } finally {
                (flow as? AutoCloseable)?.close()
            }

            AppLogStore.add(result.message)
            lastMessage = result.message
            if (result.completed) {
                return AutomationRunResult(success = true, message = result.message)
            }

            if (!result.restartRequested) {
                bridge.launchAutomationApp()
                return AutomationRunResult(success = false, message = result.message)
            }

            if (restartCount >= restartLimit) {
                bridge.launchAutomationApp()
                val message = "自动化流程已重启 $restartCount/$restartLimit 次仍未完成：$lastMessage"
                AppLogStore.add(message)
                return AutomationRunResult(success = false, message = message)
            }

            restartCount += 1
            AppLogStore.add("当前步骤多次重试无结果，关闭并重启起点读书后重新开始完整任务（$restartCount/$restartLimit）")
            bridge.restartTargetApp()
            delay(RESTART_DELAY_MILLIS)
        }
    }

    private fun fail(message: String): AutomationRunResult {
        AppLogStore.add("自动化未执行：$message")
        return AutomationRunResult(success = false, message = message)
    }

    companion object {
        private const val MAX_RESTART_LIMIT = 10
        private const val RESTART_DELAY_MILLIS = 2_000L
    }
}
