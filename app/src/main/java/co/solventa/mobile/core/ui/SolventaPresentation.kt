package co.solventa.mobile.core.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import co.solventa.mobile.R
import co.solventa.mobile.core.designsystem.SolventaLayout
import co.solventa.mobile.core.designsystem.SolventaMotion

@Composable
internal fun SolventaScreenHeader(
    title: String,
    backLabel: String? = null,
    onBack: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (backLabel != null && onBack != null) {
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = SolventaLayout.controlHeight)) {
                Icon(painterResource(R.drawable.arrow_back), null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(backLabel, textAlign = TextAlign.Center)
            }
        }
        Text(title, Modifier.fillMaxWidth().semantics { heading() },
            style = MaterialTheme.typography.headlineSmall)
    }
}

@Composable
internal fun SolventaSkeletonList(label: String, rows: Int = 3) {
    Column(
        Modifier.fillMaxWidth().clearAndSetSemantics {
            contentDescription = label
            liveRegion = LiveRegionMode.Polite
        },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(rows) { SkeletonCard() }
    }
}

@Composable
internal fun SolventaSkeletonDetail(label: String) {
    Box(Modifier.fillMaxWidth().clearAndSetSemantics {
        contentDescription = label
        liveRegion = LiveRegionMode.Polite
    }) { SkeletonCard(detail = true) }
}

@Composable
private fun SkeletonCard(detail: Boolean = false) {
    val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SkeletonLine(.56f, 20f * scale)
            SkeletonLine(.88f, 12f * scale)
            SkeletonLine(.68f, 12f * scale)
            if (detail) SkeletonLine(.4f, 12f * scale)
        }
    }
}

@Composable
internal fun SolventaLoadError(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    detail: Boolean = false,
) {
    // Match each skeleton measurement after pixel rounding, including fractional device densities.
    val minimumHeight = with(LocalDensity.current) {
        val scale = fontScale.coerceAtLeast(1f)
        val gap = 12.dp.roundToPx()
        val card = 2 * 20.dp.roundToPx() + (20f * scale).dp.roundToPx() +
            (if (detail) 3 else 2) * ((12f * scale).dp.roundToPx() + gap)
        (if (detail) card else 3 * card + 2 * gap).toDp()
    }
    Surface(
        modifier = modifier.fillMaxWidth().heightIn(min = minimumHeight),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            Modifier.padding(20.dp).semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
            },
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            val errorColor = MaterialTheme.colorScheme.error
            Canvas(Modifier.size(28.dp).clearAndSetSemantics {}) {
                val unit = size.minDimension / 28f
                drawCircle(errorColor, 11 * unit, style = Stroke(2 * unit))
                drawLine(errorColor, Offset(14 * unit, 8 * unit), Offset(14 * unit, 15 * unit),
                    strokeWidth = 2 * unit, cap = StrokeCap.Round)
                drawCircle(errorColor, unit, Offset(14 * unit, 20 * unit))
            }
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SkeletonLine(fraction: Float, height: Float) {
    Box(Modifier.fillMaxWidth(fraction).height(height.dp)
        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .10f), RoundedCornerShape(6.dp)))
}

internal enum class SolventaEmptyKind { DOCUMENT, NOTIFICATIONS }

@Composable
internal fun SolventaEmptyState(message: String, kind: SolventaEmptyKind = SolventaEmptyKind.DOCUMENT) {
    val primary = MaterialTheme.colorScheme.primary
    val tone = MaterialTheme.colorScheme.secondaryContainer
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Canvas(Modifier.size(80.dp).clearAndSetSemantics {}) {
            val u = size.minDimension / 80f
            drawCircle(tone, 32 * u, Offset(40 * u, 40 * u))
            val outline = Path().apply {
                if (kind == SolventaEmptyKind.DOCUMENT) {
                    moveTo(27 * u, 19 * u); lineTo(45 * u, 19 * u)
                    lineTo(55 * u, 29 * u); lineTo(55 * u, 61 * u)
                    lineTo(27 * u, 61 * u); close()
                    moveTo(45 * u, 19 * u); lineTo(45 * u, 29 * u); lineTo(55 * u, 29 * u)
                    moveTo(34 * u, 40 * u); lineTo(48 * u, 40 * u)
                    moveTo(34 * u, 48 * u); lineTo(44 * u, 48 * u)
                } else {
                    moveTo(23 * u, 52 * u)
                    cubicTo(30 * u, 45 * u, 25 * u, 26 * u, 40 * u, 26 * u)
                    cubicTo(55 * u, 26 * u, 50 * u, 45 * u, 57 * u, 52 * u)
                    close()
                    moveTo(35 * u, 59 * u); quadraticTo(40 * u, 65 * u, 45 * u, 59 * u)
                    moveTo(40 * u, 21 * u); lineTo(40 * u, 26 * u)
                }
            }
            drawPath(outline, primary, style = Stroke(2 * u, cap = StrokeCap.Round))
        }
        Text(message, Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun SolventaMetadataDetails(
    reference: String? = null,
    entries: List<Pair<String, String>> = emptyList(),
) {
    var expanded by rememberSaveable(reference) { mutableStateOf(false) }
    val expandedLabel = stringResource(if (expanded) R.string.ui_details_expanded else R.string.ui_details_collapsed)
    Column(Modifier.fillMaxWidth().animateContentSize(tween(SolventaMotion.detailMillis)),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { expanded = !expanded },
            modifier = Modifier.heightIn(min = SolventaLayout.controlHeight).semantics { stateDescription = expandedLabel }) {
            Text(stringResource(if (expanded) R.string.ui_hide_details else R.string.ui_show_details),
                textAlign = TextAlign.Center)
        }
        if (expanded) {
            reference?.let {
                Text(stringResource(R.string.ui_reference, it), style = MaterialTheme.typography.bodyMedium)
            }
            entries.forEach { (label, value) ->
                Text("$label: $value", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun SolventaConfirmationMark(key: String) {
    var shown by rememberSaveable(key) { mutableStateOf(false) }
    val motion = LocalAccessBackgroundState.current ?: rememberAccessBackgroundState()
    val progress = remember(key) { Animatable(if (shown) 1f else 0f) }
    LaunchedEffect(key, motion.resumed, motion.durationScale) {
        if (!shown) {
            shown = true
            if (motion.durationScale > 0f && motion.resumed) {
                progress.animateTo(1f, tween(SolventaMotion.confirmationMillis))
            } else progress.snapTo(1f)
        } else progress.snapTo(1f)
    }
    val color = MaterialTheme.colorScheme.tertiary
    Canvas(Modifier.size(40.dp).clearAndSetSemantics {}) {
        val path = Path().apply {
            moveTo(size.width * .2f, size.height * .52f)
            lineTo(size.width * .42f, size.height * .72f)
            lineTo(size.width * .8f, size.height * .28f)
        }
        val measure = PathMeasure().apply { setPath(path, false) }
        val segment = Path()
        measure.getSegment(0f, measure.length * progress.value, segment)
        drawPath(segment, color, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
    }
}
