package co.solventa.mobile.feature.evidence

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaCodec
import android.media.MediaMuxer
import java.nio.ByteBuffer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal data class EvidenceCrop(val left: Float = 0f, val top: Float = 0f, val right: Float = 1f, val bottom: Float = 1f) {
    init {
        require(listOf(left, top, right, bottom).all { it.isFinite() && it in 0f..1f })
        require(right > left && bottom > top)
    }
}

internal data class EvidencePreview(val bitmap: Bitmap?, val durationMillis: Long = 0)

internal class EvidenceMedia(cacheDirectory: File) {
    private val directory = File(cacheDirectory, "claim-evidence-drafts")

    fun create(kind: EvidenceKind): File {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Evidence draft storage unavailable")
        return File.createTempFile("capture-", ".${kind.extension}", directory)
    }

    fun delete(file: File) {
        require(file.parentFile == directory)
        if (file.exists() && !file.delete()) throw IOException("Evidence draft cleanup failed")
    }

    fun importPhoto(input: InputStream, policy: EvidencePolicy): File = importFile(input, EvidenceKind.PHOTO, policy)

    fun importFile(input: InputStream, kind: EvidenceKind, policy: EvidencePolicy): File {
        val limit = policy.maxBytes[kind] ?: throw EvidenceProblem(EvidenceError.FORMAT)
        val target = create(kind)
        try {
            target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var size = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    size += count
                    if (size > limit) throw EvidenceProblem(EvidenceError.SIZE)
                    output.write(buffer, 0, count)
                }
            }
            inspect(target, kind, policy).bitmap?.recycle()
            if (kind == EvidenceKind.VIDEO) stripVideoMetadata(target, policy)
            return target
        } catch (error: Exception) {
            delete(target)
            throw error
        }
    }

    fun inspect(file: File, kind: EvidenceKind, policy: EvidencePolicy): EvidencePreview {
        require(file.parentFile == directory)
        if (!file.isFile || file.length() !in 1..(policy.maxBytes[kind] ?: 0)) throw EvidenceProblem(EvidenceError.SIZE)
        if (kind != EvidenceKind.VIDEO) {
            policy.validate(kind, file.length())
            return EvidencePreview(image(file, 1600))
        }
        try {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.path)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                if (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) != "video/mp4" ||
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") {
                    throw EvidenceProblem(EvidenceError.FORMAT)
                }
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toLongOrNull() ?: 0
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toLongOrNull() ?: 0
                if (width <= 0 || height <= 0 || width > 16_000_000L / height) {
                    throw EvidenceProblem(EvidenceError.FORMAT)
                }
                policy.validate(kind, file.length(), duration)
                val thumbnail = if (android.os.Build.VERSION.SDK_INT >= 27) retriever.getScaledFrameAtTime(0,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 640, 480) else null
                return EvidencePreview(thumbnail, duration)
            } finally {
                retriever.release()
            }
        } catch (error: IllegalArgumentException) {
            throw EvidenceProblem(EvidenceError.FORMAT, error)
        } catch (error: RuntimeException) {
            throw EvidenceProblem(EvidenceError.FORMAT, error)
        }
    }

    fun crop(file: File, crop: EvidenceCrop, policy: EvidencePolicy, kind: EvidenceKind) {
        require(file.parentFile == directory && kind != EvidenceKind.VIDEO)
        val source = image(file, 0)
        try {
            val x = (crop.left * source.width).toInt().coerceIn(0, source.width - 1)
            val y = (crop.top * source.height).toInt().coerceIn(0, source.height - 1)
            val right = (crop.right * source.width).toInt().coerceIn(x + 1, source.width)
            val bottom = (crop.bottom * source.height).toInt().coerceIn(y + 1, source.height)
            val result = Bitmap.createBitmap(source, x, y, right - x, bottom - y)
            try {
                val staged = File.createTempFile("crop-", ".jpg", directory)
                try {
                    FileOutputStream(staged).use { output ->
                        if (!result.compress(Bitmap.CompressFormat.JPEG, 95, output)) throw IOException("Evidence crop failed")
                        output.flush()
                        policy.validate(kind, output.channel.size())
                        output.fd.sync()
                    }
                    Files.move(staged.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } finally {
                    if (staged.exists() && !staged.delete()) throw IOException("Evidence crop cleanup failed")
                }
            } finally {
                if (result !== source) result.recycle()
            }
        } finally {
            source.recycle()
        }
    }

    fun stripVideoMetadata(file: File, policy: EvidencePolicy) {
        require(file.parentFile == directory)
        val staged = File.createTempFile("video-", ".mp4", directory)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val metadata = MediaMetadataRetriever()
            try {
                metadata.setDataSource(file.path)
                val rotation = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                val muxer = MediaMuxer(staged.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                try {
                    if (rotation in listOf(0, 90, 180, 270)) muxer.setOrientationHint(rotation)
                    val tracks = mutableMapOf<Int, Int>()
                    var maxInput = 1_048_576
                    for (index in 0 until extractor.trackCount) {
                        val format = extractor.getTrackFormat(index)
                        if (format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                            tracks[index] = muxer.addTrack(format)
                            extractor.selectTrack(index)
                            if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) maxInput = maxOf(maxInput, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                        }
                    }
                    if (tracks.isEmpty() || maxInput > 16 * 1024 * 1024) throw EvidenceProblem(EvidenceError.FORMAT)
                    muxer.start()
                    val buffer = ByteBuffer.allocateDirect(maxInput)
                    val info = MediaCodec.BufferInfo()
                    while (true) {
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) break
                        if (size > buffer.capacity()) throw EvidenceProblem(EvidenceError.FORMAT)
                        info.set(0, size, extractor.sampleTime,
                            if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        muxer.writeSampleData(checkNotNull(tracks[extractor.sampleTrackIndex]), buffer, info)
                        extractor.advance()
                    }
                    muxer.stop()
                } finally {
                    muxer.release()
                }
            } finally {
                metadata.release()
            }
            FileOutputStream(staged, true).use { it.fd.sync() }
            if (staged.length() !in 1..checkNotNull(policy.maxBytes[EvidenceKind.VIDEO])) throw EvidenceProblem(EvidenceError.SIZE)
            Files.move(staged.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (error: IllegalArgumentException) {
            throw EvidenceProblem(EvidenceError.FORMAT, error)
        } catch (error: IllegalStateException) {
            throw EvidenceProblem(EvidenceError.FORMAT, error)
        } finally {
            extractor.release()
            if (staged.exists() && !staged.delete()) throw IOException("Video metadata cleanup failed")
        }
    }

    internal fun image(file: File, maxEdge: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        file.inputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
        // Bound native allocations independently of the server's byte limit.
        if (bounds.outMimeType != "image/jpeg" || bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
            bounds.outWidth.toLong() * bounds.outHeight > 16_000_000) throw EvidenceProblem(EvidenceError.FORMAT)
        val options = BitmapFactory.Options().apply { inSampleSize = 1 }
        if (maxEdge > 0) while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize > maxEdge) options.inSampleSize *= 2
        val source = file.inputStream().use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw EvidenceProblem(EvidenceError.FORMAT)
        val matrix = Matrix().apply {
            when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
            }
        }
        if (matrix.isIdentity) return source
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true).also {
            if (it !== source) source.recycle()
        }
    }
}
