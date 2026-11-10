package co.solventa.mobile.mock

import android.content.ContentValues
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.media.MediaCodec
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import co.solventa.mobile.BuildConfig
import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class DemoMediaDeviceTest {
    @Test fun prepareShortTrafficVideoInDownloads() {
        check(BuildConfig.APPLICATION_ID == "co.solventa.mobile.pf2.mock")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.filesDir, "solventa-trafico-prueba.mov")
        check(source.isFile && source.length() in 1..10_485_760)
        val resolver = context.contentResolver
        val output = checkNotNull(resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "trafico.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Solventa")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }))
        var complete = false
        try {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(source.path)
                val track = (0 until extractor.trackCount).first {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                }
                val format = extractor.getTrackFormat(track)
                assertTrue(format.getLong(MediaFormat.KEY_DURATION) in 1..20_000_000)
                extractor.selectTrack(track)
                checkNotNull(resolver.openFileDescriptor(output, "rw")).use { descriptor ->
                    val muxer = MediaMuxer(descriptor.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    try {
                        val target = muxer.addTrack(format)
                        muxer.start()
                        val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
                        val info = MediaCodec.BufferInfo()
                        while (true) {
                            buffer.clear()
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) break
                            info.set(0, size, extractor.sampleTime, extractor.sampleFlags)
                            muxer.writeSampleData(target, buffer, info)
                            extractor.advance()
                        }
                        muxer.stop()
                    } finally { muxer.release() }
                }
            } finally { extractor.release() }
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(context, output)
                val duration = checkNotNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)).toLong()
                assertTrue(duration in 1..20_000)
                val frame = retriever.getFrameAtTime(1_000_000)
                assertNotNull(frame)
                frame?.recycle()
            }
            assertEquals(1, resolver.update(output, ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }, null, null))
            complete = true
            assertTrue(source.delete())
        } finally {
            if (!complete) resolver.delete(output, null, null)
        }
    }
}
