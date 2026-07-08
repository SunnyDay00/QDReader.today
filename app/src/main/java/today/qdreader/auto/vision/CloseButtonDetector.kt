package today.qdreader.auto.vision

import android.graphics.Bitmap
import android.graphics.Rect
import today.qdreader.auto.accessibility.ScreenPoint

data class CloseButtonMatch(
    val point: ScreenPoint,
    val bounds: Rect,
    val score: Double,
    val templateName: String
)

interface CloseButtonDetector {
    suspend fun detectCloseButton(bitmap: Bitmap, threshold: Double = 0.68): Result<CloseButtonMatch?>
}
