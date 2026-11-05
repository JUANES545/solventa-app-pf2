package co.solventa.mobile.feature.evidence

import android.app.Application
import java.io.File
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
class EvidenceQueueHistoryTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = EvidenceScope("actor-a", "claim-a")
    private val created = Instant.parse("2026-11-05T12:00:00Z")

    @Test fun conflictIsDurableDoesNotOverwriteOrBlockOtherItemsAndLostAckReconcilesInFifoOrder() = runBlocking {
        val directory = folder.newFolder()
        val source = folder.newFile().apply { writeBytes(byteArrayOf(1, 2)) }
        val policy = EvidencePolicy(3, mapOf(EvidenceKind.PHOTO to 100L), 0)
        var clock = created
        val items = EvidenceStore(directory) { clock }.use { store ->
            List(3) { index ->
                store.enqueue(scope, EvidenceKind.PHOTO, source, created.minusSeconds(index.toLong()),
                    null, 0, policy, emptySet())
            }
        }
        val attempts = mutableListOf<String>()
        var uploaded: EvidenceReceipt? = null
        var uploads = 0
        val transport = object : EvidenceTransport {
            override suspend fun confirmed(scope: EvidenceScope, localId: String): EvidenceReceipt? {
                attempts += localId
                if (localId == items[0].id) throw EvidenceHttpProblem(409, "idempotencia_conflictiva", true, EvidenceError.CONFLICT)
                return uploaded?.takeIf { it.localId == localId }
            }
            override suspend fun upload(item: EvidenceItem, file: File, progress: (Long) -> Unit): EvidenceReceipt {
                uploads++
                if (item.id == items[1].id) {
                    uploaded = EvidenceReceipt(item.id, "remote-second", item.sha256)
                    throw EvidenceProblem(EvidenceError.NETWORK)
                }
                return EvidenceReceipt(item.id, "remote-third", item.sha256)
            }
        }
        clock = created.plusSeconds(10)
        EvidenceStore(directory) { clock }.use { store ->
            try { EvidenceUploader(store, transport).retry(scope, { true }) { _, _, _ -> }; fail() }
            catch (error: EvidenceProblem) { assertEquals(EvidenceError.NETWORK, error.reason) }
            assertEquals(items.take(2).map { it.id }, attempts)
            assertNull(store.list(scope)[2].lastResultAt)
        }
        clock = created.plusSeconds(20)
        EvidenceStore(directory) { clock }.use { store ->
            val conflict = store.list(scope)[0]
            assertEquals(EvidenceStatus.CONFLICT, conflict.status)
            assertEquals("idempotencia_conflictiva", conflict.errorCode)
            assertEquals(created.plusSeconds(10), conflict.lastResultAt)
            assertThrows(IllegalArgumentException::class.java) { store.removeRejected(conflict) }
            EvidenceUploader(store, transport).retry(scope, { true }) { _, _, _ -> }
            assertEquals(listOf(items[0].id, items[1].id, items[1].id, items[2].id), attempts)
            assertEquals(2, uploads)
            assertEquals(listOf(EvidenceStatus.CONFLICT, EvidenceStatus.CONFIRMED, EvidenceStatus.CONFIRMED),
                store.list(scope).map { it.status })
            assertEquals(conflict, store.list(scope)[0])
            val confirmed = store.list(scope)[1]
            clock = created.plusSeconds(30)
            store.confirm(confirmed, checkNotNull(uploaded))
            assertEquals(confirmed, store.list(scope)[1])
            EvidenceUploader(store, transport).retry(scope, { true }) { _, _, _ -> fail() }
            assertEquals(4, attempts.size)
            assertTrue(store.list(scope.copy(actorId = "other")).isEmpty())
            assertTrue(store.list(scope.copy(claimId = "other")).isEmpty())
        }
    }

    @Test fun receiptForAlteredLocalPayloadCannotOverwriteStoredHash() {
        EvidenceStore(folder.newFolder()).use { store ->
            val source = folder.newFile().apply { writeBytes(byteArrayOf(1)) }
            val item = store.enqueue(scope, EvidenceKind.PHOTO, source, created, null, 0,
                EvidencePolicy(1, mapOf(EvidenceKind.PHOTO to 100L), 0), emptySet())
            val altered = item.copy(sha256 = "f".repeat(64))
            assertThrows(EvidenceProblem::class.java) {
                store.confirm(altered, EvidenceReceipt(item.id, "remote", altered.sha256))
            }
            assertEquals(item, store.list(scope).single())
        }
    }
}
