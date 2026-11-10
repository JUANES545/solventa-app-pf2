package co.solventa.mobile.feature.assistance

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
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import co.solventa.mobile.core.designsystem.SolventaTheme
import java.util.UUID
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
class AssistanceLocationPermissionTest {
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
    private var locationCalls = 0
    private val model = AssistanceViewModel(FakeAssistanceGateway(), object : AssistanceLocator {
        override suspend fun current(): AssistanceLocation {
            locationCalls++
            return assistanceLocation
        }
    })

    @Before fun prepare() {
        shadowOf(compose.activity.application).denyPermissions(*permissions)
        compose.runOnUiThread { model.show("actor-a", UUID.randomUUID().toString()) }
        compose.setContent {
            SolventaTheme(false) {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) { AssistanceScreen(model) }
            }
        }
    }

    @Test fun blockedPermissionOffersSettingsAndRetainsManualAlternative() {
        assertTrue(launches.isEmpty())
        compose.onNodeWithTag("assistance-use-gps").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, launches.size)
        compose.runOnIdle { registry.dispatchResult(launches.single(), permissions.associateWith { false }) }
        compose.onNodeWithText("Abrir ajustes").assertIsDisplayed()
        compose.onNodeWithText("Cancelar").performClick()
        assertEquals(AssistanceStep.MANUAL, model.state.value.step)
        assertNull(model.state.value.location)
    }

    @Test fun approximateGrantLocatesOnceAfterExplicitChoice() {
        assertTrue(launches.isEmpty())
        assertEquals(0, locationCalls)
        compose.onNodeWithTag("assistance-use-gps").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, launches.size)
        compose.runOnIdle {
            shadowOf(compose.activity.application).grantPermissions(permissions[0])
            registry.dispatchResult(launches.single(), permissions.associateWith { it == permissions[0] })
        }
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            model.state.value.step == AssistanceStep.PROVIDERS && !model.state.value.busy
        }
        assertEquals(1, locationCalls)
        assertEquals(assistanceLocation, model.state.value.location)
        compose.runOnIdle {
            registry.dispatchResult(launches.single(), permissions.associateWith { it == permissions[0] })
        }
        assertEquals(1, locationCalls)
    }

    @Test fun existingPermissionDoesNotLocateUntilTheUserChoosesIt() {
        shadowOf(compose.activity.application).grantPermissions(permissions[0])
        assertEquals(0, locationCalls)
        compose.onNodeWithTag("assistance-use-gps").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            model.state.value.step == AssistanceStep.PROVIDERS && !model.state.value.busy
        }
        assertTrue(launches.isEmpty())
        assertEquals(1, locationCalls)
    }

    @Test fun manualChoiceDoesNotRequestOrClaimPermissionWasDenied() {
        shadowOf(compose.activity.application).grantPermissions(permissions[0])
        compose.onNodeWithTag("assistance-manual-option").performScrollTo().performClick()
        assertEquals(AssistanceStep.MANUAL, model.state.value.step)
        assertNull(model.state.value.error)
        assertTrue(launches.isEmpty())
        assertEquals(0, locationCalls)
    }

    @Test fun settingsReturnDoesNotLocateWithoutAnotherExplicitChoice() {
        compose.onNodeWithTag("assistance-use-gps").performScrollTo().performClick()
        compose.waitForIdle()
        compose.runOnIdle { registry.dispatchResult(launches.single(), permissions.associateWith { false }) }
        compose.onNodeWithText("Abrir ajustes").performClick()
        val intent = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package:${compose.activity.packageName}", intent.data.toString())
        compose.runOnIdle { shadowOf(compose.activity.application).grantPermissions(permissions[0]) }
        assertEquals(0, locationCalls)
        assertEquals(AssistanceStep.MANUAL, model.state.value.step)
        compose.runOnIdle { model.back() }
        compose.onNodeWithTag("assistance-use-gps").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            model.state.value.step == AssistanceStep.PROVIDERS && !model.state.value.busy
        }
        assertEquals(1, locationCalls)
        assertEquals(1, launches.size)
    }

    @Test fun latePermissionCannotLocateForAnotherClaimOrAccount() {
        compose.onNodeWithTag("assistance-use-gps").performScrollTo().performClick()
        compose.waitForIdle()
        val claim = UUID.randomUUID().toString()
        compose.runOnIdle {
            model.show("actor-b", claim)
            shadowOf(compose.activity.application).grantPermissions(permissions[0])
            registry.dispatchResult(launches.single(), permissions.associateWith { it == permissions[0] })
        }
        compose.waitForIdle()
        assertEquals("actor-b", model.state.value.actor)
        assertEquals(claim, model.state.value.claimId)
        assertEquals(AssistanceStep.LOCATION, model.state.value.step)
        assertEquals(0, locationCalls)
        assertNull(model.state.value.location)
    }
}
