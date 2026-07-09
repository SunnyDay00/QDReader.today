package today.qdreader.auto.schedule

import android.content.Context

data class ScheduleConfig(
    val enabled: Boolean,
    val hour: Int,
    val minute: Int,
    val maxRestartCount: Int
) {
    fun label(): String = "%02d:%02d".format(hour, minute)
}

class ScheduleRepository(context: Context) {
    private val prefs = context.getSharedPreferences("schedule", Context.MODE_PRIVATE)

    fun load(): ScheduleConfig {
        return ScheduleConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            hour = prefs.getInt(KEY_HOUR, 9).coerceIn(0, 23),
            minute = prefs.getInt(KEY_MINUTE, 0).coerceIn(0, 59),
            maxRestartCount = prefs.getInt(KEY_MAX_RESTART_COUNT, DEFAULT_MAX_RESTART_COUNT)
                .coerceIn(0, MAX_RESTART_COUNT)
        )
    }

    fun save(config: ScheduleConfig) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putInt(KEY_HOUR, config.hour.coerceIn(0, 23))
            .putInt(KEY_MINUTE, config.minute.coerceIn(0, 59))
            .putInt(KEY_MAX_RESTART_COUNT, config.maxRestartCount.coerceIn(0, MAX_RESTART_COUNT))
            .apply()
    }

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_HOUR = "hour"
        private const val KEY_MINUTE = "minute"
        private const val KEY_MAX_RESTART_COUNT = "max_restart_count"
        const val DEFAULT_MAX_RESTART_COUNT = 3
        const val MAX_RESTART_COUNT = 10
    }
}
