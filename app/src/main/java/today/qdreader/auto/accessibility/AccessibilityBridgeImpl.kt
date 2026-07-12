package today.qdreader.auto.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.delay
import today.qdreader.auto.core.DeviceStatus
import today.qdreader.auto.core.AppConstants
import today.qdreader.auto.logs.AppLogStore

class AccessibilityBridgeImpl(
    private val context: Context
) : AccessibilityBridge {
    private val service: QidianAccessibilityService?
        get() = QidianAccessibilityService.instance

    override fun isServiceConnected(): Boolean = service != null

    override fun currentPackageName(): String? = service?.currentPackage()

    override fun readActiveWindow(): UiTreeSnapshot? = service?.activeWindowSnapshot()

    override suspend fun captureScreenshot(): Result<Bitmap> {
        val activeService = service
            ?: return Result.failure(IllegalStateException("无障碍服务未连接，无法截图"))
        return activeService.screenshotBitmap()
    }

    override suspend fun clickNode(text: String, viewId: String?): Result<Unit> {
        val activeService = service
            ?: return Result.failure(IllegalStateException("无障碍服务未连接，无法点击组件"))
        return activeService.clickNode(text, viewId)
    }

    override suspend fun tap(point: ScreenPoint): Result<Unit> {
        val activeService = service
            ?: return Result.failure(IllegalStateException("无障碍服务未连接，无法点击"))
        return activeService.tapPoint(point)
    }

    override suspend fun swipe(
        start: ScreenPoint,
        end: ScreenPoint,
        durationMillis: Long
    ): Result<Unit> {
        val activeService = service
            ?: return Result.failure(IllegalStateException("无障碍服务未连接，无法滑动"))
        return activeService.swipePoints(start, end, durationMillis)
    }

    override fun performBack(): Boolean {
        return service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) == true
    }

    override fun launchTargetApp(): Boolean = DeviceStatus.openTargetApp(service ?: context)

    override suspend fun restartTargetApp(): Boolean {
        val activeService = service ?: return false
        if (!activeService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)) {
            AppLogStore.add("重启阶段失败：无法返回桌面")
            return false
        }
        AppLogStore.add("重启阶段：已返回桌面，等待起点进入后台")
        if (!waitForPackageToLeaveTarget(activeService)) {
            AppLogStore.add("重启阶段失败：返回桌面后前台仍是起点读书")
            return false
        }
        AppLogStore.add("重启阶段：已确认起点读书离开前台")

        if (!DeviceStatus.closeTargetApp(context)) {
            AppLogStore.add("重启阶段失败：无法请求关闭起点后台进程")
            return false
        }
        AppLogStore.add("重启阶段：已请求关闭起点后台进程")
        delay(RESTART_PROCESS_SETTLE_DELAY_MILLIS)

        if (!DeviceStatus.openTargetApp(activeService, clearTask = true)) {
            AppLogStore.add("重启阶段失败：无法重新启动起点读书")
            return false
        }
        AppLogStore.add("重启阶段：已发送起点启动请求，等待前台确认")
        val foregroundConfirmed = waitForTargetForeground(activeService)
        if (foregroundConfirmed) {
            AppLogStore.add("重启阶段：已确认起点读书重新进入前台")
        } else {
            AppLogStore.add("重启阶段失败：启动后未确认起点读书进入前台")
        }
        return foregroundConfirmed
    }

    override fun launchAutomationApp(): Boolean = DeviceStatus.openAutomationApp(service ?: context)

    override suspend fun closeTargetAppAndGoHome(): Result<Unit> = runCatching {
        val activeService = service
            ?: error("无障碍服务未连接，无法返回桌面")
        check(activeService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)) {
            "返回桌面动作执行失败"
        }
        delay(HOME_SETTLE_DELAY_MILLIS)
        check(DeviceStatus.closeTargetApp(context)) {
            "关闭起点读书后台进程失败"
        }
    }

    companion object {
        private const val HOME_SETTLE_DELAY_MILLIS = 600L
        private const val RESTART_BACKGROUND_WAIT_TIMEOUT_MILLIS = 3_000L
        private const val RESTART_PROCESS_SETTLE_DELAY_MILLIS = 900L
        private const val RESTART_FOREGROUND_WAIT_TIMEOUT_MILLIS = 8_000L
        private const val RESTART_PACKAGE_POLL_INTERVAL_MILLIS = 250L
    }

    private suspend fun waitForPackageToLeaveTarget(service: QidianAccessibilityService): Boolean {
        val deadline = System.currentTimeMillis() + RESTART_BACKGROUND_WAIT_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (service.currentPackage() != AppConstants.QIDIAN_PACKAGE) return true
            delay(RESTART_PACKAGE_POLL_INTERVAL_MILLIS)
        }
        return false
    }

    private suspend fun waitForTargetForeground(service: QidianAccessibilityService): Boolean {
        val deadline = System.currentTimeMillis() + RESTART_FOREGROUND_WAIT_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (service.currentPackage() == AppConstants.QIDIAN_PACKAGE) return true
            delay(RESTART_PACKAGE_POLL_INTERVAL_MILLIS)
        }
        return false
    }
}
