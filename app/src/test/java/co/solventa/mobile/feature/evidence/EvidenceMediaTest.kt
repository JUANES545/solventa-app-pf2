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
}
