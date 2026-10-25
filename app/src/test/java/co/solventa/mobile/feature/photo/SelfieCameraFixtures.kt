package co.solventa.mobile.feature.photo

import android.graphics.Matrix
import android.graphics.Rect
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import java.lang.reflect.Proxy

internal fun selfieFrame(
    rotation: Int = 0, timestamp: Long = 1, crop: Rect = Rect(80, 60, 560, 420), onClose: () -> Unit = {},
): ImageProxy {
    val info = Proxy.newProxyInstance(ImageInfo::class.java.classLoader, arrayOf(ImageInfo::class.java)) { _, method, _ ->
        when (method.name) {
            "getRotationDegrees" -> rotation
            "getTimestamp" -> timestamp
            "getSensorToBufferTransformMatrix" -> Matrix()
            else -> error("Unexpected ImageInfo method: ${method.name}")
        }
    } as ImageInfo
    return Proxy.newProxyInstance(ImageProxy::class.java.classLoader, arrayOf(ImageProxy::class.java)) { _, method, _ ->
        when (method.name) {
            "getWidth" -> 640
            "getHeight" -> 480
            "getCropRect" -> crop
            "getImageInfo" -> info
            "getImage" -> null
            "close" -> { onClose(); null }
            else -> error("Unexpected ImageProxy method: ${method.name}")
        }
    } as ImageProxy
}
