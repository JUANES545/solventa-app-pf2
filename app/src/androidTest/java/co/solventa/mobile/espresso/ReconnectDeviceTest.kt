package co.solventa.mobile.espresso

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import co.solventa.mobile.BuildConfig
import co.solventa.mobile.core.ui.ValidatedNetworkCallback
import co.solventa.mobile.feature.auth.AuthApi
import co.solventa.mobile.feature.auth.AuthGrant
import co.solventa.mobile.feature.auth.AuthRepository
import co.solventa.mobile.feature.auth.AuthStore
import co.solventa.mobile.feature.auth.AuthUser
import co.solventa.mobile.feature.claim.ClaimError
import co.solventa.mobile.feature.claim.ClaimHost
import co.solventa.mobile.feature.claim.ClaimPayload
import co.solventa.mobile.feature.claim.ClaimStore
import co.solventa.mobile.feature.claim.ClaimViewModel
import co.solventa.mobile.feature.claim.MobileClaimApi
import co.solventa.mobile.feature.evidence.MobileEvidenceApi
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@ClaimDeviceE2e
@RunWith(AndroidJUnit4::class)
class ReconnectDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun isolatedVariant() {
        check(BuildConfig.APPLICATION_ID == "co.solventa.mobile.pf2.claimtest")
    }

    @Test fun nativeNetworkCapabilitiesSignalRecoveryOnlyOnce() {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = checkNotNull(manager.activeNetwork) { "El dispositivo necesita una red validada para esta prueba." }
        val capabilities = checkNotNull(manager.getNetworkCapabilities(network))
        check(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        var retries = 0
        val callback = ValidatedNetworkCallback { retries++ }
        callback.onAvailable(network)
        callback.onCapabilitiesChanged(network, NetworkCapabilities())
        assertEquals(0, retries)
        callback.onCapabilitiesChanged(network, capabilities)
        callback.onCapabilitiesChanged(network, capabilities)
        assertEquals(1, retries)
        callback.onLost(network)
        callback.onCapabilitiesChanged(network, capabilities)
        assertEquals(1, retries)
        callback.onAvailable(network)
        callback.onCapabilitiesChanged(network, capabilities)
        assertEquals(2, retries)
    }

    @Test fun resumeWhileBusyRetriesAfterFailureWithTheOriginalOperationId() {
        val directory = Files.createTempDirectory(context.noBackupFilesDir.toPath(), "reconnect-test-").toFile()
        val app = object : Application() { override fun getNoBackupFilesDir() = directory }
        val actor = UUID.randomUUID().toString()
        val payload = ClaimPayload(UUID.randomUUID().toString(), Instant.parse("2026-03-01T12:00:00Z"),
            "Choque", "Lugar sintetico", "Reporte de prueba.")
        val report = ClaimStore(directory).use { it.enqueue(actor, payload) }
        val remote = UUID.randomUUID().toString()
        val online = AtomicBoolean(false)
        val hold = AtomicBoolean(false)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val keys = CopyOnWriteArrayList<String?>()
        val owner = object : LifecycleOwner { override val lifecycle = LifecycleRegistry(this) }
        var model: ClaimViewModel? = null
        try {
            MockWebServer().use { server ->
                server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.method == "GET") return MockResponse().setBody("""{"polizas":[]}""")
                        keys.add(request.getHeader("Idempotency-Key"))
                        if (hold.compareAndSet(true, false)) {
                            entered.countDown()
                            check(release.await(20, TimeUnit.SECONDS))
                            return MockResponse().setResponseCode(503)
                        }
                        if (!online.get()) return MockResponse().setResponseCode(503)
                        return MockResponse().setBody(JSONObject(request.body.readUtf8()).put("id", remote)
                            .put("estado", "registrado").put("registradoEn", Instant.now().toString()).toString())
                    }
                }
                val active = ClaimViewModel(app, MobileClaimApi(MobileEvidenceApi(
                    syntheticAuth(actor), "http://127.0.0.1:${server.port}/movil/v1")))
                model = active
                compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.RESUMED; active.show(actor) }
                compose.setContent {
                    CompositionLocalProvider(LocalLifecycleOwner provides owner) { ClaimHost(active) {} }
                }
                compose.waitForIdle()
                compose.waitUntil(20_000) { !active.state.value.busy && active.state.value.error == ClaimError.NETWORK }
                assertTrue("The controlled failure must reach the local server", keys.isNotEmpty())
                val before = keys.size
                hold.set(true)
                compose.runOnIdle {
                    owner.lifecycle.currentState = Lifecycle.State.CREATED
                    owner.lifecycle.currentState = Lifecycle.State.RESUMED
                }
                compose.waitUntil(10_000) { entered.count == 0L }
                compose.runOnIdle {
                    assertTrue(active.state.value.busy)
                    owner.lifecycle.currentState = Lifecycle.State.CREATED
                    owner.lifecycle.currentState = Lifecycle.State.RESUMED
                }
                compose.waitForIdle()
                online.set(true)
                release.countDown()
                compose.waitUntil(20_000) { !active.state.value.busy && active.state.value.reports.single().receipt != null }
                assertEquals(remote, active.state.value.reports.single().receipt!!.id)
                assertEquals(before + 2, keys.size)
                assertEquals(setOf(report.operation), keys.toSet())
                compose.runOnIdle { active.retry() }
                compose.waitUntil(20_000) { !active.state.value.busy }
                assertEquals(before + 2, keys.size)
                compose.runOnIdle { active.bindActor(null) }
                assertTrue(active.state.value.reports.isEmpty())
            }
        } finally {
            release.countDown()
            compose.runOnUiThread { model?.let { ViewModelStore().apply { put("claims", it); clear() } } }
            check(directory.deleteRecursively())
        }
    }

}

internal fun syntheticAuth(actor: String): AuthRepository {
    val user = AuthUser(actor, "Synthetic", "synthetic@example.test")
    var grant: AuthGrant? = AuthGrant(user, "synthetic-access", "synthetic-refresh", Long.MAX_VALUE - 1, Long.MAX_VALUE)
    return AuthRepository(object : AuthApi {
        override fun register(name: String, email: String, password: String) = user
        override fun login(email: String, password: String) = checkNotNull(grant)
        override fun refresh(token: String) = checkNotNull(grant)
        override fun current(token: String) = user
        override fun logout(accessToken: String, refreshToken: String) = Unit
    }, object : AuthStore {
        override fun read() = grant
        override fun write(value: AuthGrant) { grant = value }
        override fun clear() { grant = null }
    })
}
