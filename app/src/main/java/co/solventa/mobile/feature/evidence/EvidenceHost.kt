package co.solventa.mobile.feature.evidence

import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import co.solventa.mobile.core.ui.RetryOnReconnect

@Composable
internal fun EvidenceHost(model: EvidenceViewModel) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by model.state.collectAsState()
    RetryOnReconnect(state.scope, state.open && !state.busy && state.step == EvidenceStep.LIST &&
        state.configuration != null, model::retry)
    DisposableEffect(model, lifecycle) {
        val window = (context as? android.app.Activity)?.window
        val wasSecure = window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE) != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (!hasEvidenceLocationPermission(context)) model.enableLocation(false)
            }
            if (event == Lifecycle.Event.ON_STOP &&
                model.state.value.step in setOf(EvidenceStep.CAMERA, EvidenceStep.CAPTURING)) model.back()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (!wasSecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
    EvidenceScreen(model, model::close, if (state.configuration != null) model::retry else null)
}
