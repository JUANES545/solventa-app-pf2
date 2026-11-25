package co.solventa.mobile.core.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

@Composable
internal fun CollectionLoading(label: String, detail: Boolean) {
    val motion = LocalAccessBackgroundState.current
    val pulse = if (motion?.resumed == true && motion.durationScale > 0f)
        rememberInfiniteTransition(label = "Carga").animateFloat(.55f, 1f,
            infiniteRepeatable(tween(750), RepeatMode.Reverse), label = "Esqueleto") else null
    Box(Modifier.graphicsLayer { alpha = pulse?.value ?: 1f }) {
        if (detail) SolventaSkeletonDetail(label) else SolventaSkeletonList(label)
    }
}
