package co.solventa.mobile.feature.evidence

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import co.solventa.mobile.feature.claim.statusClaim
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class EvidenceHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun await(condition: () -> Boolean) = compose.waitUntil(15_000) {
        shadowOf(android.os.Looper.getMainLooper()).idle()
        condition()
    }

    @Test fun reconnectReconcilesAnAcceptedEvidenceWithoutResendingItsBytes() {
        val app = RuntimeEnvironment.getApplication()
        val visible = mutableStateOf(true)
        val owner = object : LifecycleOwner { override val lifecycle = LifecycleRegistry(this) }
        compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        val scope = EvidenceScope("actor-a", statusClaim.id)
        val policy = EvidencePolicy(3, mapOf(EvidenceKind.PHOTO to 1024L), 1000)
        val source = java.io.File.createTempFile("reconnect-", ".jpg", app.cacheDir).apply { writeBytes(byteArrayOf(1, 2)) }
        val item = EvidenceStore(app.noBackupFilesDir).use {
            it.enqueue(scope, EvidenceKind.PHOTO, source, Instant.now(), null, 0, policy, emptySet())
        }
        check(source.delete())
        val online = AtomicBoolean(false)
        val keys = CopyOnWriteArrayList<String?>()
        val mutations = CopyOnWriteArrayList<String>()
        val remoteId = UUID.randomUUID().toString()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path!!.endsWith("/configuracion-evidencias")) return MockResponse().setBody(
                        """{"maxCantidad":3,"maxChunkBytes":1024,"maxVideoSeconds":1,"formatos":[{"mimeType":"image/jpeg","maxBytes":1024}]}""")
                    if (request.path!!.endsWith("/evidencias")) return MockResponse().setBody("""{"evidencias":[]}""")
                    mutations.add(checkNotNull(request.method))
                    keys.add(request.getHeader("Idempotency-Key"))
                    if (!online.get()) return MockResponse().setResponseCode(503)
                    return MockResponse().setBody(JSONObject().put("id", UUID.randomUUID().toString())
                        .put("siniestroId", scope.claimId).put("estado", "confirmada").put("offset", 2)
                        .put("tamanoBytes", 2).put("mimeType", "image/jpeg").put("sha256", item.sha256)
                        .put("evidenciaId", remoteId).toString())
                }
            }
            val model = EvidenceViewModel(app, MobileEvidenceApi(evidenceAuth(), server.url("/movil/v1").toString()))
            try {
                compose.runOnUiThread { model.showClaim(scope.actorId, statusClaim) }
                compose.setContent {
                    if (visible.value) CompositionLocalProvider(LocalLifecycleOwner provides owner) { EvidenceHost(model) }
                }
                compose.waitForIdle()
                await { !model.state.value.busy && model.state.value.error == EvidenceError.NETWORK }
                compose.runOnIdle { assertEquals(EvidenceStatus.PENDING, model.state.value.items.single().status) }
                val manager = app.getSystemService(ConnectivityManager::class.java)
                val callback = shadowOf(manager).networkCallbacks.single()
                val network = mock(Network::class.java)
                val capabilities = mock(NetworkCapabilities::class.java)
                `when`(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(true)
                `when`(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)).thenReturn(true)
                online.set(true)
                compose.runOnIdle { callback.onAvailable(network); callback.onCapabilitiesChanged(network, capabilities) }
                compose.waitForIdle()
                await { model.state.value.items.single().status == EvidenceStatus.CONFIRMED }
                await { !model.state.value.busy }
                assertEquals(remoteId, model.state.value.items.single().remoteId)
                assertEquals(setOf(item.id), keys.toSet())
                assertEquals(setOf("POST"), mutations.toSet())
                val count = mutations.size
                compose.runOnIdle { callback.onCapabilitiesChanged(network, capabilities) }
                compose.waitForIdle()
                assertEquals(count, mutations.size)
                compose.runOnIdle {
                    assertEquals(!co.solventa.mobile.BuildConfig.SCREEN_CAPTURE_ALLOWED,
                        compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                    model.camera(EvidenceKind.PHOTO)
                    owner.lifecycle.currentState = Lifecycle.State.CREATED
                }
                compose.runOnIdle {
                    assertEquals(EvidenceStep.LIST, model.state.value.step)
                    visible.value = false
                }
                compose.runOnIdle {
                    assertEquals(0, compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
                    assertTrue(shadowOf(manager).networkCallbacks.isEmpty())
                }
            } finally {
                compose.runOnUiThread {
                    model.close()
                    androidx.lifecycle.ViewModelStore().apply { put("evidence", model); clear() }
                }
            }
        }
    }
}
