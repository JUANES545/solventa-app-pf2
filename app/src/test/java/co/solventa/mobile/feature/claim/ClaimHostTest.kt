package co.solventa.mobile.feature.claim

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import co.solventa.mobile.feature.evidence.MobileEvidenceApi
import co.solventa.mobile.feature.evidence.evidenceAuth
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ClaimHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun await(condition: () -> Boolean) = compose.waitUntil(15_000) {
        shadowOf(android.os.Looper.getMainLooper()).idle()
        condition()
    }

    private fun capabilities(validated: Boolean) = org.mockito.Mockito.mock(NetworkCapabilities::class.java).apply {
        org.mockito.Mockito.`when`(hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(true)
        org.mockito.Mockito.`when`(hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)).thenReturn(validated)
    }

    @Test fun validatedConnectionRetriesTheSameUnconfirmedOperation() {
        val app = RuntimeEnvironment.getApplication()
        val report = ClaimStore(app.noBackupFilesDir).use { it.enqueue("actor-a", draft.payload(listOf(policy))) }
        val online = java.util.concurrent.atomic.AtomicBoolean(false)
        val keys = java.util.concurrent.CopyOnWriteArrayList<String?>()
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                    if (request.method == "GET") return okhttp3.mockwebserver.MockResponse().setBody("""{"polizas":[]}""")
                    keys.add(request.getHeader("Idempotency-Key"))
                    return if (online.get()) okhttp3.mockwebserver.MockResponse().setBody(response(report))
                    else okhttp3.mockwebserver.MockResponse().setResponseCode(503)
                }
            }
            val model = ClaimViewModel(app, statusApi(server))
            try {
                compose.runOnUiThread { model.show("actor-a") }
                compose.setContent { ClaimHost(model) {} }
                await { !model.state.value.busy }
                val manager = app.getSystemService(ConnectivityManager::class.java)
                val callback = shadowOf(manager).networkCallbacks.single()
                val network = org.mockito.Mockito.mock(Network::class.java)
                compose.runOnIdle {
                    callback.onAvailable(network)
                    callback.onCapabilitiesChanged(network, capabilities(false))
                }
                compose.waitForIdle()
                await { !model.state.value.busy }
                assertNull(model.state.value.reports.single().receipt)
                assertEquals("The host must be in the foreground", Lifecycle.State.RESUMED, compose.activity.lifecycle.currentState)
                online.set(true)
                compose.runOnIdle {
                    callback.onCapabilitiesChanged(network, capabilities(true))
                }
                compose.waitForIdle()
                try {
                    await { model.state.value.reports.single().receipt != null }
                } catch (error: androidx.compose.ui.test.ComposeTimeoutException) {
                    throw AssertionError("Reconnect: posts=${keys.size}, busy=${model.state.value.busy}, error=${model.state.value.error}", error)
                }
                await { !model.state.value.busy }
                assertTrue(keys.size >= 2)
                assertEquals(setOf(report.operation), keys.toSet())
                val confirmedCount = keys.size
                compose.runOnIdle {
                    callback.onCapabilitiesChanged(network, capabilities(true))
                }
                compose.waitForIdle()
                assertEquals(confirmedCount, keys.size)
            } finally {
                compose.runOnUiThread {
                    model.bindActor(null)
                    androidx.lifecycle.ViewModelStore().apply { put("claims", model); clear() }
                }
            }
        }
    }

    @Test fun lifecycleNetworkAndDisposalKeepPrivacyAndReleaseCallbacks() {
        val app = RuntimeEnvironment.getApplication()
        val model = ClaimViewModel(app, MobileClaimApi(MobileEvidenceApi(evidenceAuth(), "http://127.0.0.1:1")))
        val visible = mutableStateOf(true)
        val useApplication = mutableStateOf(false)
        val owner = object : LifecycleOwner { override val lifecycle = LifecycleRegistry(this) }
        compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            if (visible.value) {
                CompositionLocalProvider(LocalLifecycleOwner provides owner,
                    LocalContext provides if (useApplication.value) app else compose.activity) {
                    ClaimHost(model) {}
                }
            }
        }
        val manager = app.getSystemService(ConnectivityManager::class.java)
        compose.runOnIdle {
            assertEquals(!co.solventa.mobile.BuildConfig.SCREEN_CAPTURE_ALLOWED,
                compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            shadowOf(manager).networkCallbacks.forEach { it.onAvailable(org.mockito.Mockito.mock(Network::class.java)) }
            owner.lifecycle.currentState = Lifecycle.State.CREATED
            shadowOf(manager).networkCallbacks.forEach { it.onAvailable(org.mockito.Mockito.mock(Network::class.java)) }
        }
        compose.runOnIdle {
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.runOnIdle { visible.value = false }
        compose.runOnIdle {
            assertEquals(0, compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
            assertTrue(shadowOf(manager).networkCallbacks.isEmpty())
            compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            visible.value = true
        }
        compose.runOnIdle { visible.value = false }
        compose.runOnIdle {
            assertTrue(compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            useApplication.value = true
            visible.value = true
        }
        compose.runOnIdle { visible.value = false }
        compose.runOnIdle { assertTrue(shadowOf(manager).networkCallbacks.isEmpty()) }
    }
}
