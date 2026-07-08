package today.qdreader.auto.schedule

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import today.qdreader.auto.automation.AutomationController
import today.qdreader.auto.core.AutomationTrigger
import today.qdreader.auto.notifications.AppNotifier

class CheckInWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val runResult = AutomationController(applicationContext).run(AutomationTrigger.Scheduled)
        AppNotifier.showStatus(
            applicationContext,
            if (runResult.success) "起点自动签到定时任务" else "起点自动签到定时任务未完成",
            runResult.message
        )
        SchedulePlanner.reschedule(applicationContext)
        return Result.success()
    }
}
