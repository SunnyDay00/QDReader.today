package today.qdreader.auto.automation

import today.qdreader.auto.accessibility.AccessibilityBridge
import today.qdreader.auto.accessibility.ScreenPoint

sealed interface AutomationAction {
    data object NoOp : AutomationAction
    data class TapPoint(val point: ScreenPoint) : AutomationAction
    data object Back : AutomationAction
}

class ActionExecutor(
    private val bridge: AccessibilityBridge
) {
    suspend fun execute(action: AutomationAction): Result<Unit> {
        return when (action) {
            AutomationAction.NoOp -> Result.success(Unit)
            is AutomationAction.TapPoint -> bridge.tap(action.point)
            AutomationAction.Back -> {
                if (bridge.performBack()) Result.success(Unit)
                else Result.failure(IllegalStateException("返回动作执行失败"))
            }
        }
    }
}
