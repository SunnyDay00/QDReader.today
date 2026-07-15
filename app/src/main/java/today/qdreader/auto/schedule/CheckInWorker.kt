package today.qdreader.auto.schedule

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
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
        val activityOpened = ScheduledRunLauncher.openActivity(applicationContext, source)
        if (!activityOpened) {
            AppLogStore.add("定时任务无法打开自动签到界面")
            AppNotifier.showStatus(
                applicationContext,
                "起点自动签到定时任务未启动",
                "无法打开自动签到界面"
            )
        }
        if (!isStopped) {
            SchedulePlanner.scheduleNextAfterRun(applicationContext)
        }
        return if (activityOpened) Result.success() else Result.retry()
    }

    companion object {
        const val INPUT_SOURCE = "schedule_source"
    }
}
