package today.qdreader.auto.accessibility

import android.graphics.Bitmap
import android.graphics.Rect

data class ScreenPoint(val x: Float, val y: Float)

data class UiNodeSnapshot(
    val text: String?,
    val contentDescription: String?,
    val className: String?,
    val viewId: String?,
    val packageName: String?,
    val bounds: Rect,
    val clickable: Boolean,
    val enabled: Boolean,
    val visibleToUser: Boolean,
    val children: List<UiNodeSnapshot>
)

data class UiTreeSnapshot(
    val packageName: String?,
    val root: UiNodeSnapshot,
    val capturedAtMillis: Long = System.currentTimeMillis()
)

interface AccessibilityBridge {
    fun isServiceConnected(): Boolean
    fun currentPackageName(): String?
    fun readActiveWindow(): UiTreeSnapshot?
    suspend fun captureScreenshot(): Result<Bitmap>
    suspend fun clickNode(text: String, viewId: String? = null): Result<Unit>
    suspend fun tap(point: ScreenPoint): Result<Unit>
    suspend fun swipe(start: ScreenPoint, end: ScreenPoint, durationMillis: Long = 350): Result<Unit>
    fun performBack(): Boolean
    fun launchTargetApp(): Boolean
    suspend fun restartTargetApp(): Boolean
    fun launchAutomationApp(): Boolean
    suspend fun closeTargetAppAndGoHome(): Result<Unit>
}
