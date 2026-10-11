package co.solventa.mobile.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp

// Reused from the original Android app while the Figma vector export is pending.
@Composable
fun SolventaBrandMark(size: Dp) {
    val shieldColor = MaterialTheme.colorScheme.primary
    val letterColor = MaterialTheme.colorScheme.onPrimary
    Canvas(Modifier.size(size)) {
        val unit = this.size.minDimension / 24f
        val shield = Path().apply {
            moveTo(12f, 2.25f)
            lineTo(20f, 5.15f)
            lineTo(20f, 11.35f)
            cubicTo(20f, 16.4f, 16.85f, 20.1f, 12f, 21.75f)
            cubicTo(7.15f, 20.1f, 4f, 16.4f, 4f, 11.35f)
            lineTo(4f, 5.15f)
            close()
        }
        val letter = Path().apply {
            moveTo(15.55f, 7.25f)
            cubicTo(14.65f, 6.47f, 13.43f, 6.05f, 12.05f, 6.05f)
            cubicTo(9.9f, 6.05f, 8.5f, 7f, 8.5f, 8.4f)
            cubicTo(8.5f, 9.95f, 9.9f, 10.52f, 12.05f, 11.05f)
            cubicTo(14.15f, 11.57f, 15.5f, 12.2f, 15.5f, 13.75f)
            cubicTo(15.5f, 15.25f, 14.05f, 16.3f, 11.85f, 16.3f)
            cubicTo(10.35f, 16.3f, 9f, 15.85f, 8f, 14.95f)
        }
        scale(unit, pivot = Offset.Zero) {
            drawPath(shield, shieldColor)
            drawPath(letter, letterColor, style = Stroke(width = 2.35f, cap = StrokeCap.Round))
        }
    }
}
