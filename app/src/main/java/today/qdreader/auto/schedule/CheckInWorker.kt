package today.qdreader.auto.schedule

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import today.qdreader.auto.automation.AutomationController
import today.qdreader.auto.core.AutomationTrigger
import today.qdreader.auto.notifications.AppNotifier
import today.qdreader.auto.logs.AppLogStore

class CheckInWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val source = inputData.getString(INPUT_SOURCE) ?: "unknown"
        setForeground(AppNotifier.runningForegroundInfo(applicationContext, "触发来源：$source"))
        AppLogStore.add("定时 Worker 已启动，来源：$source")
        val runResult = AutomationController(applicationContext).run(AutomationTrigger.Scheduled)
        AppNotifier.showStatus(
            applicationContext,
            if (runResult.success) "起点自动签到定时任务" else "起点自动签到定时任务未完成",
            runResult.message
        )
        if (!isStopped) {
            SchedulePlanner.scheduleNextAfterRun(applicationContext)
        }
        return Result.success()
    }

    companion object {
        const val INPUT_SOURCE = "schedule_source"
    }
}
