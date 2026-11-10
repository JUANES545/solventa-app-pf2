package co.solventa.mobile.core.ui

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Stable
internal class AccessBackgroundState {
    internal var progress by mutableFloatStateOf(0f)
    var resumed by mutableStateOf(false)
    var durationScale by mutableFloatStateOf(0f)
}

internal val LocalAccessBackgroundState = staticCompositionLocalOf<AccessBackgroundState?> { null }

@Composable
internal fun rememberAccessBackgroundState(): AccessBackgroundState {
    val state = remember { AccessBackgroundState() }
    val resolver = LocalContext.current.contentResolver
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(state, resolver, lifecycle) {
        var observingScale = false
        fun readScale() {
            state.durationScale = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }
        val scaleObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = readScale()
        }
        fun updateVisibility() {
            state.resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (state.resumed && !observingScale) {
                resolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, scaleObserver,
                )
                observingScale = true
                readScale()
            } else if (!state.resumed && observingScale) {
                resolver.unregisterContentObserver(scaleObserver)
                observingScale = false
            }
        }
        val observer = LifecycleEventObserver { _, _ -> updateVisibility() }
        lifecycle.addObserver(observer)
        updateVisibility()
        onDispose {
            lifecycle.removeObserver(observer)
            if (observingScale) resolver.unregisterContentObserver(scaleObserver)
            state.resumed = false
        }
    }
    return state
}

internal enum class AccessMotionStatus {
    RUNNING, INPUT_PAUSED, ERROR_PAUSED, SENDING_PAUSED, MODAL_PAUSED, SYSTEM_STATIC, NOT_VISIBLE,
}

internal fun accessMotionStatus(
    resumed: Boolean,
    durationScale: Float,
    editing: Boolean,
    hasError: Boolean,
    submitting: Boolean,
    modalOpen: Boolean = false,
): AccessMotionStatus = when {
    !resumed -> AccessMotionStatus.NOT_VISIBLE
    !durationScale.isFinite() || durationScale <= 0f -> AccessMotionStatus.SYSTEM_STATIC
    submitting -> AccessMotionStatus.SENDING_PAUSED
    modalOpen -> AccessMotionStatus.MODAL_PAUSED
    hasError -> AccessMotionStatus.ERROR_PAUSED
    editing -> AccessMotionStatus.INPUT_PAUSED
    else -> AccessMotionStatus.RUNNING
}

internal fun AccessBackgroundState.status(
    editing: Boolean,
    hasError: Boolean,
    submitting: Boolean,
    modalOpen: Boolean = false,
) = accessMotionStatus(resumed, durationScale, editing, hasError, submitting, modalOpen)

internal fun advanceAccessPhase(progress: Float, elapsedSeconds: Float, durationScale: Float): Float {
    if (!durationScale.isFinite() || durationScale <= 0f || !elapsedSeconds.isFinite() || elapsedSeconds <= 0f) {
        return progress
    }
    return (progress + elapsedSeconds / (20f * durationScale)) % 1f
}

@Composable
internal fun AccessBackground(state: AccessBackgroundState, status: AccessMotionStatus) {
    val primary = MaterialTheme.colorScheme.primary.copy(alpha = .07f)
    val secondary = MaterialTheme.colorScheme.secondary.copy(alpha = .09f)
    LaunchedEffect(state, status == AccessMotionStatus.RUNNING) {
        if (status != AccessMotionStatus.RUNNING) return@LaunchedEffect
        var previousFrame = 0L
        while (true) {
            withInfiniteAnimationFrameNanos { frame ->
                if (previousFrame != 0L && state.resumed &&
                    state.durationScale.isFinite() && state.durationScale > 0f) {
                    val elapsed = (frame - previousFrame) / 1_000_000_000f
                    state.progress = advanceAccessPhase(state.progress, elapsed, state.durationScale)
                }
                previousFrame = frame
            }
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        // Read animation only while drawing; the form does not recompose for each frame.
        val phase = state.progress * 2f * PI.toFloat()
        val drift = 12.dp.toPx()
        val radius = size.minDimension * .34f
        drawCircle(primary, radius, Offset(
            -radius * .25f + sin(phase) * drift,
            size.height * .18f + cos(phase) * drift,
        ))
        drawCircle(secondary, radius * .85f, Offset(
            size.width + radius * .15f + cos(phase + 1.4f) * drift,
            size.height * .48f + sin(phase + 1.4f) * drift,
        ))
        drawCircle(primary, radius * 1.15f, Offset(
            size.width * .72f + sin(phase + 3f) * drift,
            size.height + radius * .30f + cos(phase + 3f) * drift,
        ))
    }
}
