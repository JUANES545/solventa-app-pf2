package co.solventa.mobile.feature.photo

import android.app.Application
import android.media.ExifInterface
import android.graphics.Bitmap
import android.os.Looper
import co.solventa.mobile.R
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class PhotoReviewTest {
    private val app = RuntimeEnvironment.getApplication()
    private val model = PhotoReviewViewModel(app)
    private val files = PhotoFiles(app.cacheDir)

    @After fun cleanup() { model.close() }

    @Test fun documentAndSelfieSelectDifferentLensesWithoutFallback() {
        assertSame(androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA, photoCameraSelector(PhotoKind.DOCUMENT))
        assertSame(androidx.camera.core.CameraSelector.DEFAULT_FRONT_CAMERA, photoCameraSelector(PhotoKind.SELFIE))
        val guide = app.getString(R.string.photo_document_guide)
        assertTrue(guide.contains("documento"))
        assertFalse(guide.contains("frontal") || guide.contains("delantera"))
        assertTrue(app.getString(R.string.photo_no_document_camera).contains("trasera"))
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(50, TimeUnit.MILLISECONDS)
            Thread.sleep(10)
        }
        assertTrue("State did not settle: ${model.state.value.step}", condition())
    }

    private fun capture(open: Boolean = true): PhotoCapture {
        if (open) model.open()
        model.startCamera()
        model.permissionResult(model.state.value.generation, true)
        return requireNotNull(model.beginCapture())
    }

    // Generated JPEGs exercise decoding and state, not camera hardware or liveness.
    private fun jpeg(file: File, width: Int = 64, height: Int = 48) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
        bitmap.recycle()
    }

    private fun reviewed(open: Boolean = true): PhotoCapture = capture(open).also {
        jpeg(it.file)
        model.captured(it)
        await { model.state.value.step == PhotoStep.REVIEW || model.state.value.error != null }
        assertEquals(PhotoStep.REVIEW, model.state.value.step)
    }

    @Test fun cannotAdvanceWithoutCaptureOrPermission() {
        model.open()
        model.accept()
        assertNull(model.beginCapture())
        assertEquals(PhotoStep.INTRO, model.state.value.step)
        model.startCamera()
        model.permissionResult(model.state.value.generation, false)
        assertEquals(R.string.photo_denied, model.state.value.error)
        assertEquals(PhotoStep.INTRO, model.state.value.step)
        assertNull(model.state.value.preview)
        model.accept()
        assertNotEquals(PhotoStep.COMPLETE, model.state.value.step)
    }

    @Test fun staleOrRepeatedAutoCaptureCannotCreateAnotherFile() {
        val first = capture()
        assertNull(model.beginCapture(first.generation))
        model.close()
        model.open()
        model.startCamera()
        model.permissionResult(model.state.value.generation, true)
        assertNull(model.beginCapture(first.generation))
        assertEquals(PhotoStep.CAMERA, model.state.value.step)
        val current = requireNotNull(model.beginCapture(model.state.value.generation))
        assertNull(model.beginCapture(current.generation))
        assertEquals(1, File(app.cacheDir, "photo-review").listFiles().orEmpty().size)
    }

    @Test fun validFilePreviewAcceptProgressAndCleanup() {
        val request = reviewed()
        assertTrue(request.file.exists())
        assertNotNull(model.state.value.preview)
        model.accept()
        assertEquals(PhotoStep.PROCESSING, model.state.value.step)
        await { model.state.value.documentReceived && model.state.value.step == PhotoStep.INTRO }
        assertFalse(request.file.exists())
        assertNull(model.state.value.preview)
        assertFalse(model.state.value.selfieReceived)
        assertEquals(PhotoKind.SELFIE, model.state.value.kind)
        assertNotEquals(PhotoStep.COMPLETE, model.state.value.step)
        val selfie = reviewed(open = false)
        model.accept()
        await { model.state.value.step == PhotoStep.COMPLETE }
        assertFalse(selfie.file.exists())
        assertTrue(model.state.value.documentReceived)
        assertTrue(model.state.value.selfieReceived)
        assertTrue(model.state.value.open)
        model.close()
        assertFalse(model.state.value.open)
    }

    @Test fun stableSelfieDecisionCreatesOneFileButStillRequiresUserAcceptance() {
        reviewed()
        model.accept()
        await { model.state.value.documentReceived && model.state.value.step == PhotoStep.INTRO }
        model.startCamera()
        val generation = model.state.value.generation
        model.permissionResult(generation, true)
        val gate = SelfieGuidance()
        val position = SelfiePosition(.5f, .5f, .8f, .8f, 0f, 0f, 0f)
        var request: PhotoCapture? = null
        for (time in 0L..1_800L step 180) {
            if (gate.update(1, position, time, time, time + 1, true) == SelfieHint.CAPTURING) {
                assertNull(request)
                request = requireNotNull(model.beginCapture(generation))
            }
        }
        val selfie = requireNotNull(request)
        assertEquals(1, File(app.cacheDir, "photo-review").listFiles().orEmpty().size)
        jpeg(selfie.file)
        model.captured(selfie)
        await { model.state.value.step == PhotoStep.REVIEW }
        assertFalse(model.state.value.selfieReceived)
        assertTrue(selfie.file.exists())
        assertNull(model.beginCapture(generation))
        model.accept()
        await { model.state.value.step == PhotoStep.COMPLETE }
        assertFalse(selfie.file.exists())
    }

    @Test fun restartingOrBackgroundAfterDocumentDiscardsBothReceipts() {
        reviewed()
        model.accept()
        await { model.state.value.documentReceived && model.state.value.step == PhotoStep.INTRO }
        model.pause()
        assertFalse(model.state.value.documentReceived)
        assertFalse(model.state.value.selfieReceived)
        assertEquals(PhotoKind.DOCUMENT, model.state.value.kind)
        reviewed()
        model.accept()
        await { model.state.value.documentReceived && model.state.value.step == PhotoStep.INTRO }
        val stale = reviewed(open = false)
        model.open()
        model.captured(stale)
        assertFalse(stale.file.exists())
        assertFalse(model.state.value.documentReceived)
        assertFalse(model.state.value.selfieReceived)
        assertEquals(PhotoKind.DOCUMENT, model.state.value.kind)
    }

    @Test fun invalidSelfieOrCleanupFailureCannotCompleteTheAttempt() {
        reviewed()
        model.accept()
        await { model.state.value.documentReceived && model.state.value.step == PhotoStep.INTRO }
        val selfie = reviewed(open = false)
        selfie.file.writeText("invalid")
        model.accept()
        await { model.state.value.error != null }
        assertFalse(model.state.value.documentReceived)
        assertFalse(model.state.value.selfieReceived)
        assertNotEquals(PhotoStep.COMPLETE, model.state.value.step)
        val valid = reviewed()
        val obstruction = File(app.cacheDir, "photo-review/obstruction")
        check(obstruction.mkdir())
        try {
            model.accept()
            await { model.state.value.error != null }
            assertEquals(R.string.photo_storage, model.state.value.error)
            assertFalse(model.state.value.documentReceived)
            assertFalse(model.state.value.selfieReceived)
            assertNotEquals(PhotoStep.COMPLETE, model.state.value.step)
        } finally {
            check(obstruction.delete())
        }
        assertFalse(valid.file.exists())
    }

    @Test fun repeatAndCancelDeleteThePreviousFile() {
        val request = reviewed()
        model.startCamera()
        assertFalse(request.file.exists())
        assertEquals(PhotoStep.PERMISSION, model.state.value.step)
        assertNull(model.state.value.preview)
        model.close()
        assertFalse(model.state.value.open)
        assertNotEquals(PhotoStep.COMPLETE, model.state.value.step)
    }

    @Test fun emptyCorruptOrOversizedCaptureNeverCompletes() {
        repeat(3) { kind ->
            val request = capture()
            when (kind) {
                1 -> request.file.writeText("not an image")
                2 -> RandomAccessFile(request.file, "rw").use { it.setLength(PhotoFiles.MAX_BYTES + 1) }
            }
            model.captured(request)
            await { model.state.value.error != null }
            assertEquals(R.string.photo_invalid, model.state.value.error)
            assertEquals(PhotoStep.INTRO, model.state.value.step)
            assertFalse(request.file.exists())
        }
    }

    @Test fun cameraFailureHasNoSuccessAndDeletesCandidate() {
        val request = capture()
        jpeg(request.file)
        model.cameraFailed(request.generation, R.string.photo_no_camera, request)
        assertEquals(R.string.photo_no_camera, model.state.value.error)
        assertFalse(request.file.exists())
        assertNull(model.state.value.preview)
        assertEquals(PhotoStep.INTRO, model.state.value.step)
    }

    @Test fun lateCaptureAfterAccountChangeCannotPublishOrDeleteTheNewCandidate() {
        model.bindActor("account-a")
        val old = capture()
        model.bindActor("account-b")
        val next = capture()
        jpeg(old.file)
        model.captured(old)
        assertFalse(old.file.exists())
        assertTrue(next.file.exists())
        assertEquals("account-b", model.state.value.actorId)
        assertEquals(PhotoStep.CAPTURING, model.state.value.step)
        assertNull(model.state.value.error)
        model.cameraFailed(old.generation, request = old)
        assertEquals(PhotoStep.CAPTURING, model.state.value.step)
    }

    @Test fun cancelledOrExpiredPermissionCannotOpenCameraInTheNewContext() {
        model.open()
        model.startCamera()
        val generation = model.state.value.generation
        model.bindActor("another-account")
        model.open()
        model.permissionResult(generation, true)
        assertEquals(PhotoStep.INTRO, model.state.value.step)
        assertNull(model.beginCapture())
    }

    @Test fun logoutOrBackgroundDiscardsReviewAndCancelsProcessing() {
        model.bindActor("account-a")
        val request = reviewed()
        model.accept()
        model.bindActor(null)
        await { !model.state.value.open && !request.file.exists() }
        assertFalse(request.file.exists())
        assertNull(model.state.value.preview)
        assertNotEquals(PhotoStep.COMPLETE, model.state.value.step)
        val other = reviewed()
        model.pause()
        assertFalse(other.file.exists())
        assertNull(model.state.value.preview)
        assertEquals(R.string.photo_interrupted, model.state.value.error)
    }

    @Test fun fileChangedAfterPreviewCannotBeAccepted() {
        val request = reviewed()
        request.file.writeText("invalidated")
        model.accept()
        await { model.state.value.error != null }
        assertEquals(R.string.photo_invalid, model.state.value.error)
        assertFalse(request.file.exists())
        assertNotEquals(PhotoStep.COMPLETE, model.state.value.step)
    }

    @Test fun processRestartRemovesOrphanFilesWithoutRestoringCompletion() {
        val orphan = files.create()
        jpeg(orphan)
        val restarted = PhotoReviewViewModel(app)
        assertFalse(orphan.exists())
        assertFalse(restarted.state.value.open)
        assertNull(restarted.state.value.preview)
        assertEquals(PhotoStep.INTRO, restarted.state.value.step)
    }

    @Test fun cleanupFailureBlocksOpeningCameraAndShowsStorageError() {
        val directory = File(app.cacheDir, "photo-review")
        check(directory.isDirectory || directory.mkdirs())
        val obstruction = File(directory, "obstruction")
        check(obstruction.mkdir())
        try {
            model.open()
            model.startCamera()
            assertEquals(R.string.photo_storage, model.state.value.error)
            assertEquals(PhotoStep.INTRO, model.state.value.step)
            assertNull(model.beginCapture())
        } finally { check(obstruction.delete()) }
    }

    @Test fun previewIsBoundedAndUsesExifRotation() {
        val file = files.create()
        jpeg(file, 2048, 1536)
        ExifInterface(file.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val bitmap = files.preview(file)
        assertTrue(maxOf(bitmap.width, bitmap.height) <= 1024)
        assertEquals(768, bitmap.width)
        assertEquals(1024, bitmap.height)
    }
}
