package co.solventa.mobile.feature.photo

import android.app.Application
import android.graphics.Matrix
import android.graphics.RectF
import android.util.Size
import androidx.camera.view.TransformExperimental
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.camera.view.transform.OutputTransform
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@androidx.annotation.OptIn(TransformExperimental::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SelfieGeometryTest {
    @Test fun cameraXRotatedCoordinatesMapOnceThroughCropMirrorAndLetterbox() {
        val factory = ImageProxyTransformFactory().apply { isUsingRotationDegrees = true }
        for (rotation in listOf(0, 90, 180, 270)) for (mirror in listOf(false, true)) {
            val face = when (rotation) {
                0 -> RectF(200f, 120f, 400f, 360f)
                90 -> RectF(120f, 200f, 360f, 400f)
                180 -> RectF(240f, 120f, 440f, 360f)
                else -> RectF(120f, 240f, 360f, 440f)
            }
            for (content in listOf(RectF(0f, 80f, 320f, 400f), RectF(140f, 0f, 500f, 320f))) {
                val matrix = Matrix().apply {
                    setRotate(rotation.toFloat())
                    postScale(content.width() / 2 * if (mirror) -1 else 1, content.height() / 2)
                    postTranslate(content.centerX(), content.centerY())
                }
                val output = OutputTransform(matrix, Size(480, 360))
                val visible = requireNotNull(SelfieGeometry.content(selfieFrame(rotation), output, 640, 520))
                assertEquals(content.left, visible.left, .0001f)
                assertEquals(content.top, visible.top, .0001f)
                assertEquals(content.right, visible.right, .0001f)
                assertEquals(content.bottom, visible.bottom, .0001f)
                val mapped = requireNotNull(SelfieGeometry.position(face,
                    factory.getOutputTransform(selfieFrame(rotation)), output, content, 0f, 0f, 0f))
                val rawX = (300f - 80) / 480
                val expectedX = when (rotation) { 0 -> rawX; 180 -> 1 - rawX; else -> .5f }
                val expectedY = when (rotation) { 90 -> rawX; 270 -> 1 - rawX; else -> .5f }
                assertEquals(if (mirror) 1 - expectedX else expectedX, mapped.centerX, .0001f)
                assertEquals(expectedY, mapped.centerY, .0001f)
                assertEquals(if (rotation % 180 == 0) 200f / 480 else 240f / 360, mapped.width, .0001f)
                assertEquals(if (rotation % 180 == 0) 240f / 360 else 200f / 480, mapped.height, .0001f)
            }
        }
    }

    @Test fun ovalUsesVisiblePreviewContentNotTheScreenOrBlackBars() {
        val matrix = Matrix().apply { setRectToRect(RectF(-1f, -1f, 1f, 1f),
            RectF(0f, 100f, 320f, 340f), Matrix.ScaleToFit.FILL) }
        val content = requireNotNull(SelfieGeometry.content(selfieFrame(),
            OutputTransform(matrix, Size(480, 360)), 320, 520))
        assertEquals(RectF(0f, 100f, 320f, 340f), content)
        val oval = SelfieGeometry.oval(content)
        assertTrue(content.contains(oval))
        assertEquals(160f, oval.centerX(), .01f)
        assertEquals(220f, oval.centerY(), .01f)
        assertEquals(1.30f, oval.height() / oval.width(), .01f)
    }

    @Test fun smallOrInvalidFaceCannotBecomeCaptureReadyThroughScaling() {
        val output = OutputTransform(Matrix(), Size(480, 360))
        assertNull(SelfieGeometry.position(RectF(), output, output, RectF(0f, 0f, 320f, 320f), 0f, 0f, 0f))
        val small = requireNotNull(SelfieGeometry.position(RectF(0f, 0f, 99f, 100f),
            output, output, RectF(0f, 0f, 120f, 120f), 0f, 0f, 0f))
        assertFalse(small.enoughPixels)
    }
}
