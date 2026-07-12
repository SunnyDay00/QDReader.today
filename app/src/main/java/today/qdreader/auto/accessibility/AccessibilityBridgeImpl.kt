package today.qdreader.auto.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.delay
import today.qdreader.auto.core.DeviceStatus

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

    override fun restartTargetApp(): Boolean = DeviceStatus.restartTargetApp(service ?: context)

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
    }
}
