package co.solventa.mobile.feature.photo

import android.Manifest
import android.app.Application
import android.graphics.RectF
import android.graphics.Rect
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.camera.core.*
import androidx.camera.view.PreviewView
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.MutableLiveData
import co.solventa.mobile.R
import co.solventa.mobile.core.designsystem.SolventaTheme
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedConstruction
import org.mockito.MockedStatic
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], shadows = [LocalPhotoCameraBoundaryTest.CameraProviderShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocalPhotoCameraBoundaryTest {
    // Keep Mockito's nullable matcher token at the Java boundary, not Kotlin's argument check.
    private fun <T> any(type: Class<T>): T = org.mockito.ArgumentMatchers.any(type)
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = RuntimeEnvironment.getApplication()
    private val model by lazy { PhotoReviewViewModel(app) }
    private val provider = mock(ProcessCameraProvider::class.java)
    private val camera = mock(Camera::class.java)
    private val cameraInfo = mock(CameraInfo::class.java)
    private val cameraState = MutableLiveData(CameraState.create(CameraState.Type.OPEN))
    private lateinit var detectors: MockedStatic<FaceDetection>
    private lateinit var captures: MockedConstruction<ImageCapture>
    private lateinit var documents: MockedConstruction<DocumentFrameAnalyzer>
    private lateinit var selfies: MockedConstruction<SelfieAnalyzer>
    private lateinit var onBounds: (RectF?) -> Unit
    private lateinit var onHint: (SelfieHint, RectF?) -> Unit
    private lateinit var onCapture: () -> Unit
    private lateinit var onAnalysisError: () -> Unit
    private lateinit var analysisActive: () -> Boolean
    private val visible = mutableStateOf(true)
    private val owner = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }
    private val future: ListenableFuture<ProcessCameraProvider> get() = Futures.immediateFuture(provider)

    @Suppress("UNCHECKED_CAST")
    @Before fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
        `when`(provider.hasCamera(any(CameraSelector::class.java))).thenReturn(true)
        `when`(provider.bindToLifecycle(any(LifecycleOwner::class.java), any(CameraSelector::class.java),
            any(UseCaseGroup::class.java))).thenReturn(camera)
        `when`(camera.cameraInfo).thenReturn(cameraInfo)
        `when`(cameraInfo.cameraState).thenReturn(cameraState)
        CameraProviderShadow.future = future
        detectors = mockStatic(FaceDetection::class.java)
        detectors.`when`<FaceDetector> { FaceDetection.getClient(any(FaceDetectorOptions::class.java)) }
            .thenReturn(mock(FaceDetector::class.java))
        captures = mockConstruction(ImageCapture::class.java)
        documents = mockConstruction(DocumentFrameAnalyzer::class.java) { _, context ->
            analysisActive = context.arguments()[2] as () -> Boolean
            onBounds = context.arguments()[3] as (RectF?) -> Unit
            onAnalysisError = context.arguments()[4] as () -> Unit
        }
        selfies = mockConstruction(SelfieAnalyzer::class.java) { _, context ->
            analysisActive = context.arguments()[4] as () -> Boolean
            onHint = context.arguments()[5] as (SelfieHint, RectF?) -> Unit
            onCapture = context.arguments()[6] as () -> Unit
            onAnalysisError = context.arguments()[7] as () -> Unit
        }
        model.open()
        model.startCamera()
        model.permissionResult(model.state.value.generation, true)
    }

    @After fun tearDown() {
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        model.close()
        if (::selfies.isInitialized) selfies.close()
        if (::documents.isInitialized) documents.close()
        if (::captures.isInitialized) captures.close()
        if (::detectors.isInitialized) detectors.close()
    }

    private fun open(kind: PhotoKind = PhotoKind.DOCUMENT, followState: Boolean = true) {
        val initial = model.state.value
        compose.setContent {
            val state by model.state.collectAsState()
            val displayed = if (followState) state else initial
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                SolventaTheme {
                    if (visible.value && displayed.step in listOf(PhotoStep.CAMERA, PhotoStep.CAPTURING)) {
                        LocalPhotoCamera(model, displayed.copy(kind = kind))
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun documentCameraRequiresBoundsAndOpenStateBeforeCapturingAndReleasesResources() {
        open()
        compose.onNodeWithText(app.getString(R.string.photo_take)).assertIsNotEnabled()
        compose.runOnIdle { onBounds(RectF(0f, 0f, 160f, 120f)) }
        compose.onNodeWithText(app.getString(R.string.photo_take)).assertIsEnabled().performClick()
        assertEquals(PhotoStep.CAPTURING, model.state.value.step)
        val callback = org.mockito.ArgumentCaptor.forClass(ImageCapture.OnImageSavedCallback::class.java)
        verify(captures.constructed().single()).takePicture(any(ImageCapture.OutputFileOptions::class.java),
            any(Executor::class.java), callback.capture())
        compose.runOnIdle { callback.value.onError(ImageCaptureException(ImageCapture.ERROR_FILE_IO, "synthetic", null)) }
        assertEquals(R.string.photo_camera_error, model.state.value.error)
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        verify(provider, atLeastOnce()).unbind(any(Preview::class.java), any(ImageCapture::class.java))
    }

    @Test fun missingCameraAndProviderFailureProduceRecoverableErrors() {
        `when`(provider.hasCamera(any(CameraSelector::class.java))).thenReturn(false)
        open()
        assertEquals(R.string.photo_no_document_camera, model.state.value.error)
        verify(provider, never()).bindToLifecycle(any(LifecycleOwner::class.java), any(CameraSelector::class.java),
            any(UseCaseGroup::class.java))
    }

    @Test fun failedProviderFutureDoesNotLeaveCaptureEnabled() {
        CameraProviderShadow.future = Futures.immediateFailedFuture(IllegalStateException("synthetic"))
        open()
        assertEquals(R.string.photo_camera_error, model.state.value.error)
        compose.onNodeWithText(app.getString(R.string.photo_take)).assertDoesNotExist()
    }

    @Implements(value = ProcessCameraProvider.Companion::class, isInAndroidSdk = false)
    class CameraProviderShadow {
        @Implementation
        fun getInstance(context: android.content.Context): ListenableFuture<ProcessCameraProvider> = future

        companion object {
            lateinit var future: ListenableFuture<ProcessCameraProvider>
        }
    }

    @Test fun deniedBindingReportsPermissionFailure() {
        `when`(provider.bindToLifecycle(any(LifecycleOwner::class.java), any(CameraSelector::class.java),
            any(UseCaseGroup::class.java))).thenThrow(SecurityException("denied"))
        open()
        assertEquals(R.string.photo_denied, model.state.value.error)
    }

    @Test fun cameraStateErrorAndAnalyzerFailureCannotSilentlyContinueCapture() {
        open()
        compose.runOnIdle { cameraState.value = CameraState.create(CameraState.Type.CLOSED,
            CameraState.StateError.create(CameraState.ERROR_CAMERA_FATAL_ERROR)) }
        assertEquals(R.string.photo_camera_error, model.state.value.error)
        compose.runOnIdle { onAnalysisError() }
        assertEquals(R.string.photo_camera_error, model.state.value.error)
    }

    @Test fun selfieHintsRenderAndAnalyzerErrorDoesNotBecomeACompletedPhoto() {
        open(PhotoKind.SELFIE)
        compose.runOnIdle { onHint(SelfieHint.HOLD, RectF(10f, 10f, 100f, 140f)) }
        compose.onNodeWithText(app.getString(R.string.selfie_hold)).assertExists()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            bitmap.recycle()
        }
        compose.runOnIdle { onHint(SelfieHint.CENTER, null) }
        compose.onNodeWithText(app.getString(R.string.selfie_center)).assertExists()
        compose.runOnIdle { onAnalysisError() }
        compose.waitForIdle()
        assertEquals(R.string.photo_guidance_error, model.state.value.error)
        assertFalse(model.state.value.selfieReceived)
    }

    @Test fun selfieTriggerCannotDuplicateCaptureOrAuthorizeAfterDisposal() {
        open(PhotoKind.SELFIE)
        compose.runOnIdle { onCapture(); onCapture() }
        assertEquals(PhotoStep.CAPTURING, model.state.value.step)
        verify(captures.constructed().single()).takePicture(any(ImageCapture.OutputFileOptions::class.java),
            any(Executor::class.java), any(ImageCapture.OnImageSavedCallback::class.java))
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        assertFalse(analysisActive())
        verify(selfies.constructed().single()).close()
    }

    private fun preview(): PreviewView {
        fun children(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(children(view.getChildAt(index)))
        }
        return children(compose.activity.window.decorView).filterIsInstance<PreviewView>().single()
    }

    private fun focus(value: Boolean) {
        val root = org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
            compose.activity.window.decorView, "getViewRootImpl")
        org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowViewRootImpl>(root)
            .callWindowFocusChanged(value)
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test fun selfieAnalysisRequiresVisibleFocusedUnclippedPermittedResumedPreview() {
        open(PhotoKind.SELFIE, followState = false)
        val view = preview()
        compose.runOnIdle {
            focus(true)
            shadowOf(view).setGlobalVisibleRect(Rect(0, 0, view.width, view.height))
            assertTrue(analysisActive())
            view.visibility = View.INVISIBLE
            assertFalse(analysisActive())
            view.visibility = View.VISIBLE
            focus(false)
            assertFalse(analysisActive())
            focus(true)
            shadowOf(view).setGlobalVisibleRect(Rect())
            assertFalse(analysisActive())
            shadowOf(view).setGlobalVisibleRect(Rect(0, 0, view.width - 3, view.height))
            assertFalse(analysisActive())
            shadowOf(view).setGlobalVisibleRect(Rect(0, 0, view.width, view.height - 3))
            assertFalse(analysisActive())
            shadowOf(view).setGlobalVisibleRect(Rect(0, 0, view.width, view.height))
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            assertFalse(analysisActive())
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
            shadowOf(app).denyPermissions(Manifest.permission.CAMERA)
            assertFalse(analysisActive())
            shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
            cameraState.value = CameraState.create(CameraState.Type.CLOSED)
            assertFalse(analysisActive())
            onCapture()
            assertEquals(PhotoStep.CAMERA, model.state.value.step)
            cameraState.value = CameraState.create(CameraState.Type.OPEN)
            assertTrue(analysisActive())
            requireNotNull(model.beginCapture())
            assertFalse(analysisActive())
            model.open()
            assertFalse(analysisActive())
            model.close()
            assertFalse(analysisActive())
        }
    }

    @Test fun documentAnalysisRejectsPauseClosedCameraAndStaleGeneration() {
        open(followState = false)
        compose.runOnIdle {
            assertTrue(analysisActive())
            onBounds(null)
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            assertFalse(analysisActive())
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
            cameraState.value = CameraState.create(CameraState.Type.CLOSED)
            assertFalse(analysisActive())
            cameraState.value = CameraState.create(CameraState.Type.OPEN)
            requireNotNull(model.beginCapture())
            assertTrue(analysisActive())
            model.open()
            assertFalse(analysisActive())
            model.close()
            assertFalse(analysisActive())
            visible.value = false
        }
        compose.waitForIdle()
        assertFalse(analysisActive())
    }

    @Test fun providerCompletingAfterDisposalDoesNotBindACamera() {
        val delayed = com.google.common.util.concurrent.SettableFuture.create<ProcessCameraProvider>()
        CameraProviderShadow.future = delayed
        open()
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle { delayed.set(provider) }
        compose.waitForIdle()
        verify(provider, never()).hasCamera(any(CameraSelector::class.java))
    }

    @Test fun everySelfieHintHasAnExplicitInstruction() {
        val expected = listOf(R.string.selfie_preparing, R.string.selfie_no_face, R.string.selfie_multiple,
            R.string.selfie_center, R.string.selfie_closer, R.string.selfie_farther, R.string.selfie_straight,
            R.string.selfie_hold, R.string.photo_taking)
        assertEquals(expected, SelfieHint.entries.map(::selfieHintMessage))
    }
}
