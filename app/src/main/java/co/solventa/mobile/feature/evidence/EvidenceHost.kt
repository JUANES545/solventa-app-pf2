package co.solventa.mobile.feature.evidence

import android.net.ConnectivityManager
import android.net.Network
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.first

@Composable
internal fun EvidenceHost(model: EvidenceViewModel) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by model.state.collectAsState()
    DisposableEffect(model, lifecycle) {
        val window = (context as? android.app.Activity)?.window
        val wasSecure = window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE) != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (!hasEvidenceLocationPermission(context)) model.enableLocation(false)
                model.retry()
            }
            if (event == Lifecycle.Event.ON_STOP &&
                model.state.value.step in setOf(EvidenceStep.CAMERA, EvidenceStep.CAPTURING)) model.back()
        }
        lifecycle.addObserver(observer)
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val executor = ContextCompat.getMainExecutor(context)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                executor.execute { if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) model.retry() }
            }
        }
        manager.registerDefaultNetworkCallback(callback)
        onDispose {
            manager.unregisterNetworkCallback(callback)
            lifecycle.removeObserver(observer)
            if (!wasSecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
    LaunchedEffect(state.scope) {
        if (state.scope != null) {
            model.state.first { !it.busy }
            model.retry()
        }
    }
    EvidenceScreen(model, model::close, if (state.configuration != null) model::retry else null)
}
