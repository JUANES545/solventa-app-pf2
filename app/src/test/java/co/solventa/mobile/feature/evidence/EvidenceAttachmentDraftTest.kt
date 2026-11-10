package co.solventa.mobile.feature.evidence

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import co.solventa.mobile.BuildConfig
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EvidenceAttachmentDraftTest {
    private val app = RuntimeEnvironment.getApplication()
    private val api = MobileEvidenceApi(evidenceAuth(), "http://127.0.0.1:1/movil/v1")
    private val model = EvidenceViewModel(app, api)
    private val scope = EvidenceScope("actor-a", UUID.randomUUID().toString())
    private val policy = EvidencePolicy(5, mapOf(
        EvidenceKind.PHOTO to 1_000_000L, EvidenceKind.DOCUMENT to 1_000_000L, EvidenceKind.VIDEO to 1_000_000L,
    ), 45_000)

    @After fun clear() { ViewModelStore().apply { put("evidence", model); clear() } }

    private fun await(error: EvidenceError? = null) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (model.state.value.busy && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertFalse(model.state.value.busy)
        assertEquals(error, model.state.value.error)
    }

    private fun open() { model.open(scope, policy, emptySet()); await() }

    private fun jpegUri(name: String): Uri {
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        val bytes = java.io.ByteArrayOutputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it))
            it.toByteArray()
        }
        bitmap.recycle()
        return Uri.parse("content://gallery/$name.jpg").also {
            shadowOf(app.contentResolver).registerInputStream(it, bytes.inputStream())
        }
    }

    private fun importDocument(): EvidenceDraft {
        model.importFile(jpegUri("document"), EvidenceKind.DOCUMENT, scope, model.state.value.generation)
        await()
        assertEquals(EvidenceStep.REVIEW, model.state.value.step)
        assertTrue(model.state.value.items.isEmpty())
        return checkNotNull(model.state.value.draft)
    }

    @Test fun documentImportUsesPrivateReviewAndAcceptsOnlyAfterReview() {
        open()
        val draft = importDocument()
        assertEquals(EvidenceKind.DOCUMENT, draft.kind)
        assertTrue(draft.file.canonicalPath.startsWith(app.cacheDir.canonicalPath))
        model.accept(false)
        await()
        assertFalse(draft.file.exists())
        val item = model.state.value.items.single()
        assertEquals(EvidenceKind.DOCUMENT, item.kind)
        EvidenceStore(app.noBackupFilesDir).use { assertTrue(it.file(item).exists()) }
    }

    @Test fun cancellingReplacementCaptureRestoresTheReviewedDraftAndRejectsLateCapture() {
        open()
        val original = importDocument()
        val originalBytes = original.file.readBytes()
        model.camera(EvidenceKind.DOCUMENT)
        val late = checkNotNull(model.beginCapture())
        model.back()
        assertEquals(EvidenceStep.REVIEW, model.state.value.step)
        assertEquals(original, model.state.value.draft)
        assertArrayEquals(originalBytes, original.file.readBytes())
        model.captured(late)
        assertFalse(late.file.exists())
        assertEquals(original, model.state.value.draft)
        model.camera(EvidenceKind.DOCUMENT)
        model.cameraFailed(null, model.state.value.generation, EvidenceError.PERMISSION)
        assertEquals(original, model.state.value.draft)
        assertEquals(EvidenceStep.REVIEW, model.state.value.step)
    }

    @Test fun failedOrStaleSelectionPreservesTheExistingDraft() {
        open()
        val original = importDocument()
        val generation = model.state.value.generation
        val bad = Uri.parse("content://gallery/invalid.jpg")
        shadowOf(app.contentResolver).registerInputStream(bad, "not a jpeg".byteInputStream())
        model.importFile(bad, EvidenceKind.DOCUMENT, scope, generation)
        await(EvidenceError.FORMAT)
        assertEquals(original, model.state.value.draft)
        assertTrue(original.file.exists())
        model.importFile(jpegUri("late"), EvidenceKind.PHOTO, scope.copy(actorId = "other-actor"), generation)
        assertEquals(original, model.state.value.draft)
        model.importFile(jpegUri("old"), EvidenceKind.PHOTO, scope, generation - 1)
        assertEquals(original, model.state.value.draft)
        model.close()
        assertFalse(original.file.exists())
    }

    @Test fun invalidReplacementCaptureReturnsToThePreviousReviewWithoutKeepingBadBytes() {
        open()
        val original = importDocument()
        model.camera(EvidenceKind.DOCUMENT)
        val invalid = checkNotNull(model.beginCapture())
        invalid.file.writeText("not a jpeg")
        model.captured(invalid)
        await(EvidenceError.FORMAT)
        assertEquals(EvidenceStep.REVIEW, model.state.value.step)
        assertEquals(original, model.state.value.draft)
        assertTrue(original.file.exists())
        assertFalse(invalid.file.exists())
        assertNull(model.state.value.retainedDraft)
    }

    @Test fun successfulReplacementDeletesOnlyTheOldDraft() {
        open()
        val original = importDocument()
        model.importFile(jpegUri("replacement"), EvidenceKind.PHOTO, scope, model.state.value.generation)
        await()
        assertFalse(original.file.exists())
        assertEquals(EvidenceKind.PHOTO, model.state.value.draft?.kind)
        assertTrue(checkNotNull(model.state.value.draft).file.exists())
    }

    @Test fun unstartedPendingRemovalDeletesPrivateFileAndMetadataWithoutNetwork() {
        open()
        importDocument()
        model.accept(false)
        await()
        val item = model.state.value.items.single()
        val file = EvidenceStore(app.noBackupFilesDir).use { it.file(item) }
        model.remove(item)
        await()
        assertFalse(file.exists())
        assertTrue(model.state.value.items.isEmpty())
        EvidenceStore(app.noBackupFilesDir).use { assertTrue(it.list(scope).isEmpty()) }
    }

    @Test fun productionConfirmedRemovalCannotCallDemoApiOrDeleteLocalData() {
        if (BuildConfig.BUILD_TYPE == "mock") return
        open()
        importDocument()
        model.accept(false)
        await()
        val original = model.state.value.items.single()
        val remote = UUID.randomUUID().toString()
        EvidenceStore(app.noBackupFilesDir).use {
            it.confirm(original, EvidenceReceipt(original.id, remote, original.sha256))
        }
        model.open(scope, policy, setOf(remote))
        await()
        val confirmed = model.state.value.items.single()
        assertFalse(evidenceCanRemove(confirmed))
        model.remove(confirmed)
        assertFalse(model.state.value.busy)
        assertEquals(confirmed, model.state.value.items.single())
        EvidenceStore(app.noBackupFilesDir).use { assertTrue(it.file(confirmed).exists()) }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { api.removeDemoConfirmed(scope, remote) }
        }
        EvidenceStore(app.noBackupFilesDir).use { store ->
            assertThrows(IllegalStateException::class.java) { store.removeDemoConfirmed(confirmed) }
        }
    }
}
