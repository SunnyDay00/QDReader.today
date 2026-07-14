package today.qdreader.auto.core

object AppConstants {
    const val QIDIAN_PACKAGE = "com.qidian.QDReader"
    const val NOTIFICATION_CHANNEL_ID = "automation_status"
    const val NOTIFICATION_STATUS_ID = 1001
    const val RUNNING_NOTIFICATION_CHANNEL_ID = "automation_running"
    const val RUNNING_NOTIFICATION_ID = 1002
    const val KEEP_ALIVE_NOTIFICATION_CHANNEL_ID = "automation_keep_alive"
    const val KEEP_ALIVE_NOTIFICATION_ID = 1003
    const val UNIQUE_DAILY_WORK = "daily_qidian_checkin"
    const val DAILY_ALARM_ACTION = "today.qdreader.auto.action.DAILY_ALARM"
    const val DAILY_ALARM_REQUEST_CODE = 2001
}

enum class AutomationTrigger {
    Manual,
    Scheduled
}
