package today.qdreader.auto.automation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import today.qdreader.auto.logs.AppLogStore

object AutomationRunState {
    private val lock = Any()
    private var activeJob: Job? = null
    private val _isRunning = MutableStateFlow(false)
    private val _latestMessage = MutableStateFlow("尚未运行自动任务。")

    val isRunning: StateFlow<Boolean> = _isRunning
    val latestMessage: StateFlow<String> = _latestMessage

    fun markStarting() {
        _latestMessage.value = "自动任务运行中..."
    }

    fun markFinished(message: String) {
        _latestMessage.value = message
    }

    fun markStopped() {
        _latestMessage.value = "自动任务已停止。"
    }

    fun register(job: Job): Boolean = synchronized(lock) {
        if (activeJob?.isActive == true) return@synchronized false
        activeJob = job
        _isRunning.value = true
        true
    }

    fun unregister(job: Job) = synchronized(lock) {
        if (activeJob === job) {
            activeJob = null
            _isRunning.value = false
        }
    }

    fun stop(): Boolean {
        val job = synchronized(lock) { activeJob?.takeIf { it.isActive } } ?: return false
        AppLogStore.add("用户请求停止自动任务")
        job.cancel(CancellationException("用户停止自动任务"))
        return true
    }
}
