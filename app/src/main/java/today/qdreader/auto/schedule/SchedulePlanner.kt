package today.qdreader.auto.schedule

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import today.qdreader.auto.core.AppConstants
import today.qdreader.auto.logs.AppLogStore
import java.util.Calendar
import java.util.concurrent.TimeUnit

object SchedulePlanner {
    fun reschedule(context: Context) {
        val repository = ScheduleRepository(context)
        val config = repository.load()
        val workManager = WorkManager.getInstance(context)

        if (!config.enabled) {
            workManager.cancelUniqueWork(AppConstants.UNIQUE_DAILY_WORK)
            AppLogStore.add("每日自动任务已关闭")
            return
        }

        val delayMillis = nextDelayMillis(config.hour, config.minute)
        val request = OneTimeWorkRequestBuilder<CheckInWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .build()

        workManager.enqueueUniqueWork(
            AppConstants.UNIQUE_DAILY_WORK,
            ExistingWorkPolicy.REPLACE,
            request
        )
        AppLogStore.add("已安排每日自动任务：${config.label()}")
    }

    fun nextDelayMillis(hour: Int, minute: Int, now: Calendar = Calendar.getInstance()): Long {
        val next = now.clone() as Calendar
        next.set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
        next.set(Calendar.MINUTE, minute.coerceIn(0, 59))
        next.set(Calendar.SECOND, 0)
        next.set(Calendar.MILLISECOND, 0)
        if (!next.after(now)) {
            next.add(Calendar.DAY_OF_YEAR, 1)
        }
        return next.timeInMillis - now.timeInMillis
    }
}
