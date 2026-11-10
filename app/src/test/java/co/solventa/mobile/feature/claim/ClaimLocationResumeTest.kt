package co.solventa.mobile.feature.claim

import android.Manifest
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import co.solventa.mobile.core.designsystem.SolventaTheme
import co.solventa.mobile.core.location.DeviceLocation
import co.solventa.mobile.core.ui.RetryOnReconnect
import co.solventa.mobile.feature.evidence.MobileEvidenceApi
import co.solventa.mobile.feature.evidence.evidenceAuth
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ClaimLocationResumeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun claimHostDefersResumeRefreshWhilePermissionIsPending() {
        val permissions = arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        val app = compose.activity.application
        shadowOf(app).denyPermissions(*permissions)
        val launches = mutableListOf<Int>()
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>,
                input: I, options: ActivityOptionsCompat?) {
                assertArrayEquals(permissions, input as Array<*>)
                launches.add(requestCode)
            }
        }
        val registryOwner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        val owner = object : LifecycleOwner { override val lifecycle = LifecycleRegistry(this) }
        compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse().setBody("""{"polizas":[]}""")
            }
            val model = ClaimViewModel(app, MobileClaimApi(MobileEvidenceApi(evidenceAuth(), server.url("/").toString())))
            try {
                compose.runOnUiThread { model.show("actor-a") }
                compose.setContent {
                    SolventaTheme(false) {
                        CompositionLocalProvider(LocalLifecycleOwner provides owner,
                            LocalActivityResultRegistryOwner provides registryOwner) { ClaimHost(model) {} }
                    }
                }
                compose.waitUntil(15_000) {
                    shadowOf(android.os.Looper.getMainLooper()).idle()
                    model.state.value.locationRevision >= 3 && !model.state.value.busy
                }
                compose.onNodeWithTag("claim-current-location").performScrollTo().performClick()
                compose.waitUntil(10_000) { launches.size == 1 }
                val before = model.state.value.locationRevision
                compose.runOnIdle {
                    owner.lifecycle.currentState = Lifecycle.State.STARTED
                    owner.lifecycle.currentState = Lifecycle.State.RESUMED
                }
                compose.waitForIdle()
                assertEquals("Refresh must wait while the permission prompt is active",
                    before, model.state.value.locationRevision)
                compose.runOnIdle { registry.dispatchResult(launches.single(), emptyMap<String, Boolean>()) }
                compose.waitUntil(15_000) {
                    shadowOf(android.os.Looper.getMainLooper()).idle()
                    model.state.value.locationRevision > before && !model.state.value.busy
                }
                assertEquals("", model.state.value.draft.place)
            } finally {
                compose.runOnUiThread {
                    model.bindActor(null)
                    androidx.lifecycle.ViewModelStore().apply { put("claims", model); clear() }
                }
            }
        }
    }

    @Test fun resumeRefreshWaitsForTheRequestedLocation() {
        val permissions = arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(compose.activity.application).denyPermissions(*permissions)
        val launches = mutableListOf<Int>()
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>,
                input: I, options: ActivityOptionsCompat?) {
                assertArrayEquals(permissions, input as Array<*>)
                launches.add(requestCode)
            }
        }
        val registryOwner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        val owner = object : LifecycleOwner { override val lifecycle = LifecycleRegistry(this) }
        compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        val state = mutableStateOf(ClaimState(actor = "actor-a", open = true))
        val active = mutableStateOf(false)
        val location = CompletableDeferred<DeviceLocation?>()
        var started = false
        var refreshes = 0
        compose.setContent {
            SolventaTheme(false) {
                CompositionLocalProvider(LocalLifecycleOwner provides owner,
                    LocalActivityResultRegistryOwner provides registryOwner) {
                    RetryOnReconnect(state.value.actor, state.value.open && !state.value.busy && !active.value) {
                        refreshes++
                        state.value = state.value.copy(locationRevision = state.value.locationRevision + 1)
                    }
                    ClaimLocationField(state.value, { state.value = state.value.copy(draft = it) }, { fail(it) },
                        readState = { state.value }, address = { "Calle sintética 10" },
                        onActiveChange = { active.value = it }) {
                        started = true
                        location.await()
                    }
                }
            }
        }
        compose.waitUntil(10_000) { refreshes > 0 }
        val before = refreshes
        compose.onNodeWithTag("claim-current-location").performClick()
        compose.waitUntil(10_000) { launches.size == 1 }
        compose.runOnIdle {
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            shadowOf(compose.activity.application).grantPermissions(permissions[0])
            registry.dispatchResult(launches.single(), permissions.associateWith { it == permissions[0] })
        }
        compose.waitUntil(10_000) { started }
        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()
        assertEquals("Refresh must not cancel the user's location request", before, refreshes)
        compose.runOnIdle { location.complete(DeviceLocation(4.5, -74.1, Instant.now())) }
        compose.waitUntil(10_000) { state.value.draft.place == "Calle sintética 10" }
        compose.waitUntil(10_000) { refreshes == before + 1 }
    }
}
