package co.solventa.mobile.feature.photo

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Size
import androidx.camera.view.TransformExperimental
import androidx.camera.view.transform.OutputTransform
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetector
import java.lang.reflect.Proxy
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@androidx.annotation.OptIn(TransformExperimental::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SelfieAnalyzerTest {
    companion object { private var sdkInitialized = false }
    @Before fun initializeInputImageSdk() {
        if (!sdkInitialized) {
            com.google.mlkit.common.MlKit.initialize(org.robolectric.RuntimeEnvironment.getApplication())
            sdkInitialized = true
        }
    }
    private val task = TaskCompletionSource<List<Face>>()
    private var detectorCloses = 0
    private var errors = 0
    private var hints = 0
    private var captures = 0
    private var active = true
    private val detector = Proxy.newProxyInstance(FaceDetector::class.java.classLoader, arrayOf(FaceDetector::class.java)) { _, method, _ ->
        when (method.name) {
            "process" -> task.task
            "close" -> { detectorCloses++; null }
            else -> error("Unexpected detector method: ${method.name}")
        }
    } as FaceDetector

    @Test fun syntheticFramePreparationDoesNotInvokeTheNativeDetector() {
        val image = InputImage.fromBitmap(Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888), 0)
        androidx.camera.view.transform.ImageProxyTransformFactory().getOutputTransform(selfieFrame())
        assertFalse(detector.process(image).isComplete)
    }

    private fun analyzer(missingImage: Boolean = false) = SelfieAnalyzer(detector, Executor { it.run() },
        { OutputTransform(Matrix(), Size(480, 360)) }, { 320 to 480 }, { active },
        { _, _ -> hints++ }, { captures++ }, { errors++ }, input = {
            if (missingImage) null else InputImage.fromBitmap(Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888), 0)
        })

    @Test fun disposeDefersDetectorCloseUntilPendingFrameClosesAndIgnoresLateResult() {
        var frames = 0
        val analyzer = analyzer()
        analyzer.analyze(selfieFrame(onClose = { frames++ }))
        analyzer.close()
        assertEquals(0, detectorCloses)
        task.setResult(emptyList())
        assertEquals(1, frames)
        assertEquals(1, detectorCloses)
        assertEquals(0, hints)
        assertEquals(0, captures)
        analyzer.close()
        analyzer.analyze(selfieFrame(onClose = { frames++ }))
        assertEquals(2, frames)
        assertEquals(1, detectorCloses)
    }

    @Test fun concurrentFrameIsClosedWithoutASecondDetectorRequest() {
        var frames = 0
        val analyzer = analyzer()
        analyzer.analyze(selfieFrame(onClose = { frames++ }))
        analyzer.analyze(selfieFrame(timestamp = 2, onClose = { frames++ }))
        assertEquals(1, frames)
        task.setResult(emptyList())
        assertEquals(2, frames)
        assertEquals(1, hints)
        assertEquals(0, captures)
        analyzer.close()
        assertEquals(1, detectorCloses)
    }

    @Test fun detectorErrorIsExplicitClosesFrameAndNeverCaptures() {
        var frames = 0
        val analyzer = analyzer()
        analyzer.analyze(selfieFrame(onClose = { frames++ }))
        task.setException(IllegalStateException("synthetic"))
        assertEquals(1, frames)
        assertEquals(1, errors)
        assertEquals(1, detectorCloses)
        assertEquals(0, captures)
    }

    @Test fun pauseRejectsResultAndMissingImageClosesResources() {
        var frames = 0
        val analyzer = analyzer()
        analyzer.analyze(selfieFrame(onClose = { frames++ }))
        active = false
        task.setResult(emptyList())
        assertEquals(0, hints)
        assertEquals(0, captures)
        assertEquals(1, frames)
        analyzer.close()
        val missing = analyzer(missingImage = true)
        missing.analyze(selfieFrame(onClose = { frames++ }))
        assertEquals(2, frames)
        assertEquals(1, errors)
        assertEquals(2, detectorCloses)
    }

    @Test fun stalledDetectorReportsErrorAndLateCompletionStillClosesTheFrame() {
        var frames = 0
        val analyzer = analyzer()
        analyzer.analyze(selfieFrame(onClose = { frames++ }))
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(5_001, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(1, errors)
        assertEquals(0, captures)
        task.setResult(emptyList())
        assertEquals(1, frames)
        assertEquals(1, detectorCloses)
        assertEquals(0, captures)
    }

    @Test fun pauseOrFocusCycleRejectsPrePauseResultEvenAfterResume() {
        var frames = 0
        val analyzer = analyzer()
        analyzer.analyze(selfieFrame(onClose = { frames++ }))
        analyzer.invalidate()
        analyzer.invalidate()
        task.setResult(emptyList())
        assertEquals(1, frames)
        assertEquals(0, hints)
        assertEquals(0, captures)
        assertEquals(0, errors)
        analyzer.close()
        assertEquals(1, detectorCloses)
    }

    @Test fun stableDetectedFaceCapturesOnceAndClosesFollowingFrames() {
        val face = org.mockito.Mockito.mock(Face::class.java)
        org.mockito.Mockito.`when`(face.boundingBox).thenReturn(android.graphics.Rect(220, 110, 420, 370))
        val detector = org.mockito.Mockito.mock(FaceDetector::class.java)
        org.mockito.Mockito.`when`(detector.process(org.mockito.Mockito.any(InputImage::class.java)))
            .thenAnswer { com.google.android.gms.tasks.Tasks.forResult(listOf(face)) }
        val matrix = Matrix().apply { setRectToRect(android.graphics.RectF(-1f, -1f, 1f, 1f),
            android.graphics.RectF(0f, 0f, 320f, 240f), Matrix.ScaleToFit.FILL) }
        var now = 0L
        var frames = 0
        var lastHint: SelfieHint? = null
        val analyzer = SelfieAnalyzer(detector, Executor { it.run() },
            { OutputTransform(matrix, Size(480, 360)) }, { 320 to 240 }, { true },
            { hint, oval -> lastHint = hint; assertNotNull(oval) }, { captures++ }, { errors++ },
            clock = { now }, input = {
                InputImage.fromBitmap(Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888), 0)
            })
        for (time in 0L..1980L step 180) {
            now = time
            analyzer.analyze(selfieFrame(timestamp = time + 1, onClose = { frames++ }))
        }
        assertEquals(SelfieHint.CAPTURING, lastHint)
        assertEquals(1, captures)
        assertEquals(0, errors)
        assertEquals(12, frames)
        org.mockito.Mockito.verify(detector).close()
    }

    @Test fun missingTransformTimesOutRatherThanCapturingOutsideThePreview() {
        val detector = org.mockito.Mockito.mock(FaceDetector::class.java)
        org.mockito.Mockito.`when`(detector.process(org.mockito.Mockito.any(InputImage::class.java)))
            .thenAnswer { com.google.android.gms.tasks.Tasks.forResult(emptyList<Face>()) }
        var now = 0L
        var frames = 0
        val analyzer = SelfieAnalyzer(detector, Executor { it.run() }, { null }, { 320 to 240 }, { true },
            { hint, oval -> assertEquals(SelfieHint.PREPARING, hint); assertNull(oval); hints++ },
            { captures++ }, { errors++ }, clock = { now }, input = {
                InputImage.fromBitmap(Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888), 0)
            })
        analyzer.analyze(selfieFrame(onClose = { frames++ }))
        now = 3001
        analyzer.analyze(selfieFrame(timestamp = 2, onClose = { frames++ }))
        assertEquals(1, hints)
        assertEquals(1, errors)
        assertEquals(0, captures)
        assertEquals(2, frames)
        org.mockito.Mockito.verify(detector).close()
    }

    @Test fun invalidInputAndCallbackFailuresCloseTheFrameAndDetector() {
        for (failure in listOf(IllegalArgumentException("invalid"), IllegalStateException("closed"))) {
            val detector = org.mockito.Mockito.mock(FaceDetector::class.java)
            var frames = 0
            var failures = 0
            val analyzer = SelfieAnalyzer(detector, Executor { it.run() }, { null }, { 320 to 240 }, { true },
                { _, _ -> fail("Unexpected hint") }, { fail("Unexpected capture") }, { failures++ },
                input = { throw failure })
            analyzer.analyze(selfieFrame(onClose = { frames++ }))
            assertEquals(1, failures)
            assertEquals(1, frames)
            org.mockito.Mockito.verify(detector).close()
        }
    }
}
