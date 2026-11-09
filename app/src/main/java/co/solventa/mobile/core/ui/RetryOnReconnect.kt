package co.solventa.mobile.core.ui

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

internal class ValidatedNetworkCallback(private val reconnect: () -> Unit) : ConnectivityManager.NetworkCallback() {
    private var current: Network? = null
    private var validated = false

    override fun onAvailable(network: Network) {
        current = network
        validated = false
    }

    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
        if (network != current) return
        val online = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val recovered = online && !validated
        validated = online
        if (recovered) reconnect()
    }

    override fun onLost(network: Network) {
        if (network == current) {
            current = null
            validated = false
        }
    }
}

@Composable
internal fun RetryOnReconnect(scope: Any?, ready: Boolean, retry: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentRetry by rememberUpdatedState(retry)
    var pending by remember(scope, lifecycle) { mutableStateOf(false) }
    var resumed by remember(scope, lifecycle) { mutableStateOf(false) }
    DisposableEffect(scope, context, lifecycle) {
        var active = true
        val observer = LifecycleEventObserver { _, _ ->
            val foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (foreground && !resumed) pending = true
            resumed = foreground
        }
        lifecycle.addObserver(observer)
        val executor = ContextCompat.getMainExecutor(context)
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val callback = ValidatedNetworkCallback {
            executor.execute { if (active) pending = true }
        }
        manager.registerDefaultNetworkCallback(callback)
        onDispose {
            active = false
            manager.unregisterNetworkCallback(callback)
            lifecycle.removeObserver(observer)
        }
    }
    LaunchedEffect(pending, ready, resumed, scope) {
        if (pending && ready && resumed) {
            pending = false
            currentRetry()
        }
    }
}
