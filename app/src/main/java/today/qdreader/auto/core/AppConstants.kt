package today.qdreader.auto.core

object AppConstants {
    const val QIDIAN_PACKAGE = "com.qidian.QDReader"
    const val NOTIFICATION_CHANNEL_ID = "automation_status"
    const val NOTIFICATION_STATUS_ID = 1001
    const val UNIQUE_DAILY_WORK = "daily_qidian_checkin"
}

enum class AutomationTrigger {
    Manual,
    Scheduled
}
