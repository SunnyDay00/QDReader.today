package today.qdreader.auto.automation

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import today.qdreader.auto.accessibility.QidianAccessibilityService
import today.qdreader.auto.core.AppConstants
import today.qdreader.auto.core.AutomationTrigger
import today.qdreader.auto.logs.AppLogStore
import today.qdreader.auto.notifications.AppNotifier

class AutomationForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var runJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground("服务常驻中，等待手动或定时任务")
        AppLogStore.add("自动签到前台服务已启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RUN_AUTOMATION -> startAutomation(
                trigger = intent.getStringExtra(EXTRA_TRIGGER)
                    ?.let { runCatching { AutomationTrigger.valueOf(it) }.getOrNull() }
                    ?: AutomationTrigger.Manual,
                maxRestartCount = intent.getIntExtra(EXTRA_MAX_RESTART_COUNT, -1)
            )
            ACTION_KEEP_ALIVE, null -> {
                AppNotifier.updateKeepAliveNotification(
                    this,
                    if (AutomationRunState.isRunning.value) "自动任务运行中" else "服务常驻中，等待手动或定时任务"
                )
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        runJob = null
        AppLogStore.add("自动签到前台服务已停止")
        super.onDestroy()
    }

    private fun startAutomation(trigger: AutomationTrigger, maxRestartCount: Int) {
        if (runJob?.isActive == true || AutomationRunState.isRunning.value) {
            AppLogStore.add("已有自动任务正在运行，忽略重复的 ${trigger.name} 请求")
            return
        }

        runJob = serviceScope.launch {
            AutomationRunState.markStarting()
            AppNotifier.updateKeepAliveNotification(this@AutomationForegroundService, "自动任务运行中")
            try {
                waitForAccessibilityService()
                val controller = AutomationController(applicationContext)
                val result = if (maxRestartCount >= 0) {
                    controller.run(trigger, maxRestartCount)
                } else {
                    controller.run(trigger)
                }
                AutomationRunState.markFinished(result.message)
                AppNotifier.showStatus(
                    this@AutomationForegroundService,
                    if (result.success) "起点自动签到已完成" else "起点自动签到未完成",
                    result.message
                )
            } catch (_: CancellationException) {
                AutomationRunState.markStopped()
                AppLogStore.add("自动任务已由用户停止，不再继续重启")
            } finally {
                AppNotifier.updateKeepAliveNotification(
                    this@AutomationForegroundService,
                    "服务常驻中，等待手动或定时任务"
                )
                runJob = null
            }
        }
    }

    private suspend fun waitForAccessibilityService() {
        if (QidianAccessibilityService.instance != null) return

        AppLogStore.add("等待无障碍服务连接后再启动自动任务")
        AppNotifier.updateKeepAliveNotification(this, "等待无障碍服务连接")
        repeat(ACCESSIBILITY_CONNECT_WAIT_ATTEMPTS) {
            if (QidianAccessibilityService.instance != null) {
                AppLogStore.add("无障碍服务已连接，开始自动任务")
                return
            }
            delay(ACCESSIBILITY_CONNECT_POLL_MILLIS)
        }
        AppLogStore.add("等待无障碍服务连接超时，交给自动任务输出具体状态")
    }

    private fun startAsForeground(body: String) {
        val notification = AppNotifier.keepAliveNotification(this, body)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                AppConstants.KEEP_ALIVE_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(AppConstants.KEEP_ALIVE_NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val ACTION_KEEP_ALIVE = "today.qdreader.auto.action.KEEP_ALIVE"
        private const val ACTION_RUN_AUTOMATION = "today.qdreader.auto.action.RUN_AUTOMATION"
        private const val EXTRA_TRIGGER = "automation_trigger"
        private const val EXTRA_MAX_RESTART_COUNT = "max_restart_count"
        private const val ACCESSIBILITY_CONNECT_WAIT_ATTEMPTS = 40
        private const val ACCESSIBILITY_CONNECT_POLL_MILLIS = 250L

        fun keepAlive(context: Context): Boolean = startService(
            context,
            Intent(context, AutomationForegroundService::class.java)
                .setAction(ACTION_KEEP_ALIVE)
        )

        fun requestRun(
            context: Context,
            trigger: AutomationTrigger,
            maxRestartCount: Int = -1
        ): Boolean = startService(
            context,
            Intent(context, AutomationForegroundService::class.java)
                .setAction(ACTION_RUN_AUTOMATION)
                .putExtra(EXTRA_TRIGGER, trigger.name)
                .putExtra(EXTRA_MAX_RESTART_COUNT, maxRestartCount)
        )

        private fun startService(context: Context, intent: Intent): Boolean = runCatching {
            ContextCompat.startForegroundService(context, intent)
            true
        }.getOrDefault(false)
    }
}
