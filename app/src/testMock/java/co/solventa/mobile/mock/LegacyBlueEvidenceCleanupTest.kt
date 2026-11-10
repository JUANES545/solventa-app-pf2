package co.solventa.mobile.mock

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import co.solventa.mobile.feature.evidence.*
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LegacyBlueEvidenceCleanupTest {
    private val app = RuntimeEnvironment.getApplication()
    private val scope = EvidenceScope(MockBackend.ACTOR, MockBackend.CLAIM)
    private val policy = EvidencePolicy(20, EvidenceKind.entries.associateWith { 1_000_000L }, 20_000)

    private fun photo(width: Int = 96, color: Int = Color.BLUE, marked: Boolean = false): File {
        val file = File(app.cacheDir, "${UUID.randomUUID()}.jpg")
        val bitmap = Bitmap.createBitmap(width, 64, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            if (marked) for (x in 0 until width / 2) for (y in 0 until 32) bitmap.setPixel(x, y, Color.WHITE)
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
        } finally { bitmap.recycle() }
        return file
    }

    private fun enqueue(store: EvidenceStore, source: File, target: EvidenceScope = scope,
        kind: EvidenceKind = EvidenceKind.PHOTO, confirmed: Boolean = true): EvidenceItem {
        val item = store.enqueue(target, kind, source, Instant.now(), null, 0, policy, emptySet())
        if (confirmed) store.confirm(item, EvidenceReceipt(item.id, UUID.randomUUID().toString(), item.sha256))
        return store.list(target).single { it.id == item.id }
    }

    @Test fun exactOldJpegAndPrivacyReencodedJpegAreRemovedWithoutTouchingOtherImagesOrScopes() = runBlocking {
        EvidenceStore(app.noBackupFilesDir).use { store ->
            val sources = mutableListOf<File>()
            fun source(width: Int = 96, color: Int = Color.BLUE, marked: Boolean = false) =
                photo(width, color, marked).also(sources::add)
            try {
                val old = enqueue(store, source())
                val media = EvidenceMedia(app.cacheDir)
                val acceptedFile = source().inputStream().use { media.importPhoto(it, policy) }
                val accepted = try {
                    media.crop(acceptedFile, EvidenceCrop(), policy, EvidenceKind.PHOTO)
                    enqueue(store, acceptedFile)
                } finally { media.delete(acceptedFile) }
                val otherClaim = scope.copy(claimId = UUID.randomUUID().toString())
                val otherActor = scope.copy(actorId = "another-account")
                val testClaim = scope.copy(claimId = UUID.randomUUID().toString())
                val wrongDescription = scope.copy(claimId = UUID.randomUUID().toString())
                val wrongType = scope.copy(claimId = UUID.randomUUID().toString())
                val oldTestPhoto = enqueue(store, source(), testClaim)
                val kept = listOf(
                    enqueue(store, source(color = Color.RED)),
                    enqueue(store, source(marked = true)),
                    enqueue(store, source(width = 97)),
                    enqueue(store, source(), kind = EvidenceKind.DOCUMENT),
                    enqueue(store, source(), confirmed = false),
                    enqueue(store, source(), otherClaim),
                    enqueue(store, source(), otherActor),
                    enqueue(store, source(), testClaim.copy(actorId = "another-account")),
                    enqueue(store, source(color = Color.RED), testClaim),
                    enqueue(store, source(), wrongDescription),
                    enqueue(store, source(), wrongType),
                )
                val legacyDescription = "Fotografía sintética para comprobar carga y restauración."
                val claims = listOf(
                    EvidenceClaim(testClaim.claimId, "Prueba de fotografía", "", legacyDescription),
                    EvidenceClaim(otherClaim.claimId, "Choque", "", "Fotografía del usuario"),
                    EvidenceClaim(wrongDescription.claimId, "Prueba de fotografía", "", "Fotografía del usuario"),
                    EvidenceClaim(wrongType.claimId, "Choque", "", legacyDescription),
                )
                val calls = mutableListOf<Pair<EvidenceScope, String>>()
                val removed = LegacyBlueEvidenceCleanup.remove(store, claims) { target, remote ->
                    assertTrue(store.file(store.list(target).single { it.remoteId == remote }).isFile)
                    calls.add(target to remote)
                }
                assertEquals(listOf(old.id, accepted.id, oldTestPhoto.id), removed)
                assertEquals(listOf(scope to old.remoteId, scope to accepted.remoteId,
                    testClaim to oldTestPhoto.remoteId), calls)
                for (item in kept) {
                    assertTrue(store.list(item.scope).contains(item))
                    assertTrue(store.file(item).isFile)
                }
                assertTrue(LegacyBlueEvidenceCleanup.remove(store, claims) { _, _ -> fail("Already cleaned") }.isEmpty())
            } finally { sources.forEach { check(it.delete()) } }
        }
    }

    @Test fun failedRemoteDeletionPreservesValidatedLocalFileAndRow() = runBlocking {
        val source = photo()
        try {
            EvidenceStore(app.noBackupFilesDir).use { store ->
                val item = enqueue(store, source)
                try {
                    LegacyBlueEvidenceCleanup.remove(store) { _, _ -> throw IOException("No 204") }
                    fail("Remote deletion must succeed first")
                } catch (_: IOException) { }
                assertEquals(item, store.list(scope).single())
                assertTrue(store.file(item).exists())
            }
        } finally { check(source.delete()) }
    }

    @Test fun changedLocalBytesCannotAuthorizeRemoteDeletion() = runBlocking {
        val source = photo()
        try {
            EvidenceStore(app.noBackupFilesDir).use { store ->
                val item = enqueue(store, source)
                store.file(item).appendBytes(byteArrayOf(1))
                try {
                    LegacyBlueEvidenceCleanup.remove(store) { _, _ -> fail("Corrupt evidence must not authorize deletion") }
                    fail("Expected storage validation")
                } catch (error: EvidenceProblem) { assertEquals(EvidenceError.STORAGE, error.reason) }
                assertEquals(item, store.list(scope).single())
            }
        } finally { check(source.delete()) }
    }
}
