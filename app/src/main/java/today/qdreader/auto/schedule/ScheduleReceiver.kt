package today.qdreader.auto.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import today.qdreader.auto.core.AppConstants
import today.qdreader.auto.logs.AppLogStore
import today.qdreader.auto.automation.AutomationForegroundService

class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        AutomationForegroundService.keepAlive(context.applicationContext)
        if (intent?.action == AppConstants.DAILY_ALARM_ACTION) {
            SchedulePlanner.triggerFromLegacyAlarm(context.applicationContext)
            return
        }

        AppLogStore.add("系统时间或设备状态变化，重新安排每日自动任务")
        SchedulePlanner.reschedule(context.applicationContext)
    }
}
