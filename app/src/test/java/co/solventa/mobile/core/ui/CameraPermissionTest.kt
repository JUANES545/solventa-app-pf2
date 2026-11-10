package co.solventa.mobile.core.ui

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
class CameraPermissionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val launches = mutableListOf<Pair<Int, String>>()
    private val registry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>,
            input: I, options: ActivityOptionsCompat?) {
            launches.add(requestCode to (input as Array<*>).single().toString())
        }
    }
    private val owner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = registry
    }
    private val visible = mutableStateOf(true)
    private val request = mutableStateOf("actor-a/claim-a/photo/1")
    private val granted = mutableListOf<String>()
    private var cancelled = 0
    private fun text(id: Int) = compose.activity.getString(id)

    @Before fun prepare() {
        shadowOf(compose.activity.application).denyPermissions(Manifest.permission.CAMERA)
    }

    private fun show() {
        compose.setContent {
            SolventaTheme(false) {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    if (visible.value) {
                        val current = request.value
                        CameraPermission(current, onGranted = { granted.add(current) },
                            onCancel = { cancelled++; visible.value = false })
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun result(allowed: Boolean, requestCode: Int = launches.last().first) {
        compose.runOnIdle {
            if (allowed) shadowOf(compose.activity.application).grantPermissions(Manifest.permission.CAMERA)
            registry.dispatchResult(requestCode, mapOf(Manifest.permission.CAMERA to allowed))
        }
    }

    @Test fun firstRequestLaunchesOnlyCameraAndGrantContinuesOnce() {
        show()
        assertEquals(listOf(Manifest.permission.CAMERA), launches.map { it.second })
        assertTrue(granted.isEmpty())
        result(true)
        assertEquals(listOf(request.value), granted)
        result(true)
        assertEquals(1, granted.size)
    }

    @Test fun alreadyGrantedContinuesWithoutPrompt() {
        shadowOf(compose.activity.application).grantPermissions(Manifest.permission.CAMERA)
        show()
        assertTrue(launches.isEmpty())
        assertEquals(listOf(request.value), granted)
    }

    @Test fun rejectionShowsShortMessageWithoutCaptureOrSettingsAndCancelDoesNotRetry() {
        shadowOf(compose.activity.packageManager).setShouldShowRequestPermissionRationale(Manifest.permission.CAMERA, true)
        show()
        result(false)
        compose.onNodeWithText(text(R.string.camera_permission_needed)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.camera_open_settings)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.camera_permission_close)).performClick()
        assertEquals(1, cancelled)
        assertTrue(granted.isEmpty())
        assertEquals(1, launches.size)
    }

    @Test fun permanentDenialOffersSettingsAndReturningDoesNotCapture() {
        show()
        result(false)
        compose.onNodeWithText(text(R.string.camera_permission_settings)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.camera_open_settings)).performClick()
        val intent = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package:${compose.activity.packageName}", intent.data.toString())
        assertEquals(1, cancelled)
        shadowOf(compose.activity.application).grantPermissions(Manifest.permission.CAMERA)
        compose.waitForIdle()
        assertTrue(granted.isEmpty())
    }

    @Test fun leavingWhileSystemPromptIsOpenIgnoresLateGrant() {
        show()
        val old = launches.single().first
        compose.runOnIdle { visible.value = false }
        result(true, old)
        assertTrue(granted.isEmpty())
        assertEquals(0, cancelled)
    }

    @Test fun dismissingTheSystemPromptCancelsWithoutOfferingSettings() {
        show()
        compose.runOnIdle { registry.dispatchResult(launches.single().first, emptyMap<String, Boolean>()) }
        assertEquals(1, cancelled)
        assertTrue(granted.isEmpty())
        compose.onNodeWithText(text(R.string.camera_open_settings)).assertDoesNotExist()
    }

    @Test fun changingActorClaimOrKindNeverConsumesThePreviousGrant() {
        shadowOf(compose.activity.packageManager).setShouldShowRequestPermissionRationale(Manifest.permission.CAMERA, true)
        show()
        for (next in listOf("actor-a/claim-b/photo/2", "actor-b/claim-b/photo/3", "actor-b/claim-b/video/4")) {
            val old = launches.last().first
            compose.runOnIdle { request.value = next }
            result(true, old)
            assertTrue(granted.isEmpty())
            compose.runOnIdle { shadowOf(compose.activity.application).denyPermissions(Manifest.permission.CAMERA) }
        }
        result(true)
        assertEquals(listOf("actor-b/claim-b/video/4"), granted)
    }
}
