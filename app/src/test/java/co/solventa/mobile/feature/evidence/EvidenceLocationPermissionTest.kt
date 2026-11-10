package co.solventa.mobile.feature.evidence

import android.Manifest
import android.app.Application
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import co.solventa.mobile.R
import co.solventa.mobile.core.designsystem.SolventaTheme
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class EvidenceLocationPermissionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val permissions = arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
    private val launches = mutableListOf<Int>()
    private val registry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>,
            input: I, options: ActivityOptionsCompat?) {
            assertArrayEquals(permissions, input as Array<*>)
            launches.add(requestCode)
        }
    }
    private val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
    private val scope = EvidenceScope("actor-a", UUID.randomUUID().toString())
    private val policy = EvidencePolicy(4, mapOf(EvidenceKind.PHOTO to 1_000_000L), 0)
    private lateinit var model: EvidenceViewModel

    @Before fun prepare() {
        val app = compose.activity.application
        shadowOf(app).denyPermissions(*permissions)
        model = EvidenceViewModel(app, MobileEvidenceApi(evidenceAuth(), "http://127.0.0.1:1/movil/v1"))
        compose.runOnUiThread { model.open(scope, policy, emptySet()) }
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            !model.state.value.busy
        }
        compose.runOnUiThread { model.camera(EvidenceKind.PHOTO) }
    }

    private fun showScreen() {
        compose.setContent {
            SolventaTheme(false) {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    EvidenceScreen(model, {}, camera = { _, _ -> })
                }
            }
        }
    }

    private fun showChoice(locate: suspend () -> EvidenceLocation?) {
        compose.setContent {
            val state by model.state.collectAsState()
            SolventaTheme(false) {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    LocationChoice(model, state) { locate() }
                }
            }
        }
    }

    private fun toggle() = compose.onNodeWithContentDescription(compose.activity.getString(R.string.evidence_location))
        .performClick()

    @Test fun blockedPermissionOffersSettingsAndDoesNotKeepLocationSelected() {
        showScreen()
        assertTrue(launches.isEmpty())
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.evidence_location))
            .performScrollTo().assertIsOff().performClick()
        compose.waitForIdle()
        assertEquals(1, launches.size)
        compose.runOnIdle { registry.dispatchResult(launches.single(), permissions.associateWith { false }) }
        compose.onNodeWithText("Abrir ajustes").assertIsDisplayed()
        compose.onNodeWithText("Cancelar").performClick()
        assertFalse(model.state.value.locationEnabled)
        assertNull(model.state.value.location)
    }

    @Test fun approximateGrantLocatesOnlyAfterExplicitChoiceAndCanBeRemoved() {
        val location = EvidenceLocation(4.5, -74.1, Instant.parse("2026-01-01T00:00:00Z"))
        var calls = 0
        showChoice { calls++; location }
        assertTrue(launches.isEmpty())
        assertEquals(0, calls)
        toggle()
        compose.waitForIdle()
        assertEquals(1, launches.size)
        assertFalse(model.state.value.locationEnabled)
        compose.runOnIdle {
            shadowOf(compose.activity.application).grantPermissions(permissions[0])
            registry.dispatchResult(launches.single(), permissions.associateWith { it == permissions[0] })
        }
        compose.waitUntil(10_000) { model.state.value.location == location }
        assertTrue(model.state.value.locationEnabled)
        assertEquals(1, calls)
        compose.runOnIdle {
            registry.dispatchResult(launches.single(), permissions.associateWith { it == permissions[0] })
        }
        assertEquals(1, calls)
        toggle()
        compose.runOnIdle {
            assertFalse(model.state.value.locationEnabled)
            assertNull(model.state.value.location)
        }
    }

    @Test fun existingPermissionDoesNotLocateUntilTheUserChoosesIt() {
        shadowOf(compose.activity.application).grantPermissions(permissions[0])
        val location = EvidenceLocation(4.5, -74.1, Instant.parse("2026-01-01T00:00:00Z"))
        var calls = 0
        showChoice { calls++; location }
        assertEquals(0, calls)
        toggle()
        compose.waitUntil(10_000) { model.state.value.location == location }
        assertTrue(launches.isEmpty())
        assertEquals(1, calls)
    }

    @Test fun settingsReturnDoesNotAddLocationWithoutAnotherChoice() {
        val location = EvidenceLocation(4.5, -74.1, Instant.parse("2026-01-01T00:00:00Z"))
        var calls = 0
        showChoice { calls++; location }
        toggle()
        compose.waitForIdle()
        compose.runOnIdle { registry.dispatchResult(launches.single(), permissions.associateWith { false }) }
        compose.onNodeWithText("Abrir ajustes").performClick()
        val intent = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package:${compose.activity.packageName}", intent.data.toString())
        compose.runOnIdle { shadowOf(compose.activity.application).grantPermissions(permissions[0]) }
        assertEquals(0, calls)
        assertFalse(model.state.value.locationEnabled)
        toggle()
        compose.waitUntil(10_000) { model.state.value.location == location }
        assertEquals(1, calls)
        assertEquals(1, launches.size)
    }

    @Test fun cancelledSystemRequestKeepsOptionalLocationOff() {
        var calls = 0
        showChoice { calls++; null }
        toggle()
        compose.waitForIdle()
        compose.runOnIdle { registry.dispatchResult(launches.single(), emptyMap<String, Boolean>()) }
        compose.runOnIdle {
            assertFalse(model.state.value.locationEnabled)
            assertNull(model.state.value.location)
            assertNull(model.state.value.error)
            assertEquals(0, calls)
        }
        compose.onNodeWithText("Abrir ajustes").assertDoesNotExist()
    }

    @Test fun unavailableLocationDoesNotLeaveTheCheckboxSelected() {
        shadowOf(compose.activity.application).grantPermissions(permissions[0])
        var available = false
        val location = EvidenceLocation(4.5, -74.1, Instant.parse("2026-01-01T00:00:00Z"))
        showChoice { if (available) location else null }
        toggle()
        compose.waitUntil(10_000) { model.state.value.error == EvidenceError.LOCATION }
        assertFalse(model.state.value.locationEnabled)
        assertNull(model.state.value.location)
        compose.onNodeWithText("Ubicación no disponible").assertIsDisplayed()
        compose.runOnIdle { available = true }
        compose.onNodeWithText("Reintentar ubicación").performClick()
        compose.waitUntil(10_000) { model.state.value.location == location }
        assertTrue(model.state.value.locationEnabled)
        assertNull(model.state.value.error)
    }

    @Test fun latePermissionAndLocationCannotAffectAReopenedReport() {
        val location = EvidenceLocation(4.5, -74.1, Instant.parse("2026-01-01T00:00:00Z"))
        val deferred = CompletableDeferred<EvidenceLocation?>()
        var calls = 0
        showChoice { calls++; deferred.await() }
        toggle()
        compose.waitForIdle()
        compose.runOnIdle {
            model.close()
            model.open(scope, policy, emptySet())
            shadowOf(compose.activity.application).grantPermissions(permissions[0])
            registry.dispatchResult(launches.single(), permissions.associateWith { it == permissions[0] })
        }
        compose.waitForIdle()
        assertEquals(0, calls)
        assertFalse(model.state.value.locationEnabled)
        assertNull(model.state.value.location)

        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            !model.state.value.busy
        }
        compose.runOnIdle { model.camera(EvidenceKind.PHOTO) }
        toggle()
        compose.waitUntil(10_000) { calls == 1 }
        compose.runOnIdle {
            model.close()
            model.open(scope, policy, emptySet())
            deferred.complete(location)
        }
        compose.waitForIdle()
        assertFalse(model.state.value.locationEnabled)
        assertNull(model.state.value.location)
    }
}
