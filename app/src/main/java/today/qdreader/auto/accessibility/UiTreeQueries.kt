package today.qdreader.auto.accessibility

import android.graphics.Rect
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

fun UiNodeSnapshot.flatten(): Sequence<UiNodeSnapshot> = sequence {
    yield(this@flatten)
    children.forEach { child -> yieldAll(child.flatten()) }
}

fun UiTreeSnapshot.findNode(
    text: String? = null,
    viewId: String? = null
): UiNodeSnapshot? {
    return root.flatten().firstOrNull { node ->
        (text == null || node.text == text) &&
            (viewId == null || node.viewId == viewId)
    }
}

fun UiTreeSnapshot.findNodes(
    text: String? = null,
    viewId: String? = null
): List<UiNodeSnapshot> {
    return root.flatten().filter { node ->
        (text == null || node.text == text) &&
            (viewId == null || node.viewId == viewId)
    }.toList()
}

fun UiTreeSnapshot.hasNode(text: String, viewId: String? = null): Boolean {
    return findNode(text = text, viewId = viewId) != null
}

fun UiTreeSnapshot.hasAllTexts(texts: Collection<String>): Boolean {
    return texts.all { text -> root.flatten().any { node -> node.text == text } }
}

fun UiTreeSnapshot.clickPointFor(node: UiNodeSnapshot): ScreenPoint {
    val center = node.bounds.centerPoint()
    val clickableContainer = root.flatten()
        .filter { candidate -> candidate.clickable && candidate.enabled }
        .filter { candidate -> candidate.bounds.contains(center.x.toInt(), center.y.toInt()) }
        .minByOrNull { candidate -> candidate.bounds.area() }

    return (clickableContainer ?: node).bounds.centerPoint()
}

fun Rect.centerPoint(): ScreenPoint {
    return ScreenPoint(
        x = exactCenterX(),
        y = exactCenterY()
    )
}

fun Rect.distanceTo(other: Rect): Float {
    val dx = exactCenterX() - other.exactCenterX()
    val dy = exactCenterY() - other.exactCenterY()
    return sqrt(dx.pow(2) + dy.pow(2))
}

private fun Rect.area(): Int {
    return max(0, width()) * max(0, height())
}
