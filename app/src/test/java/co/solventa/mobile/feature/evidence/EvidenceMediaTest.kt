package co.solventa.mobile.feature.evidence

import android.app.Application
import android.graphics.Bitmap
import android.media.ExifInterface
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EvidenceMediaTest {
    @get:Rule val folder = TemporaryFolder()
    private val policy = EvidencePolicy(4, mapOf(EvidenceKind.PHOTO to 1_000_000L, EvidenceKind.DOCUMENT to 1_000_000L), 0)

    private fun jpeg(file: File) {
        val image = Bitmap.createBitmap(100, 80, Bitmap.Config.ARGB_8888)
        file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
        image.recycle()
    }

    @Test fun reviewAndCropUseUprightImageAndRemoveGpsMetadata() {
        val media = EvidenceMedia(folder.newFolder())
        val file = media.create(EvidenceKind.DOCUMENT).also(::jpeg)
        ExifInterface(file.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "4/1,36/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "74/1,0/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "W")
            saveAttributes()
        }
        val preview = media.inspect(file, EvidenceKind.DOCUMENT, policy).bitmap!!
        assertEquals(80, preview.width)
        assertEquals(100, preview.height)
        media.crop(file, EvidenceCrop(.25f, .2f, .75f, .8f), policy, EvidenceKind.DOCUMENT)
        val raw = file.inputStream().use { android.graphics.BitmapFactory.decodeStream(it) }!!
        assertEquals("Raw cropped width; EXIF=${ExifInterface(file.path).getAttribute(ExifInterface.TAG_ORIENTATION)}", 40, raw.width)
        val cropped = media.inspect(file, EvidenceKind.DOCUMENT, policy).bitmap!!
        assertEquals(40, cropped.width)
        assertEquals(60, cropped.height)
        assertFalse(ExifInterface(file.path).getLatLong(FloatArray(2)))
        media.delete(file)
        assertFalse(file.exists())
    }

    @Test fun badFormatsEmptyFilesAndInvalidCropCannotPassReview() {
        val media = EvidenceMedia(folder.newFolder())
        val file = media.create(EvidenceKind.PHOTO)
        assertThrows(EvidenceProblem::class.java) { media.inspect(file, EvidenceKind.PHOTO, policy) }
        file.writeText("not a jpeg")
        assertThrows(EvidenceProblem::class.java) { media.inspect(file, EvidenceKind.PHOTO, policy) }
        assertThrows(IllegalArgumentException::class.java) { EvidenceCrop(.5f, 0f, .4f, 1f) }
        assertThrows(IllegalArgumentException::class.java) { EvidenceCrop(Float.NaN) }
    }

    @Test fun failedCropLeavesOriginalBytesRecoverable() {
        val media = EvidenceMedia(folder.newFolder())
        val file = media.create(EvidenceKind.PHOTO).also(::jpeg)
        val original = file.readBytes()
        val tooSmall = EvidencePolicy(1, mapOf(EvidenceKind.PHOTO to 1L), 0)
        assertThrows(EvidenceProblem::class.java) { media.crop(file, EvidenceCrop(), tooSmall, EvidenceKind.PHOTO) }
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun importingJpegUsesPrivateCopyAndRejectsOversizeOrDisguisedFiles() {
        val media = EvidenceMedia(folder.newFolder())
        val original = folder.newFile("gallery.jpg").also(::jpeg)
        val copied = original.inputStream().use { media.importPhoto(it, policy) }
        assertArrayEquals(original.readBytes(), copied.readBytes())
        assertNotEquals(original, copied)
        val before = copied.parentFile!!.list()!!.toSet()
        assertThrows(EvidenceProblem::class.java) {
            original.inputStream().use {
                media.importPhoto(it, EvidencePolicy(1, mapOf(EvidenceKind.PHOTO to 1L), 0))
            }
        }
        assertThrows(EvidenceProblem::class.java) {
            "not a JPEG".byteInputStream().use { media.importPhoto(it, policy) }
        }
        assertEquals("Rejected imports must not leave draft files", before, copied.parentFile!!.list()!!.toSet())
    }

    @Test fun documentImportUsesItsOwnPolicyAndCannotDisguiseAnImageAsVideo() {
        val media = EvidenceMedia(folder.newFolder())
        val source = folder.newFile("document.jpg").also(::jpeg)
        val documentOnly = EvidencePolicy(1, mapOf(EvidenceKind.DOCUMENT to 1_000_000L), 0)
        val imported = source.inputStream().use { media.importFile(it, EvidenceKind.DOCUMENT, documentOnly) }
        assertArrayEquals(source.readBytes(), imported.readBytes())
        val before = imported.parentFile!!.list()!!.toSet()
        val videoPolicy = EvidencePolicy(1, mapOf(EvidenceKind.VIDEO to 1_000_000L), 45_000)
        assertThrows(EvidenceProblem::class.java) {
            source.inputStream().use { media.importFile(it, EvidenceKind.VIDEO, videoPolicy) }
        }
        assertThrows(EvidenceProblem::class.java) {
            source.inputStream().use { media.importFile(it, EvidenceKind.PHOTO, documentOnly) }
        }
        assertEquals(before, imported.parentFile!!.list()!!.toSet())
    }

    @Test fun oversizedJpegDimensionsAreRejectedBeforeNativeDecode() {
        val media = EvidenceMedia(folder.newFolder())
        val file = media.create(EvidenceKind.DOCUMENT).also(::jpeg)
        val bytes = file.readBytes()
        val frame = (0 until bytes.size - 8).first {
            bytes[it] == 0xff.toByte() && bytes[it + 1] in listOf(0xc0.toByte(), 0xc2.toByte())
        }
        // The JPEG frame advertises 5000 × 5000; the compressed payload stays small.
        bytes[frame + 5] = 0x13
        bytes[frame + 6] = 0x88.toByte()
        bytes[frame + 7] = 0x13
        bytes[frame + 8] = 0x88.toByte()
        file.writeBytes(bytes)
        assertEquals(EvidenceError.FORMAT, assertThrows(EvidenceProblem::class.java) {
            media.inspect(file, EvidenceKind.DOCUMENT, policy)
        }.reason)
    }
}
