package co.solventa.mobile.feature.photo

import android.graphics.RectF
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.view.TransformExperimental
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.camera.view.transform.OutputTransform
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetector
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@androidx.annotation.OptIn(ExperimentalGetImage::class, TransformExperimental::class)
internal class SelfieAnalyzer(
    private val detector: FaceDetector,
    private val callbacks: Executor,
    private val target: () -> OutputTransform?,
    private val viewSize: () -> Pair<Int, Int>,
    private val active: () -> Boolean,
    private val onHint: (SelfieHint, RectF?) -> Unit,
    private val onCapture: () -> Unit,
    private val onError: () -> Unit,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val input: (ImageProxy) -> InputImage? = { frame ->
        frame.image?.let { InputImage.fromMediaImage(it, frame.imageInfo.rotationDegrees) }
    },
) : ImageAnalysis.Analyzer, AutoCloseable {
    private val guidance = SelfieGuidance()
    private val closed = AtomicBoolean(false)
    private val detectorClosed = AtomicBoolean(false)
    private val revision = AtomicLong()
    private val lock = Any()
    private var pending: ImageProxy? = null
    private var transformWaitSince = -1L
    private val handler = Handler(Looper.getMainLooper())
    private val stalled = Runnable { if (!closed.get()) fail() }
    // ML Kit returns rotated-image coordinates; CameraX maps that output to the shared viewport.
    private val sourceFactory = ImageProxyTransformFactory().apply { isUsingRotationDegrees = true }

    override fun analyze(frame: ImageProxy) {
        synchronized(lock) {
            if (closed.get() || pending != null) {
                frame.close()
                return
            }
            pending = frame
        }
        val received = clock()
        val frameRevision = revision.get()
        handler.postDelayed(stalled, 5_000)
        try {
            val image = input(frame)
            if (image == null) {
                fail()
                finish(frame)
                return
            }
            val source = sourceFactory.getOutputTransform(frame)
            val timestamp = frame.imageInfo.timestamp
            detector.process(image).addOnCompleteListener(callbacks) { task ->
                try {
                    if (!closed.get() && frameRevision == revision.get()) {
                        if (!task.isSuccessful) {
                            fail()
                        } else if (!active()) {
                            guidance.reset()
                        } else {
                            val output = target()
                            val (width, height) = viewSize()
                            val content = output?.let { SelfieGeometry.content(frame, it, width, height) }
                            if (content == null) {
                                guidance.reset()
                                if (transformWaitSince < 0) transformWaitSince = clock()
                                if (clock() - transformWaitSince > 3_000) fail()
                                else onHint(SelfieHint.PREPARING, null)
                            } else {
                                transformWaitSince = -1
                                val oval = SelfieGeometry.oval(content)
                                val faces = task.result
                                val face = faces.singleOrNull()
                                val position = face?.let {
                                    SelfieGeometry.position(RectF(it.boundingBox), source, output, oval,
                                        it.headEulerAngleX, it.headEulerAngleY, it.headEulerAngleZ)
                                }
                                val hint = guidance.update(faces.size, position, received, clock(), timestamp, active())
                                onHint(hint, oval)
                                if (hint == SelfieHint.CAPTURING && !closed.get() && active()) {
                                    close()
                                    onCapture()
                                }
                            }
                        }
                    }
                } catch (_: IllegalArgumentException) {
                    fail()
                } catch (_: IllegalStateException) {
                    fail()
                } finally {
                    finish(frame)
                }
            }
        } catch (_: IllegalArgumentException) {
            fail()
            finish(frame)
        } catch (_: IllegalStateException) {
            fail()
            finish(frame)
        }
    }

    private fun fail() {
        guidance.reset()
        if (!closed.get()) onError()
        close()
    }

    fun invalidate() {
        revision.incrementAndGet()
        guidance.reset()
        transformWaitSince = -1
    }

    private fun finish(frame: ImageProxy) {
        handler.removeCallbacks(stalled)
        frame.close()
        synchronized(lock) { if (pending === frame) pending = null }
        if (closed.get()) closeDetector()
    }

    override fun close() {
        closed.set(true)
        handler.removeCallbacks(stalled)
        guidance.reset()
        synchronized(lock) { if (pending == null) closeDetector() }
    }

    private fun closeDetector() {
        if (detectorClosed.compareAndSet(false, true)) detector.close()
    }
}
