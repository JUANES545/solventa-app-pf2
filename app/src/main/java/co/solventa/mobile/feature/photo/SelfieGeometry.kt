package co.solventa.mobile.feature.photo

import android.graphics.RectF
import androidx.camera.view.TransformExperimental
import androidx.camera.core.ImageProxy
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.camera.view.transform.OutputTransform
import kotlin.math.min

@androidx.annotation.OptIn(TransformExperimental::class)
internal object SelfieGeometry {
    fun oval(content: RectF): RectF {
        val width = min(content.width() * .78f, content.height() * .70f)
        val height = width * 1.30f
        return RectF(content.centerX() - width / 2, content.centerY() - height / 2,
            content.centerX() + width / 2, content.centerY() + height / 2)
    }

    fun content(frame: ImageProxy, target: OutputTransform, width: Int, height: Int): RectF? {
        val bounds = RectF(frame.cropRect)
        val unrotated = ImageProxyTransformFactory().getOutputTransform(frame)
        CoordinateTransform(unrotated, target).mapRect(bounds)
        return bounds.takeIf { it.intersect(0f, 0f, width.toFloat(), height.toFloat()) && !it.isEmpty }
    }

    fun position(
        face: RectF, source: OutputTransform, target: OutputTransform, oval: RectF,
        pitch: Float, yaw: Float, roll: Float,
    ): SelfiePosition? {
        if (face.width() <= 0 || face.height() <= 0 || oval.width() <= 0 || oval.height() <= 0) return null
        val displayed = RectF(face)
        CoordinateTransform(source, target).mapRect(displayed)
        return SelfiePosition((displayed.centerX() - oval.left) / oval.width(),
            (displayed.centerY() - oval.top) / oval.height(),
            displayed.width() / oval.width(), displayed.height() / oval.height(), pitch, yaw, roll,
            face.width() >= 100 && face.height() >= 100)
    }
}
