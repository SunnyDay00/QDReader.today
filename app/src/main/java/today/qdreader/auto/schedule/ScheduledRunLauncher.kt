package today.qdreader.auto.schedule

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import today.qdreader.auto.MainActivity
import today.qdreader.auto.core.AppConstants

object ScheduledRunLauncher {
    fun alarmPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ScheduleReceiver::class.java)
            .setAction(AppConstants.SCHEDULED_ALARM_ACTION)
        return PendingIntent.getBroadcast(
            context,
            AppConstants.SCHEDULED_ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun legacyActivityAlarmPendingIntent(context: Context): PendingIntent? {
        return PendingIntent.getActivity(
            context,
            AppConstants.DAILY_ALARM_REQUEST_CODE,
            scheduledRunIntent(context, SOURCE_ALARM),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun openActivity(context: Context, source: String): Boolean = runCatching {
        val pendingIntent = activityPendingIntent(
            context = context,
            source = source,
            requestCode = AppConstants.SCHEDULED_ACTIVITY_REQUEST_CODE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            pendingIntent.send(
                context,
                0,
                null,
                null,
                null,
                null,
                senderActivityOptions()
            )
        } else {
            pendingIntent.send()
        }
    }.isSuccess

    private fun activityPendingIntent(
        context: Context,
        source: String,
        requestCode: Int
    ): PendingIntent {
        return PendingIntent.getActivity(
            context,
            requestCode,
            scheduledRunIntent(context, source),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            creatorActivityOptions()
        )
    }

    private fun scheduledRunIntent(context: Context, source: String): Intent {
        return Intent(context, MainActivity::class.java)
            .setAction(AppConstants.SCHEDULED_RUN_ACTION)
            .putExtra(AppConstants.SCHEDULED_RUN_SOURCE_EXTRA, source)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
    }

    private fun senderActivityOptions(): Bundle {
        return ActivityOptions.makeBasic().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                setPendingIntentBackgroundActivityStartMode(
                    backgroundActivityStartMode()
                )
            }
        }.toBundle()
    }

    private fun creatorActivityOptions(): Bundle? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        return ActivityOptions.makeBasic().apply {
            setPendingIntentCreatorBackgroundActivityStartMode(
                backgroundActivityStartMode()
            )
        }.toBundle()
    }

    @Suppress("DEPRECATION")
    private fun backgroundActivityStartMode(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
        } else {
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
        }
    }

    const val SOURCE_ALARM = "alarm"
    const val SOURCE_WORK_FALLBACK = "work_fallback"
    const val SOURCE_LEGACY_ALARM = "legacy_alarm"
}
