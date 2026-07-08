package today.qdreader.auto.vision

import android.graphics.Rect
import today.qdreader.auto.accessibility.ScreenPoint
import kotlin.math.pow
import kotlin.math.sqrt

fun OcrResult.hasText(needle: String): Boolean {
    val normalizedNeedle = needle.normalizedForOcr()
    return rawText.normalizedForOcr().contains(normalizedNeedle) ||
        blocks.any { block -> block.text.normalizedForOcr().contains(normalizedNeedle) }
}

fun OcrResult.findBlocksContaining(needle: String): List<OcrTextBlock> {
    val normalizedNeedle = needle.normalizedForOcr()
    return blocks.filter { block ->
        block.bounds != null && block.text.normalizedForOcr().contains(normalizedNeedle)
    }
}

fun OcrResult.findGoCompleteAfterIncentiveTask(): ScreenPoint? {
    val incentiveBlocks = findBlocksContaining("激励任务")
    val goBlocks = findBlocksContaining("去完成")

    val rowBlock = blocks.firstOrNull { block ->
        val text = block.text.normalizedForOcr()
        block.bounds != null && text.contains("激励任务") && text.contains("去完成")
    }
    if (rowBlock?.bounds != null) {
        val bounds = rowBlock.bounds
        return ScreenPoint(
            x = bounds.left + bounds.width() * 0.82f,
            y = bounds.exactCenterY()
        )
    }

    val incentive = incentiveBlocks
        .mapNotNull { it.bounds }
        .minByOrNull { bounds -> bounds.top }
        ?: return null

    return goBlocks
        .mapNotNull { it.bounds }
        .filter { candidate ->
            candidate.exactCenterX() >= incentive.exactCenterX() ||
                candidate.exactCenterY() >= incentive.top - 160
        }
        .minByOrNull { candidate -> candidate.distanceTo(incentive) }
        ?.let { bounds -> ScreenPoint(bounds.exactCenterX(), bounds.exactCenterY()) }
}

private fun String.normalizedForOcr(): String {
    return filterNot { it.isWhitespace() || it == '/' || it == '\\' || it == '|' }
}

private fun Rect.distanceTo(other: Rect): Float {
    val dx = exactCenterX() - other.exactCenterX()
    val dy = exactCenterY() - other.exactCenterY()
    return sqrt(dx.pow(2) + dy.pow(2))
}
