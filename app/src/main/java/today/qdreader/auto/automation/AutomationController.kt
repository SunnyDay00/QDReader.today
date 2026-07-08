package today.qdreader.auto.automation

import android.content.Context
import today.qdreader.auto.accessibility.AccessibilityBridgeImpl
import today.qdreader.auto.core.AutomationTrigger
import today.qdreader.auto.core.DeviceStatus
import today.qdreader.auto.logs.AppLogStore

data class AutomationRunResult(
    val success: Boolean,
    val message: String
)

class AutomationController(
    private val context: Context,
    private val flow: CheckInFlow = PlaceholderCheckInFlow()
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
        val step = flow.detectCurrentStep(null)
        val action = flow.nextAction(step)
        val result = flow.executeAction(action, executor)
        AppLogStore.add(result.message)
        return AutomationRunResult(success = true, message = result.message)
    }

    private fun fail(message: String): AutomationRunResult {
        AppLogStore.add("自动化未执行：$message")
        return AutomationRunResult(success = false, message = message)
    }
}
