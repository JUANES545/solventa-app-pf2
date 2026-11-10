package co.solventa.mobile.feature.evidence

import android.Manifest
import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.CameraSelector
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.ViewModelStore
import co.solventa.mobile.R
import co.solventa.mobile.core.designsystem.SolventaTheme
import co.solventa.mobile.feature.photo.LocalPhotoCameraBoundaryTest.CameraProviderShadow
import com.google.common.util.concurrent.Futures
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], shadows = [CameraProviderShadow::class])
class EvidenceCameraPermissionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = compose.activity.application
    private val model by lazy { EvidenceViewModel(app, MobileEvidenceApi(evidenceAuth(), "http://127.0.0.1:1")) }
    private val scope = EvidenceScope("actor-a", "claim-a")
    private val policy = EvidencePolicy(4, EvidenceKind.entries.associateWith { 1_000_000L }, 45_000)
    private data class Launch(val code: Int, val contract: ActivityResultContract<*, *>, val input: Any?)
    private val launches = mutableListOf<Launch>()
    private val registry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>,
            input: I, options: ActivityOptionsCompat?) {
            launches.add(Launch(requestCode, contract, input))
        }
    }
    private val owner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = registry
    }
    private val provider = mock(ProcessCameraProvider::class.java)
    private fun text(id: Int) = app.getString(id)
    private fun await() = compose.waitUntil(10_000) {
        shadowOf(Looper.getMainLooper()).idle()
        !model.state.value.busy
    }

    @Before fun prepare() {
        shadowOf(app).denyPermissions(Manifest.permission.CAMERA)
        shadowOf(compose.activity.packageManager).setShouldShowRequestPermissionRationale(Manifest.permission.CAMERA, true)
        `when`(provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)).thenReturn(true)
        `when`(provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)).thenReturn(true)
        CameraProviderShadow.future = Futures.immediateFuture(provider)
        compose.runOnUiThread { model.open(scope, policy, emptySet()) }
        compose.setContent {
            SolventaTheme(false) {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) { EvidenceHost(model) }
            }
        }
        await()
    }

    @After fun clear() {
        compose.runOnIdle { ViewModelStore().apply { put("evidence", model); clear() } }
    }

    private fun choose(kind: EvidenceKind, camera: Boolean = true) {
        compose.onNodeWithTag(when (kind) {
            EvidenceKind.PHOTO -> "evidence-add-photo"
            EvidenceKind.VIDEO -> "evidence-add-video"
            EvidenceKind.DOCUMENT -> "evidence-add-document"
        }).performScrollTo().performClick()
        compose.onNodeWithTag(if (camera) "evidence-source-camera" else "evidence-source-file").performClick()
        compose.waitForIdle()
    }

    @Test fun grantingCameraContinuesTheChosenPhotoVideoOrDocumentWithoutStartingCapture() {
        for (kind in EvidenceKind.entries) {
            compose.runOnIdle { shadowOf(app).denyPermissions(Manifest.permission.CAMERA) }
            choose(kind)
            assertTrue(launches.last().contract is ActivityResultContracts.RequestMultiplePermissions)
            assertEquals(listOf(Manifest.permission.CAMERA), (launches.last().input as Array<*>).toList())
            assertNull(model.state.value.draft)
            compose.runOnIdle {
                shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
                registry.dispatchResult(launches.last().code, mapOf(Manifest.permission.CAMERA to true))
            }
            compose.onNodeWithText(text(R.string.evidence_take)).performScrollTo().assertIsEnabled()
            compose.runOnIdle {
                assertEquals(kind, model.state.value.kind)
                assertEquals(EvidenceStep.CAMERA, model.state.value.step)
                assertNull(model.state.value.draft)
                assertEquals(kind, checkNotNull(model.beginCapture()).kind)
                model.back()
            }
        }
    }

    @Test fun alreadyGrantedCameraDoesNotRequestAgain() {
        compose.runOnIdle { shadowOf(app).grantPermissions(Manifest.permission.CAMERA) }
        choose(EvidenceKind.DOCUMENT)
        compose.onNodeWithText(text(R.string.evidence_take)).performScrollTo().assertIsEnabled()
        assertTrue(launches.isEmpty())
        assertEquals(EvidenceKind.DOCUMENT, model.state.value.kind)
        assertNull(model.state.value.draft)
    }

    @Test fun switchChangesBetweenFrontAndBackWithoutTakingAPhotoAndLocksDuringCapture() {
        compose.runOnIdle { shadowOf(app).grantPermissions(Manifest.permission.CAMERA) }
        choose(EvidenceKind.PHOTO)
        compose.onNodeWithContentDescription("Usar cámara frontal").assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("Usar cámara trasera").assertIsEnabled().performClick()
        compose.runOnIdle {
            val selectors = mockingDetails(provider).invocations.filter { it.method.name == "bindToLifecycle" }
                .map { it.arguments[1] }
            assertEquals(listOf(CameraSelector.DEFAULT_BACK_CAMERA, CameraSelector.DEFAULT_FRONT_CAMERA,
                CameraSelector.DEFAULT_BACK_CAMERA), selectors)
            assertNull(model.state.value.draft)
            assertNotNull(model.beginCapture())
        }
        compose.onNodeWithContentDescription("Usar cámara frontal").assertIsNotEnabled()
    }

    @Test fun rejectingCameraKeepsFilesAndReturnsWithoutACaptureError() {
        choose(EvidenceKind.VIDEO)
        compose.runOnIdle { registry.dispatchResult(launches.single().code, mapOf(Manifest.permission.CAMERA to false)) }
        compose.onNodeWithText(text(R.string.camera_permission_needed)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.camera_permission_close)).performClick()
        assertEquals(EvidenceStep.LIST, model.state.value.step)
        assertNull(model.state.value.error)
        assertNull(model.state.value.draft)
        assertTrue(model.state.value.items.isEmpty())
        assertEquals(1, launches.size)
    }

    @Test fun filePickersForAllKindsNeverRequestCamera() {
        for (kind in EvidenceKind.entries) {
            choose(kind, camera = false)
            assertTrue(launches.last().contract is ActivityResultContracts.GetContent)
            assertEquals(kind.mime, launches.last().input)
            compose.runOnIdle { registry.dispatchResult(launches.last().code, null) }
            assertEquals(EvidenceStep.LIST, model.state.value.step)
            assertNull(model.state.value.draft)
        }
        assertEquals(3, launches.size)
    }

    @Test fun latePermissionAfterChangingActorOrClaimDoesNotOpenCamera() {
        choose(EvidenceKind.PHOTO)
        val old = launches.single().code
        compose.runOnIdle { model.open(scope.copy(actorId = "actor-b", claimId = "claim-b"), policy, emptySet()) }
        await()
        compose.runOnIdle {
            shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
            registry.dispatchResult(old, mapOf(Manifest.permission.CAMERA to true))
        }
        assertEquals(EvidenceStep.LIST, model.state.value.step)
        assertEquals("actor-b", model.state.value.scope?.actorId)
        assertNull(model.state.value.draft)
        compose.onNodeWithText(text(R.string.evidence_take)).assertDoesNotExist()
    }

    @Test fun unavailableCameraStillShowsARealCaptureError() {
        CameraProviderShadow.future = Futures.immediateFailedFuture(IllegalStateException("Camera unavailable"))
        compose.runOnIdle { shadowOf(app).grantPermissions(Manifest.permission.CAMERA) }
        choose(EvidenceKind.PHOTO)
        compose.onNodeWithText(text(R.string.evidence_error_camera)).assertExists()
        assertEquals(EvidenceError.CAMERA, model.state.value.error)
        assertEquals(EvidenceStep.LIST, model.state.value.step)
        assertNull(model.state.value.draft)
    }
}
