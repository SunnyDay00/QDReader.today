package today.qdreader.auto.vision

import android.graphics.Rect
import today.qdreader.auto.accessibility.ScreenPoint
import kotlin.math.abs

data class OcrActionTextMatch(
    val actionText: String,
    val point: ScreenPoint,
    val bounds: Rect
)

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

fun OcrResult.findTextCenter(needle: String): ScreenPoint? {
    return findBlocksContaining(needle)
        .mapNotNull { it.bounds }
        .minByOrNull { bounds -> bounds.top * 10_000 + bounds.left }
        ?.let { bounds -> ScreenPoint(bounds.exactCenterX(), bounds.exactCenterY()) }
}

fun OcrResult.findAnyTextCenter(needles: List<String>): ScreenPoint? {
    return needles.firstNotNullOfOrNull { needle -> findTextCenter(needle) }
}

fun OcrResult.hasAnyText(needles: List<String>): Boolean {
    return needles.any { needle -> hasText(needle) }
}

fun OcrResult.findActionAfterText(
    anchorText: String,
    actionTexts: List<String>
): OcrActionTextMatch? {
    return findActionAfterAnyText(listOf(anchorText), actionTexts)
}

fun OcrResult.findActionAfterAnyText(
    anchorTexts: List<String>,
    actionTexts: List<String>
): OcrActionTextMatch? {
    val normalizedAnchors = anchorTexts.map { it.normalizedForOcr() }
    val normalizedActions = actionTexts.map { actionText -> actionText to actionText.normalizedForOcr() }

    val sameLineMatch = blocks.firstNotNullOfOrNull { block ->
        val bounds = block.bounds ?: return@firstNotNullOfOrNull null
        val normalizedText = block.text.normalizedForOcr()
        val hasAnchor = normalizedAnchors.any { normalizedAnchor ->
            normalizedText.contains(normalizedAnchor)
        }
        val actionText = normalizedActions.firstOrNull { (_, normalizedAction) ->
            hasAnchor && normalizedText.contains(normalizedAction)
        }?.first ?: return@firstNotNullOfOrNull null
        OcrActionTextMatch(
            actionText = actionText,
            point = ScreenPoint(bounds.left + bounds.width() * 0.84f, bounds.exactCenterY()),
            bounds = bounds
        )
    }
    if (sameLineMatch != null) {
        return sameLineMatch
    }

    val anchors = anchorTexts.flatMap { anchorText ->
        findBlocksContaining(anchorText).mapNotNull { it.bounds }
    }
    if (anchors.isEmpty()) return null

    val candidates = actionTexts.flatMap { actionText ->
        findBlocksContaining(actionText).mapNotNull { block ->
            block.bounds?.let { bounds -> actionText to bounds }
        }
    }
    if (candidates.isEmpty()) return null

    return anchors
        .flatMap { anchor ->
            candidates.mapNotNull { (actionText, candidate) ->
                if (!candidate.isLikelySameTaskRow(anchor)) return@mapNotNull null
                val score = abs(candidate.exactCenterY() - anchor.exactCenterY()) * 4 +
                    abs(candidate.exactCenterX() - anchor.exactCenterX())
                ScoredOcrActionTextMatch(
                    match = OcrActionTextMatch(
                        actionText = actionText,
                        point = ScreenPoint(candidate.exactCenterX(), candidate.exactCenterY()),
                        bounds = candidate
                    ),
                    score = score
                )
            }
        }
        .minByOrNull { it.score }
        ?.match
}

private fun String.normalizedForOcr(): String {
    return filterNot { char ->
        char.isWhitespace() ||
            char in "/\\|,，.。:：;；!！?？()（）[]【】{}"
    }
}

private fun Rect.isLikelySameTaskRow(anchor: Rect): Boolean {
    val verticalTolerance = maxOf(150f, anchor.height() * 3.2f)
    val verticalDelta = abs(exactCenterY() - anchor.exactCenterY())
    val isRightOfAnchor = exactCenterX() >= anchor.exactCenterX()
    return isRightOfAnchor && verticalDelta <= verticalTolerance
}

private data class ScoredOcrActionTextMatch(
    val match: OcrActionTextMatch,
    val score: Float
)
