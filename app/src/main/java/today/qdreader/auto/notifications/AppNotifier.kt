package today.qdreader.auto.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import today.qdreader.auto.MainActivity
import today.qdreader.auto.R
import today.qdreader.auto.core.AppConstants

object AppNotifier {
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            AppConstants.NOTIFICATION_CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "起点自动签到运行状态"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun showStatus(context: Context, title: String, body: String) {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, AppConstants.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .build()

        runCatching {
            NotificationManagerCompat.from(context)
                .notify(AppConstants.NOTIFICATION_STATUS_ID, notification)
        }
    }

    fun runningForegroundInfo(context: Context, body: String): ForegroundInfo {
        ensureRunningChannel(context)
        val notification = NotificationCompat.Builder(context, AppConstants.RUNNING_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("起点自动签到正在运行")
            .setContentText(body)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(
                AppConstants.RUNNING_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            ForegroundInfo(AppConstants.RUNNING_NOTIFICATION_ID, notification)
        }
    }

    fun keepAliveNotification(context: Context, body: String): Notification {
        ensureKeepAliveChannel(context)
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, AppConstants.KEEP_ALIVE_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("起点自动签到")
            .setContentText(body)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    fun updateKeepAliveNotification(context: Context, body: String) {
        context.getSystemService(NotificationManager::class.java).notify(
            AppConstants.KEEP_ALIVE_NOTIFICATION_ID,
            keepAliveNotification(context, body)
        )
    }

    private fun ensureRunningChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            AppConstants.RUNNING_NOTIFICATION_CHANNEL_ID,
            "自动任务运行中",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "定时自动任务运行期间保持后台执行"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun ensureKeepAliveChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            AppConstants.KEEP_ALIVE_NOTIFICATION_CHANNEL_ID,
            "自动签到常驻服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "保持自动签到服务运行，并接收手动或定时任务"
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
