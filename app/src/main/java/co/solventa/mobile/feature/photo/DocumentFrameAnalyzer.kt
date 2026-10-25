package co.solventa.mobile.feature.photo

import android.graphics.RectF
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.view.TransformExperimental
import androidx.camera.view.transform.OutputTransform
import java.util.concurrent.atomic.AtomicBoolean

@androidx.annotation.OptIn(TransformExperimental::class)
internal class DocumentFrameAnalyzer(
    private val target: () -> OutputTransform?,
    private val viewSize: () -> Pair<Int, Int>,
    private val active: () -> Boolean,
    private val onBounds: (RectF?) -> Unit,
    private val onError: () -> Unit,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : ImageAnalysis.Analyzer, AutoCloseable {
    private val closed = AtomicBoolean(false)
    private var waitingSince = -1L

    override fun analyze(frame: ImageProxy) {
        try {
            if (closed.get()) return
            if (!active()) {
                waitingSince = -1
                onBounds(null)
                return
            }
            val (width, height) = viewSize()
            val bounds = target()?.let { SelfieGeometry.content(frame, it, width, height) }
            onBounds(bounds)
            if (bounds != null) waitingSince = -1
            else if (waitingSince < 0) waitingSince = clock()
            else if (clock() - waitingSince > 3_000) fail()
        } catch (_: IllegalArgumentException) {
            fail()
        } catch (_: IllegalStateException) {
            fail()
        } finally {
            frame.close()
        }
    }

    private fun fail() {
        if (closed.compareAndSet(false, true)) {
            onBounds(null)
            onError()
        }
    }

    override fun close() { closed.set(true) }
}
