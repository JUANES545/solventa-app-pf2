package co.solventa.mobile.feature.photo

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

// Bounds are the actual preview image in local overlay pixels, excluding letterboxing.
// This decorative guide does not detect documents; instructions belong to the caller.
@Composable
internal fun DocumentFrameOverlay(imageBounds: Rect?, modifier: Modifier = Modifier) {
    val outerColor = MaterialTheme.colorScheme.surface
    val innerColor = MaterialTheme.colorScheme.onSurface
    Canvas(modifier) {
        val frame = imageBounds?.let {
            documentFrameBounds(it, Rect(0f, 0f, size.width, size.height))
        } ?: return@Canvas
        val outerWidth = 6.dp.toPx()
        if (frame.width <= outerWidth || frame.height <= outerWidth) return@Canvas
        val outline = frame.deflate(outerWidth / 2)
        drawRect(outerColor, outline.topLeft, outline.size, style = Stroke(outerWidth))
        drawRect(innerColor, outline.topLeft, outline.size, style = Stroke(3.dp.toPx()))
    }
}

internal fun documentFrameBounds(imageBounds: Rect, viewportBounds: Rect): Rect? {
    if (!listOf(imageBounds, viewportBounds).all { bounds ->
        bounds.left.isFinite() && bounds.top.isFinite() &&
            bounds.right.isFinite() && bounds.bottom.isFinite() &&
            bounds.width > 0f && bounds.height > 0f
    }) return null
    val visible = imageBounds.intersect(viewportBounds)
    if (visible.width <= 0f || visible.height <= 0f) return null
    val width = minOf(visible.width * .84f, visible.height * .84f * 1.6f)
    val height = width / 1.6f
    return Rect(
        visible.center.x - width / 2,
        visible.center.y - height / 2,
        visible.center.x + width / 2,
        visible.center.y + height / 2,
    )
}
