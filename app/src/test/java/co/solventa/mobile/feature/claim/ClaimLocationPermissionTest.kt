package co.solventa.mobile.feature.claim

import android.Manifest
import android.app.Application
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import co.solventa.mobile.R
import co.solventa.mobile.core.designsystem.SolventaTheme
import co.solventa.mobile.core.location.LocationPermission
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
class ClaimLocationPermissionTest {
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
    private val visible = mutableStateOf(false)
    private val request = mutableStateOf(0)
    private var granted = 0
    private var cancelled = 0

    @Before fun prepare() { shadowOf(compose.activity.application).denyPermissions(*permissions) }

    private fun show() {
        compose.setContent {
            SolventaTheme(false) {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    if (visible.value) key(request.value) {
                        LocationPermission({ granted++ }, { cancelled++; visible.value = false })
                    }
                }
            }
        }
        compose.runOnIdle { assertTrue(launches.isEmpty()); visible.value = true }
        compose.waitForIdle()
    }

    private fun result(grant: String? = null, code: Int = launches.last()) {
        compose.runOnIdle {
            if (grant != null) shadowOf(compose.activity.application).grantPermissions(grant)
            registry.dispatchResult(code, permissions.associateWith { it == grant })
        }
        compose.waitForIdle()
    }

    @Test fun initialRequestAndApproximateGrantContinueExactlyOnce() {
        show()
        assertEquals(1, launches.size)
        result(permissions[0])
        result(permissions[0])
        assertEquals(1, granted)
        assertEquals(0, cancelled)
    }

    @Test fun preciseGrantContinuesExactlyOnce() {
        show()
        result(permissions[1])
        result(permissions[1])
        assertEquals(1, granted)
    }

    @Test fun alreadyGrantedDoesNotLaunchPrompt() {
        shadowOf(compose.activity.application).grantPermissions(permissions[0])
        show()
        assertEquals(1, granted)
        assertTrue(launches.isEmpty())
    }

    @Test fun rejectionAllowsManualEntryAndDoesNotRetry() {
        shadowOf(compose.activity.packageManager).setShouldShowRequestPermissionRationale(permissions[0], true)
        show()
        result()
        compose.onNodeWithText(compose.activity.getString(R.string.location_permission_denied)).assertIsDisplayed()
        compose.onNodeWithText("Abrir ajustes").assertDoesNotExist()
        compose.onNodeWithText("Cancelar").performClick()
        assertEquals(1, cancelled)
        assertEquals(0, granted)
        assertEquals(1, launches.size)
    }

    @Test fun permanentDenialOffersSettingsWithoutLoopOrAutomaticLocationOnReturn() {
        show()
        result()
        compose.onNodeWithText("Abrir ajustes").performClick()
        val intent = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package:${compose.activity.packageName}", intent.data.toString())
        compose.runOnIdle { shadowOf(compose.activity.application).grantPermissions(permissions[0]) }
        assertEquals(0, granted)
        assertEquals(1, cancelled)
        assertEquals(1, launches.size)
    }

    @Test fun cancellingSystemPromptDoesNotInventPermanentDenial() {
        show()
        compose.runOnIdle { registry.dispatchResult(launches.single(), emptyMap<String, Boolean>()) }
        assertEquals(1, cancelled)
        assertEquals(0, granted)
        compose.onNodeWithText("Abrir ajustes").assertDoesNotExist()
    }

    @Test fun disposedRequestIgnoresLateGrant() {
        show()
        val code = launches.single()
        compose.runOnIdle { visible.value = false }
        result(permissions[0], code)
        assertEquals(0, granted)
    }

    @Test fun changedContextIgnoresPreviousPermissionResult() {
        show()
        val code = launches.single()
        compose.runOnIdle { request.value++ }
        result(permissions[0], code)
        assertEquals(0, granted)
        assertEquals(2, launches.size)
        result(permissions[0])
        assertEquals(1, granted)
    }

    @Test fun fieldRequestsOnlyOnTapAndContinuesOneSearchAfterApproximateGrant() {
        val state = mutableStateOf(ClaimState())
        var searches = 0
        compose.setContent {
            SolventaTheme(false) {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    ClaimLocationField(state.value, { state.value = state.value.copy(draft = it) }, { fail(it) },
                        readState = { state.value }, address = { "Calle sintética 10" }) {
                        searches++
                        co.solventa.mobile.core.location.DeviceLocation(4.5, -74.1, java.time.Instant.now())
                    }
                }
            }
        }
        compose.runOnIdle { assertTrue(launches.isEmpty()); assertEquals(0, searches) }
        compose.onNodeWithTag("claim-current-location").performClick()
        compose.runOnIdle { assertEquals(1, launches.size); assertEquals(0, searches) }
        val code = launches.single()
        result(permissions[0], code)
        compose.waitUntil { state.value.draft.place.isNotBlank() }
        result(permissions[0], code)
        assertEquals(1, searches)
        assertEquals("Calle sintética 10", state.value.draft.place)
    }
}
