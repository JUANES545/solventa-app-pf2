package co.solventa.mobile.feature.photo

import android.app.Application
import android.graphics.Matrix
import android.graphics.RectF
import android.util.Size
import androidx.camera.view.TransformExperimental
import androidx.camera.view.transform.OutputTransform
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@androidx.annotation.OptIn(TransformExperimental::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class DocumentFrameAnalyzerTest {
    private var bounds: RectF? = null
    private var errors = 0
    private var closedFrames = 0
    private var active = true
    private var time = 0L
    private val output = OutputTransform(Matrix().apply {
        setRectToRect(RectF(-1f, -1f, 1f, 1f), RectF(0f, 100f, 320f, 340f), Matrix.ScaleToFit.FILL)
    }, Size(480, 360))

    private fun analyzer(target: () -> OutputTransform? = { output }) =
        DocumentFrameAnalyzer(target, { 320 to 520 }, { active }, { bounds = it },
            { errors++ }, { time })

    @Test fun metadataMapsOnlyTheImageAreaAndAlwaysClosesWithoutReadingPixels() {
        val analyzer = analyzer()
        analyzer.analyze(selfieFrame(onClose = { closedFrames++ }))
        assertEquals(RectF(0f, 100f, 320f, 340f), bounds)
        assertEquals(1, closedFrames)
        assertEquals(0, errors)
        analyzer.close()
    }

    @Test fun inactiveOrDisposedAttemptCannotPublishAGuide() {
        val analyzer = analyzer()
        active = false
        analyzer.analyze(selfieFrame(onClose = { closedFrames++ }))
        assertNull(bounds)
        active = true
        analyzer.close()
        analyzer.analyze(selfieFrame(onClose = { closedFrames++ }))
        assertNull(bounds)
        assertEquals(2, closedFrames)
        assertEquals(0, errors)
    }

    @Test fun missingTransformTimesOutExplicitlyAndDoesNotKeepAnOldGuide() {
        var available = true
        val analyzer = analyzer { if (available) output else null }
        analyzer.analyze(selfieFrame(onClose = { closedFrames++ }))
        assertNotNull(bounds)
        available = false
        analyzer.analyze(selfieFrame(onClose = { closedFrames++ }))
        assertNull(bounds)
        time = 3_001
        analyzer.analyze(selfieFrame(onClose = { closedFrames++ }))
        analyzer.analyze(selfieFrame(onClose = { closedFrames++ }))
        assertEquals(1, errors)
        assertEquals(4, closedFrames)
    }

    @Test fun invalidTransformFailureIsExplicitAndStillClosesTheFrame() {
        val analyzer = analyzer { throw IllegalStateException("synthetic") }
        analyzer.analyze(selfieFrame(onClose = { closedFrames++ }))
        assertNull(bounds)
        assertEquals(1, errors)
        assertEquals(1, closedFrames)
    }
}
