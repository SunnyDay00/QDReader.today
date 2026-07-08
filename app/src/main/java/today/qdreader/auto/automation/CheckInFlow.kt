package today.qdreader.auto.automation

sealed interface CheckInStep {
    data object FrameworkOnly : CheckInStep
    data object Unsupported : CheckInStep
}

data class FlowExecutionResult(
    val completed: Boolean,
    val message: String
)

interface CheckInFlow {
    suspend fun detectCurrentStep(analysis: ScreenAnalysis?): CheckInStep
    suspend fun nextAction(step: CheckInStep): AutomationAction
    suspend fun executeAction(action: AutomationAction, executor: ActionExecutor): FlowExecutionResult
}

class PlaceholderCheckInFlow : CheckInFlow {
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
