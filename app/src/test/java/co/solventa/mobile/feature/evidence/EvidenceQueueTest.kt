package co.solventa.mobile.feature.evidence

import android.app.Application
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class EvidenceQueueTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = EvidenceScope("actor-a", "claim-a")
    private val policy = EvidencePolicy(3, mapOf(EvidenceKind.PHOTO to 100L), 0)
    private val now = Instant.parse("2026-11-04T15:00:00Z")

    private fun add(store: EvidenceStore, scope: EvidenceScope = this.scope): EvidenceItem {
        val source = folder.newFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }
        return store.enqueue(scope, EvidenceKind.PHOTO, source, now, null, 0, policy, emptySet())
    }

    @Test fun queuedBytesIdentityAndConfirmationSurviveReopenAndAreAccountScoped() {
        val dir = folder.newFolder()
        val item = EvidenceStore(dir).use { store ->
            add(store).also {
                assertTrue(store.list(scope.copy(actorId = "actor-b")).isEmpty())
                assertTrue(store.list(scope.copy(claimId = "claim-b")).isEmpty())
                store.confirm(it, EvidenceReceipt(it.id, "remote-1", it.sha256))
            }
        }
        EvidenceStore(dir).use { store ->
            val restored = store.list(scope).single()
            assertEquals(item.id, restored.id)
            assertEquals(EvidenceStatus.CONFIRMED, restored.status)
            assertArrayEquals(byteArrayOf(1, 2, 3), store.file(restored).readBytes())
            assertThrows(EvidenceProblem::class.java) { store.file(item.copy(scope = scope.copy(actorId = "other"))) }
        }
    }

    @Test fun limitsCountRemoteUnionAndPendingWithoutDoubleCountingConfirmed() {
        EvidenceStore(folder.newFolder()).use { store ->
            val a = add(store)
            store.confirm(a, EvidenceReceipt(a.id, "remote-a", a.sha256))
            val source = folder.newFile().apply { writeBytes(byteArrayOf(1)) }
            store.enqueue(scope, EvidenceKind.PHOTO, source, now, null, 0, policy, setOf("remote-a", "remote-b"))
            val error = assertThrows(EvidenceProblem::class.java) {
                store.enqueue(scope, EvidenceKind.PHOTO, source, now, null, 0, policy, setOf("remote-a", "remote-b"))
            }
            assertEquals(EvidenceError.COUNT, error.reason)
        }
    }

    @Test fun lostAcknowledgementIsReconciledWithoutResendingAndConfirmedNeverUploadsAgain() = runBlocking {
        EvidenceStore(folder.newFolder()).use { store ->
            val item = add(store)
            var sends = 0
            var remote: EvidenceReceipt? = null
            val transport = object : EvidenceTransport {
                override suspend fun confirmed(scope: EvidenceScope, localId: String) = remote
                override suspend fun upload(item: EvidenceItem, file: File, progress: (Long) -> Unit): EvidenceReceipt {
                    sends++
                    remote = EvidenceReceipt(item.id, "remote-1", item.sha256)
                    throw IOException("Lost response after commit")
                }
            }
            val uploader = EvidenceUploader(store, transport)
            try { uploader.retry(scope, { true }) { _, _, _ -> }; fail() } catch (_: IOException) { }
            assertEquals(EvidenceStatus.PENDING, store.list(scope).single().status)
            EvidenceUploader(store, transport).retry(scope, { true }) { _, _, _ -> }
            uploader.retry(scope, { true }) { _, _, _ -> fail("Confirmed should not report upload") }
            assertEquals(1, sends)
            assertEquals(item.id, store.list(scope).single().id)
            assertEquals(EvidenceStatus.CONFIRMED, store.list(scope).single().status)
        }
    }

    @Test fun failedUploadKeepsStableIdAndRetryChecksBytesAndAccount() = runBlocking {
        EvidenceStore(folder.newFolder()).use { store ->
            val item = add(store)
            val transport = object : EvidenceTransport {
                override suspend fun confirmed(scope: EvidenceScope, localId: String): EvidenceReceipt? = null
                override suspend fun upload(item: EvidenceItem, file: File, progress: (Long) -> Unit): EvidenceReceipt =
                    throw AssertionError("Must not upload")
            }
            val uploader = EvidenceUploader(store, transport)
            try { uploader.retry(scope, { false }) { _, _, _ -> }; fail() }
            catch (e: EvidenceProblem) { assertEquals(EvidenceError.SESSION, e.reason) }
            store.file(item).appendBytes(byteArrayOf(4))
            try { uploader.retry(scope, { true }) { _, _, _ -> }; fail() }
            catch (e: EvidenceProblem) { assertEquals(EvidenceError.STORAGE, e.reason) }
            assertEquals(item.id, store.list(scope).single().id)
            assertEquals(EvidenceStatus.PENDING, store.list(scope).single().status)
        }
    }

    @Test fun mismatchedOrConflictingReceiptsCannotConfirm() {
        EvidenceStore(folder.newFolder()).use { store ->
            val item = add(store)
            assertThrows(EvidenceProblem::class.java) { store.confirm(item, EvidenceReceipt("wrong", "remote", item.sha256)) }
            assertThrows(EvidenceProblem::class.java) { store.confirm(item, EvidenceReceipt(item.id, "remote", "bad")) }
            assertEquals(EvidenceStatus.PENDING, store.list(scope).single().status)
            store.confirm(item, EvidenceReceipt(item.id, "remote", item.sha256))
            assertThrows(EvidenceProblem::class.java) { store.confirm(item, EvidenceReceipt(item.id, "different", item.sha256)) }
            assertEquals("remote", store.list(scope).single().remoteId)
        }
    }

    @Test fun locationIsOptionalAndPreservedOnlyWhenSupplied() {
        EvidenceStore(folder.newFolder()).use { store ->
            assertNull(add(store).location)
            val geo = EvidenceLocation(4.6, -74.0, now)
            val source = folder.newFile().apply { writeBytes(byteArrayOf(1)) }
            val item = store.enqueue(scope, EvidenceKind.PHOTO, source, now, geo, 0, policy, emptySet())
            assertEquals(geo, store.list(scope).single { it.id == item.id }.location)
        }
        assertThrows(IllegalArgumentException::class.java) { EvidenceLocation(Double.NaN, 0.0, now) }
    }

    @Test fun exactConfiguredSizeDurationAndFormatBoundaries() {
        val configured = EvidencePolicy(1, mapOf(EvidenceKind.VIDEO to 8, EvidenceKind.PHOTO to 4), 500)
        configured.validate(EvidenceKind.VIDEO, 8, 500)
        assertThrows(EvidenceProblem::class.java) { configured.validate(EvidenceKind.VIDEO, 9, 500) }
        assertThrows(EvidenceProblem::class.java) { configured.validate(EvidenceKind.VIDEO, 8, 501) }
        assertThrows(EvidenceProblem::class.java) { configured.validate(EvidenceKind.VIDEO, 8, 0) }
        assertThrows(EvidenceProblem::class.java) { configured.validate(EvidenceKind.DOCUMENT, 1) }
        assertThrows(EvidenceProblem::class.java) { configured.validate(EvidenceKind.PHOTO, 0) }
    }

    @Test fun rejectedItemDoesNotBlockLaterValidItemsAndCannotDeleteConfirmed() = runBlocking {
        val dir = folder.newFolder()
        var rejectedId = ""
        EvidenceStore(dir).use { store ->
            val first = add(store)
            val second = add(store)
            val ordered = store.list(scope)
            rejectedId = ordered.first().id
            val transport = object : EvidenceTransport {
                override suspend fun confirmed(scope: EvidenceScope, localId: String): EvidenceReceipt? = null
                override suspend fun upload(item: EvidenceItem, file: File, progress: (Long) -> Unit): EvidenceReceipt {
                    if (item.id == rejectedId) throw EvidenceHttpProblem(422, "formato_invalido", true, EvidenceError.FORMAT)
                    return EvidenceReceipt(item.id, "remote", item.sha256)
                }
            }
            EvidenceUploader(store, transport).retry(scope, { true }) { _, _, _ -> }
            assertEquals(1, store.list(scope).count { it.status == EvidenceStatus.CONFIRMED })
            assertEquals(1, store.list(scope).count { it.status == EvidenceStatus.REJECTED })
            assertTrue(store.file(first).exists())
            assertTrue(store.file(second).exists())
            val confirmed = store.list(scope).single { it.status == EvidenceStatus.CONFIRMED }
            assertThrows(IllegalArgumentException::class.java) { store.removeRejected(confirmed) }
        }
        EvidenceStore(dir).use { store ->
            val rejected = store.list(scope).single { it.id == rejectedId }
            assertEquals(EvidenceError.FORMAT, rejected.rejection)
            store.removeRejected(rejected)
            assertEquals(EvidenceStatus.CONFIRMED, store.list(scope).single().status)
        }
    }
}
