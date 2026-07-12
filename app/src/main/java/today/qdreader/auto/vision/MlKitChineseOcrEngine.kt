package today.qdreader.auto.vision

import android.graphics.Bitmap
import android.os.SystemClock
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MlKitChineseOcrEngine : OcrEngine {
    private val recognizer = TextRecognition.getClient(
        ChineseTextRecognizerOptions.Builder().build()
    )

    override suspend fun recognize(bitmap: Bitmap): Result<OcrResult> {
        return runCatching {
            val image = InputImage.fromBitmap(bitmap, 0)
            val started = SystemClock.elapsedRealtime()
            val detectedText = recognizer.process(image).await()
            val elapsed = SystemClock.elapsedRealtime() - started
            OcrResult(
                rawText = detectedText.text,
                blocks = detectedText.textBlocks
                    .flatMap { block -> block.lines }
                    .map { line ->
                        OcrTextBlock(
                            text = line.text,
                            bounds = line.boundingBox,
                            elements = line.elements.map { element ->
                                OcrTextBlock(text = element.text, bounds = element.boundingBox)
                            }
                        )
                    },
                elapsedMillis = elapsed
            )
        }
    }

    override fun close() {
        recognizer.close()
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { value -> continuation.resume(value) }
    addOnFailureListener { error -> continuation.resumeWithException(error) }
    addOnCanceledListener { continuation.cancel() }
}
