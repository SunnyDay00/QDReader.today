package today.qdreader.auto.automation

import android.content.Context
import today.qdreader.auto.accessibility.AccessibilityBridgeImpl
import today.qdreader.auto.core.AutomationTrigger
import today.qdreader.auto.core.DeviceStatus
import today.qdreader.auto.logs.AppLogStore
import today.qdreader.auto.vision.MlKitChineseOcrEngine

data class AutomationRunResult(
    val success: Boolean,
    val message: String
)

class AutomationController(
    private val context: Context,
    private val flowFactory: () -> CheckInFlow = { QidianPartialCheckInFlow(MlKitChineseOcrEngine()) }
) {
    suspend fun run(trigger: AutomationTrigger): AutomationRunResult {
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
        val flow = flowFactory()
        return try {
            val result = flow.run(bridge, executor)
            AppLogStore.add(result.message)
            AutomationRunResult(success = result.completed, message = result.message)
        } finally {
            (flow as? AutoCloseable)?.close()
        }
    }

    private fun fail(message: String): AutomationRunResult {
        AppLogStore.add("自动化未执行：$message")
        return AutomationRunResult(success = false, message = message)
    }
}
