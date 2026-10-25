package co.solventa.mobile.feature.photo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import co.solventa.mobile.R
import java.io.File
import java.io.IOException

internal class PhotoFileProblem(val messageId: Int) : IOException()

internal class PhotoFiles(cacheDir: File) {
    private val directory = File(cacheDir, "photo-review")

    fun create(): File {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Photo cache unavailable")
        return File.createTempFile("capture-", ".jpg", directory)
    }

    fun remove(file: File) {
        require(file.parentFile == directory)
        if (file.exists() && (!file.isFile || !file.delete())) throw IOException("Photo cleanup failed")
    }

    fun clear() {
        if (!directory.exists()) return
        val entries = directory.listFiles() ?: throw IOException("Photo cache unreadable")
        for (file in entries) remove(file)
    }

    fun preview(file: File): Bitmap {
        require(file.parentFile == directory)
        if (!file.isFile || file.length() !in 1..MAX_BYTES) throw PhotoFileProblem(R.string.photo_invalid)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        file.inputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outMimeType != "image/jpeg" || bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
            bounds.outWidth.toLong() * bounds.outHeight > MAX_PIXELS) {
            throw PhotoFileProblem(R.string.photo_invalid)
        }
        val options = BitmapFactory.Options().apply { inSampleSize = 1 }
        while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize > PREVIEW_EDGE) {
            options.inSampleSize *= 2
        }
        val bitmap = file.inputStream().use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw PhotoFileProblem(R.string.photo_invalid)
        val orientation = ExifInterface(file.path).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
            }
        }
        return if (matrix.isIdentity) bitmap else Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    companion object {
        const val MAX_BYTES = 20L * 1024 * 1024
        const val MAX_PIXELS = 64_000_000L
        private const val PREVIEW_EDGE = 1024
    }
}
