package co.solventa.mobile.core.ui

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class RetryOnReconnectTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun capabilities(internet: Boolean, validated: Boolean) = mock(NetworkCapabilities::class.java).apply {
        `when`(hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(internet)
        `when`(hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)).thenReturn(validated)
    }

    @Test fun onlyValidationEdgesOnTheCurrentNetworkRequestRetry() {
        var retries = 0
        val callback = ValidatedNetworkCallback { retries++ }
        val first = mock(Network::class.java)
        val second = mock(Network::class.java)
        val online = capabilities(true, true)
        callback.onCapabilitiesChanged(first, online)
        assertEquals(0, retries)
        callback.onAvailable(first)
        callback.onCapabilitiesChanged(first, capabilities(false, true))
        callback.onCapabilitiesChanged(first, capabilities(true, false))
        assertEquals(0, retries)
        callback.onCapabilitiesChanged(first, online)
        callback.onCapabilitiesChanged(first, online)
        assertEquals(1, retries)
        callback.onCapabilitiesChanged(first, capabilities(true, false))
        callback.onCapabilitiesChanged(first, online)
        assertEquals(2, retries)
        callback.onAvailable(second)
        callback.onLost(first)
        callback.onCapabilitiesChanged(first, online)
        assertEquals(2, retries)
        callback.onCapabilitiesChanged(second, online)
        assertEquals(3, retries)
        callback.onLost(second)
        callback.onCapabilitiesChanged(second, online)
        assertEquals(3, retries)
        callback.onAvailable(second)
        callback.onCapabilitiesChanged(second, online)
        assertEquals(4, retries)
    }

    @Test fun pendingReconnectWaitsForReadinessAndForegroundWithoutBusyRetryLoops() {
        val ready = mutableStateOf(false)
        val visible = mutableStateOf(true)
        val scope = mutableStateOf("actor-a")
        val owner = object : LifecycleOwner { override val lifecycle = LifecycleRegistry(this) }
        val retries = mutableListOf<String>()
        compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            if (visible.value) CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                val actor = scope.value
                RetryOnReconnect(actor, ready.value) { retries.add(actor) }
            }
        }
        val manager = compose.activity.getSystemService(ConnectivityManager::class.java)
        val firstCallback = shadowOf(manager).networkCallbacks.single()
        val network = mock(Network::class.java)
        val online = capabilities(true, true)
        compose.runOnIdle {
            firstCallback.onAvailable(network)
            firstCallback.onCapabilitiesChanged(network, online)
            assertTrue(retries.isEmpty())
        }
        compose.runOnIdle { ready.value = true }
        compose.runOnIdle { assertEquals(listOf("actor-a"), retries); ready.value = false }
        compose.runOnIdle { ready.value = true }
        compose.runOnIdle { assertEquals(1, retries.size) }
        compose.runOnIdle {
            ready.value = false
            firstCallback.onCapabilitiesChanged(network, capabilities(true, false))
            firstCallback.onCapabilitiesChanged(network, online)
            firstCallback.onCapabilitiesChanged(network, online)
        }
        compose.runOnIdle { assertEquals(1, retries.size); ready.value = true }
        compose.runOnIdle { assertEquals(2, retries.size); owner.lifecycle.currentState = Lifecycle.State.CREATED }
        compose.runOnIdle {
            firstCallback.onLost(network)
            firstCallback.onAvailable(network)
            firstCallback.onCapabilitiesChanged(network, online)
        }
        compose.runOnIdle { assertEquals(2, retries.size); owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        compose.runOnIdle { assertEquals(3, retries.size); ready.value = false }
        compose.runOnIdle {
            firstCallback.onLost(network)
            firstCallback.onAvailable(network)
            firstCallback.onCapabilitiesChanged(network, online)
            scope.value = "actor-b"
        }
        compose.runOnIdle { ready.value = true }
        compose.runOnIdle { assertEquals(listOf("actor-a", "actor-a", "actor-a", "actor-b"), retries) }
        compose.runOnIdle {
            firstCallback.onLost(network)
            firstCallback.onAvailable(network)
            firstCallback.onCapabilitiesChanged(network, online)
        }
        compose.runOnIdle { assertEquals(4, retries.size); visible.value = false }
        compose.runOnIdle { assertTrue(shadowOf(manager).networkCallbacks.isEmpty()) }
    }
}
