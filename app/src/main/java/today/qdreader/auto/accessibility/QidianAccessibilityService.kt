package today.qdreader.auto.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import today.qdreader.auto.logs.AppLogStore
import kotlin.coroutines.resume

class QidianAccessibilityService : AccessibilityService() {
    @Volatile
    private var lastPackageName: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AppLogStore.add("无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        lastPackageName = event?.packageName?.toString()
    }

    override fun onInterrupt() {
        AppLogStore.add("无障碍服务被系统中断")
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        AppLogStore.add("无障碍服务已断开")
        super.onDestroy()
    }

    fun currentPackage(): String? = rootInActiveWindow?.packageName?.toString() ?: lastPackageName

    fun activeWindowSnapshot(): UiTreeSnapshot? {
        val root = rootInActiveWindow ?: return null
        return UiTreeSnapshot(
            packageName = root.packageName?.toString(),
            root = root.toSnapshot()
        )
    }

    @SuppressLint("NewApi")
    suspend fun screenshotBitmap(): Result<Bitmap> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return Result.failure(IllegalStateException("截图需要 Android 11 或更高版本"))
        }

        return suspendCancellableCoroutine { continuation ->
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: ScreenshotResult) {
                        val hardwareBuffer = screenshotResult.hardwareBuffer
                        val hardwareBitmap = Bitmap.wrapHardwareBuffer(
                            hardwareBuffer,
                            screenshotResult.colorSpace
                        )
                        if (hardwareBitmap == null) {
                            hardwareBuffer.close()
                            continuation.resume(Result.failure(IllegalStateException("无法读取截图 bitmap")))
                            return
                        }

                        val bitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
                        hardwareBuffer.close()
                        continuation.resume(Result.success(bitmap))
                    }

                    override fun onFailure(errorCode: Int) {
                        continuation.resume(
                            Result.failure(IllegalStateException("截图失败，错误码：$errorCode"))
                        )
                    }
                }
            )
        }
    }

    suspend fun tapPoint(point: ScreenPoint): Result<Unit> {
        val path = Path().apply { moveTo(point.x, point.y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        return dispatchGestureResult(gesture, "点击失败")
    }

    suspend fun swipePoints(
        start: ScreenPoint,
        end: ScreenPoint,
        durationMillis: Long
    ): Result<Unit> {
        val path = Path().apply {
            moveTo(start.x, start.y)
            lineTo(end.x, end.y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMillis))
            .build()
        return dispatchGestureResult(gesture, "滑动失败")
    }

    private suspend fun dispatchGestureResult(
        gesture: GestureDescription,
        failureMessage: String
    ): Result<Unit> = suspendCancellableCoroutine { continuation ->
        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    continuation.resume(Result.success(Unit))
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    continuation.resume(Result.failure(IllegalStateException(failureMessage)))
                }
            },
            null
        )
        if (!dispatched) {
            continuation.resume(Result.failure(IllegalStateException(failureMessage)))
        }
    }

    private fun AccessibilityNodeInfo.toSnapshot(): UiNodeSnapshot {
        val rect = Rect()
        getBoundsInScreen(rect)
        val childSnapshots = buildList {
            for (index in 0 until childCount) {
                getChild(index)?.let { child -> add(child.toSnapshot()) }
            }
        }
        return UiNodeSnapshot(
            text = text?.toString(),
            contentDescription = contentDescription?.toString(),
            className = className?.toString(),
            viewId = viewIdResourceName,
            packageName = packageName?.toString(),
            bounds = rect,
            clickable = isClickable,
            enabled = isEnabled,
            children = childSnapshots
        )
    }

    companion object {
        @Volatile
        var instance: QidianAccessibilityService? = null
            private set
    }
}
