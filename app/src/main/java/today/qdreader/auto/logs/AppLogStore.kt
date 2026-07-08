package today.qdreader.auto.logs

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AppLogEntry(
    val timestamp: String,
    val message: String
)

object AppLogStore {
    private const val MAX_ENTRIES = 80
    private val clockFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val _entries = MutableStateFlow<List<AppLogEntry>>(emptyList())

    val entries: StateFlow<List<AppLogEntry>> = _entries

    fun add(message: String) {
        val entry = AppLogEntry(clockFormat.format(Date()), message)
        _entries.update { current -> (listOf(entry) + current).take(MAX_ENTRIES) }
    }

    fun clear() {
        _entries.value = emptyList()
    }
}
