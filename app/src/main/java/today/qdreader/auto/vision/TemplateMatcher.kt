package today.qdreader.auto.vision

import android.graphics.Bitmap
import android.graphics.Rect

data class TemplateMatchResult(
    val templateName: String?,
    val matched: Boolean,
    val score: Double,
    val threshold: Double,
    val bounds: Rect?,
    val elapsedMillis: Long,
    val message: String
)

interface TemplateMatcher {
    suspend fun matchAny(bitmap: Bitmap, threshold: Double = 0.82): Result<TemplateMatchResult>
}
