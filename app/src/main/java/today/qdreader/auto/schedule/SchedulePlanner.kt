package today.qdreader.auto.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import today.qdreader.auto.core.AppConstants
import today.qdreader.auto.logs.AppLogStore
import java.util.Calendar
import java.util.concurrent.TimeUnit

object SchedulePlanner {
    fun reschedule(context: Context) {
        cancelLegacyAlarm(context)
        val repository = ScheduleRepository(context)
        val config = repository.load()
        val workManager = WorkManager.getInstance(context)

        if (!config.enabled) {
            cancelAlarm(context)
            workManager.cancelUniqueWork(AppConstants.UNIQUE_DAILY_WORK)
            AppLogStore.add("每日自动任务已关闭")
            return
        }

        val delayMillis = nextDelayMillis(config.hour, config.minute)
        scheduleAlarm(context, delayMillis)
        enqueueFallbackWork(
            context = context,
            delayMillis = delayMillis + WORK_FALLBACK_GRACE_MILLIS,
            policy = ExistingWorkPolicy.REPLACE
        )
        AppLogStore.add(
            "已安排每日自动任务：${config.label()}，" +
                if (canScheduleExactAlarms(context)) "精确定时" else "系统定时 + 15 分钟补偿"
        )
    }

    fun triggerFromLegacyAlarm(context: Context) {
        val config = ScheduleRepository(context).load()
        if (!config.enabled) {
            cancelAlarm(context)
            WorkManager.getInstance(context).cancelUniqueWork(AppConstants.UNIQUE_DAILY_WORK)
            return
        }

        reschedule(context)
        val activityOpened = ScheduledRunLauncher.openActivity(
            context,
            ScheduledRunLauncher.SOURCE_LEGACY_ALARM
        )
        AppLogStore.add(
            if (activityOpened) "旧版定时闹钟已打开自动签到界面"
            else "旧版定时闹钟无法打开自动签到界面"
        )
    }

    fun scheduleNextAfterRun(context: Context) {
        val config = ScheduleRepository(context).load()
        if (!config.enabled) return

        val delayMillis = nextDelayMillis(config.hour, config.minute)
        scheduleAlarm(context, delayMillis)
        enqueueFallbackWork(
            context = context,
            delayMillis = delayMillis + WORK_FALLBACK_GRACE_MILLIS,
            policy = ExistingWorkPolicy.APPEND_OR_REPLACE
        )
    }

    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }

    private fun enqueueFallbackWork(
        context: Context,
        delayMillis: Long,
        policy: ExistingWorkPolicy
    ) {
        val request = OneTimeWorkRequestBuilder<CheckInWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setInputData(workerData(ScheduledRunLauncher.SOURCE_WORK_FALLBACK))
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            AppConstants.UNIQUE_DAILY_WORK,
            policy,
            request
        )
    }

    private fun workerData(source: String): Data {
        return Data.Builder().putString(CheckInWorker.INPUT_SOURCE, source).build()
    }

    private fun scheduleAlarm(context: Context, delayMillis: Long) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val triggerAtMillis = System.currentTimeMillis() + delayMillis
        val pendingIntent = ScheduledRunLauncher.alarmPendingIntent(context)
        if (canScheduleExactAlarms(context)) {
            val exactScheduled = runCatching {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }.isSuccess
            if (exactScheduled) return
        }
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAtMillis,
            pendingIntent
        )
    }

    private fun cancelAlarm(context: Context) {
        context.getSystemService(AlarmManager::class.java)
            .cancel(ScheduledRunLauncher.alarmPendingIntent(context))
    }

    private fun cancelLegacyAlarm(context: Context) {
        val intent = Intent(context, ScheduleReceiver::class.java)
            .setAction(AppConstants.DAILY_ALARM_ACTION)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            AppConstants.DAILY_ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent)
        pendingIntent.cancel()
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

    private const val WORK_FALLBACK_GRACE_MILLIS = 15 * 60_000L
}
