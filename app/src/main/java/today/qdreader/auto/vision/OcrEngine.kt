package today.qdreader.auto.vision

import android.graphics.Bitmap
import android.graphics.Rect

data class OcrTextBlock(
    val text: String,
    val bounds: Rect?,
    val elements: List<OcrTextBlock> = emptyList()
)

data class OcrResult(
    val rawText: String,
    val blocks: List<OcrTextBlock>,
    val elapsedMillis: Long
)

interface OcrEngine : AutoCloseable {
    suspend fun recognize(bitmap: Bitmap): Result<OcrResult>
}
