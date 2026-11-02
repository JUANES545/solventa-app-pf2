package co.solventa.mobile.feature.photo

import android.app.Application
import android.graphics.Bitmap
import co.solventa.mobile.R
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedConstruction
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class PhotoStorageBoundaryTest {
    private lateinit var construction: MockedConstruction<PhotoFiles>
    private val app get() = RuntimeEnvironment.getApplication()
    private val file get() = File(app.cacheDir, "photo-review/synthetic.jpg")
    private val bitmap by lazy { Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888) }
    private lateinit var files: PhotoFiles
    private lateinit var model: PhotoReviewViewModel

    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        construction = mockConstruction(PhotoFiles::class.java) { mock, _ ->
            files = mock
            `when`(mock.create()).thenReturn(file)
            `when`(mock.preview(file)).thenReturn(bitmap)
        }
        model = PhotoReviewViewModel(app)
    }

    @After fun tearDown() {
        model.close()
        construction.close()
        Dispatchers.resetMain()
    }

    private fun capture(): PhotoCapture {
        model.open()
        model.startCamera()
        model.permissionResult(model.state.value.generation, true)
        return requireNotNull(model.beginCapture())
    }

    @Test fun createFailuresReportStorageAndNeverLeaveCaptureInProgress() {
        for (error in listOf(IOException("full"), SecurityException("denied"))) {
            doAnswer { throw error }.`when`(files).create()
            model.open()
            model.startCamera()
            model.permissionResult(model.state.value.generation, true)
            assertNull(model.beginCapture())
            assertEquals(PhotoStep.INTRO, model.state.value.step)
            assertEquals(R.string.photo_storage, model.state.value.error)
        }
    }

    @Test fun readFailuresDiscardCandidateWithoutShowingPreview() = runTest {
        for ((error, expected) in listOf(
            IOException("unreadable") to R.string.photo_invalid,
            SecurityException("denied") to R.string.photo_storage,
        )) {
            doAnswer { throw error }.`when`(files).preview(file)
            val request = capture()
            model.captured(request)
            val state = model.state.first { it.error != null }
            assertEquals(expected, state.error)
            assertNull(state.preview)
            assertFalse(state.documentReceived)
        }
    }

    @Test fun acceptanceRevalidatesTheFileAndDoesNotConfirmStorageErrors() = runTest {
        for (error in listOf(IOException("unreadable"), SecurityException("denied"))) {
            doReturn(bitmap).`when`(files).preview(file)
            val request = capture()
            model.captured(request)
            model.state.first { it.step == PhotoStep.REVIEW }
            doAnswer { throw error }.`when`(files).preview(file)
            model.accept()
            val state = model.state.first { it.error != null }
            assertEquals(R.string.photo_storage, state.error)
            assertFalse(state.documentReceived)
            assertFalse(state.selfieReceived)
        }
    }

    @Test fun cleanupFailureAndLateCaptureRemainErrorsNotSuccess() {
        val request = capture()
        model.close()
        for (error in listOf(IOException("unavailable"), SecurityException("denied"))) {
            doAnswer { throw error }.`when`(files).remove(file)
            model.captured(request)
            assertFalse(model.state.value.open)
            assertNull(model.state.value.preview)
            model.cameraFailed(request.generation)
            model.cameraFailed(request.generation, request = request)
        }
        doThrow(SecurityException("denied")).`when`(files).clear()
        model.open()
        model.startCamera()
        assertEquals(R.string.photo_storage, model.state.value.error)
        assertEquals(PhotoStep.INTRO, model.state.value.step)
        androidx.lifecycle.ViewModelStore().apply { put("photo", model); clear() }
        assertFalse(model.state.value.open)
        assertEquals(R.string.photo_storage, model.state.value.error)
    }

    @Test fun duplicatePermissionAndCaptureEventsDoNotRestartCompletedReview() = runTest {
        model.startCamera()
        assertFalse(model.state.value.open)
        model.pause()
        val request = capture()
        model.permissionResult(request.generation, false)
        assertEquals(PhotoStep.CAPTURING, model.state.value.step)
        model.captured(request)
        model.state.first { it.step == PhotoStep.REVIEW }
        model.captured(request)
        assertEquals(PhotoStep.REVIEW, model.state.value.step)
        model.accept()
        model.accept()
        model.state.first { it.kind == PhotoKind.SELFIE }
        assertTrue(model.state.value.documentReceived)
        verify(files, times(2)).preview(file)
    }
}
