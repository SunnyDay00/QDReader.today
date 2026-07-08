package today.qdreader.auto.schedule

import android.content.Context

data class ScheduleConfig(
    val enabled: Boolean,
    val hour: Int,
    val minute: Int
) {
    fun label(): String = "%02d:%02d".format(hour, minute)
}

class ScheduleRepository(context: Context) {
    private val prefs = context.getSharedPreferences("schedule", Context.MODE_PRIVATE)

    fun load(): ScheduleConfig {
        return ScheduleConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            hour = prefs.getInt(KEY_HOUR, 9).coerceIn(0, 23),
            minute = prefs.getInt(KEY_MINUTE, 0).coerceIn(0, 59)
        )
    }

    fun save(config: ScheduleConfig) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putInt(KEY_HOUR, config.hour.coerceIn(0, 23))
            .putInt(KEY_MINUTE, config.minute.coerceIn(0, 59))
            .apply()
    }

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_HOUR = "hour"
        private const val KEY_MINUTE = "minute"
    }
}
