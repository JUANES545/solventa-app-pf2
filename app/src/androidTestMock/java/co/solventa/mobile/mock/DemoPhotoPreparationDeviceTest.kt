package co.solventa.mobile.mock

import android.content.ContentUris
import android.content.ContentValues
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import co.solventa.mobile.BuildConfig
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class DemoPhotoPreparationDeviceTest {
    @Test fun registerPublicPracticePhotosAndDocumentWithoutBroadMediaPermission() {
        check(BuildConfig.APPLICATION_ID == "co.solventa.mobile.pf2.mock")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val names = listOf("choque-frontal.jpg", "parachoques.jpg", "puerta-lateral.jpg",
            "vehiculos-temporal.jpg", "documento-prueba.jpg")
        for (name in names) {
            val source = File(context.filesDir, "demo-media/$name")
            check(source.isFile && source.length() in 1..10_485_760)
            val existing = checkNotNull(resolver.query(collection, arrayOf(MediaStore.Images.Media._ID),
                "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND ${MediaStore.Images.Media.RELATIVE_PATH} = ?",
                arrayOf(name, "Pictures/Solventa/"), null)).use { cursor ->
                if (cursor.moveToFirst()) ContentUris.withAppendedId(collection, cursor.getLong(0)) else null
            }
            val uri = existing ?: checkNotNull(resolver.insert(collection, ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Solventa")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }))
            var complete = false
            try {
                if (existing == null) {
                    checkNotNull(resolver.openOutputStream(uri)).use { output ->
                        source.inputStream().use { it.copyTo(output) }
                    }
                }
                val expected = MessageDigest.getInstance("SHA-256").digest(source.readBytes())
                val actual = checkNotNull(resolver.openInputStream(uri)).use {
                    MessageDigest.getInstance("SHA-256").digest(it.readBytes())
                }
                assertArrayEquals(expected, actual)
                if (existing == null) assertEquals(1, resolver.update(uri, ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }, null, null))
                complete = true
                assertTrue(source.delete())
            } finally {
                if (!complete && existing == null) resolver.delete(uri, null, null)
            }
        }
    }
}
