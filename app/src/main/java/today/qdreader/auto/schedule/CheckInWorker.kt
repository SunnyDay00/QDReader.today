package today.qdreader.auto.schedule

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import today.qdreader.auto.automation.AutomationForegroundService
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
        val dispatched = AutomationForegroundService.requestRun(
            applicationContext,
            AutomationTrigger.Scheduled
        )
        if (!dispatched) {
            AppLogStore.add("定时任务提交到前台服务失败")
            AppNotifier.showStatus(
                applicationContext,
                "起点自动签到定时任务未启动",
                "无法连接自动签到前台服务"
            )
        }
        if (!isStopped) {
            SchedulePlanner.scheduleNextAfterRun(applicationContext)
        }
        return if (dispatched) Result.success() else Result.retry()
    }

    companion object {
        const val INPUT_SOURCE = "schedule_source"
    }
}
