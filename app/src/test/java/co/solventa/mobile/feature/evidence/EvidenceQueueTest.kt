package co.solventa.mobile.feature.evidence

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import co.solventa.mobile.feature.auth.AuthError
import co.solventa.mobile.feature.auth.AuthHttpError
import co.solventa.mobile.feature.auth.AuthProblem
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
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
            assertNull(store.list(scope).single().lastError)
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
            store.fail(ordered.first(), EvidenceError.NETWORK)
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
            assertNull(rejected.lastError)
            store.removeRejected(rejected)
            assertEquals(EvidenceStatus.CONFIRMED, store.list(scope).single().status)
        }
    }

    @Test fun recoverableFailurePersistsOnlyForAttemptedItemWithoutAllowingRemoval() = runBlocking {
        val dir = folder.newFolder()
        val failure = EvidenceHttpProblem(503, null, false, EvidenceError.NETWORK)
        val attempted = EvidenceStore(dir).use { store ->
            repeat(3) { add(store) }
            val first = store.list(scope).first()
            val transport = object : EvidenceTransport {
                override suspend fun confirmed(scope: EvidenceScope, localId: String): EvidenceReceipt? = throw failure
                override suspend fun upload(item: EvidenceItem, file: File, progress: (Long) -> Unit): EvidenceReceipt =
                    throw AssertionError("Reconciliation failed; upload must not run")
            }
            try { EvidenceUploader(store, transport).retry(scope, { true }) { _, _, _ -> }; fail() }
            catch (error: EvidenceHttpProblem) { assertSame(failure, error) }
            first.id
        }
        EvidenceStore(dir).use { store ->
            val items = store.list(scope)
            assertEquals(listOf(EvidenceError.NETWORK, null, null), items.map { it.lastError })
            assertTrue(items.all { it.status == EvidenceStatus.PENDING && it.remoteId == null && it.rejection == null })
            assertEquals(attempted, items.first().id)
            assertThrows(IllegalArgumentException::class.java) { store.removeRejected(items.first()) }
            assertTrue(store.list(scope.copy(actorId = "another")).isEmpty())
            assertTrue(store.list(scope.copy(claimId = "another")).isEmpty())
            assertThrows(IllegalStateException::class.java) {
                store.fail(items.first().copy(scope = scope.copy(actorId = "another")), EvidenceError.SESSION)
            }
            assertArrayEquals(byteArrayOf(1, 2, 3), store.file(items.first()).readBytes())
        }
    }

    @Test fun cancellationAndUnknownExceptionsPropagateWithoutReplacingTheLastError() = runBlocking {
        for (failure in listOf(CancellationException("cancelled"), IllegalStateException("unexpected"))) {
            EvidenceStore(folder.newFolder()).use { store ->
                add(store)
                val transport = object : EvidenceTransport {
                    override suspend fun confirmed(scope: EvidenceScope, localId: String): EvidenceReceipt? = throw failure
                    override suspend fun upload(item: EvidenceItem, file: File, progress: (Long) -> Unit): EvidenceReceipt =
                        throw AssertionError("Must not upload")
                }
                for (previous in listOf(null, EvidenceError.NETWORK)) {
                    val item = store.list(scope).single()
                    if (previous != null) store.fail(item, previous)
                    val before = store.list(scope).single()
                    try { EvidenceUploader(store, transport).retry(scope, { true }) { _, _, _ -> }; fail() }
                    catch (error: Exception) { assertSame(failure, error) }
                    assertEquals(previous, store.list(scope).single().lastError)
                    assertEquals(before, store.list(scope).single())
                }
            }
        }
    }

    @Test fun knownFailuresKeepTheirCategoryAndOriginalException() = runBlocking {
        for ((failure, reason) in listOf(
            EvidenceProblem(EvidenceError.NETWORK) to EvidenceError.NETWORK,
            AuthHttpError(401) to EvidenceError.SESSION,
            AuthProblem(AuthError.EXPIRED) to EvidenceError.SESSION,
            org.json.JSONException("invalid receipt") to EvidenceError.PROTOCOL,
            IllegalArgumentException("invalid receipt") to EvidenceError.PROTOCOL,
            SQLiteException("storage unavailable") to EvidenceError.STORAGE,
            IOException("file unavailable") to EvidenceError.STORAGE,
            SecurityException("permission denied") to EvidenceError.PERMISSION,
        )) {
            EvidenceStore(folder.newFolder()).use { store ->
                add(store)
                val transport = object : EvidenceTransport {
                    override suspend fun confirmed(scope: EvidenceScope, localId: String): EvidenceReceipt? = null
                    override suspend fun upload(item: EvidenceItem, file: File, progress: (Long) -> Unit): EvidenceReceipt =
                        throw failure
                }
                try { EvidenceUploader(store, transport).retry(scope, { true }) { _, _, _ -> }; fail() }
                catch (error: Exception) { assertSame(failure, error) }
                assertEquals(reason, store.list(scope).single().lastError)
            }
        }
    }

    @Test fun migratingLegacyQueueKeepsFilesReceiptsRejectionsAndUploadIdentity() {
        val dir = folder.newFolder()
        val items = EvidenceStore(dir).use { store ->
            val pending = add(store)
            val confirmed = add(store)
            val rejected = add(store)
            store.saveUploadId(pending, pending.id)
            store.confirm(confirmed, EvidenceReceipt(confirmed.id, "remote", confirmed.sha256))
            store.reject(rejected, EvidenceError.FORMAT)
            store.list(scope)
        }
        SQLiteDatabase.openDatabase(File(dir, "claim-evidence/queue.db").path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("ALTER TABLE evidence RENAME TO current_evidence")
            it.execSQL("""
                CREATE TABLE evidence (
                    id TEXT PRIMARY KEY, actor TEXT NOT NULL, claim_id TEXT NOT NULL,
                    kind TEXT NOT NULL, captured_at TEXT NOT NULL, latitude REAL, longitude REAL,
                    measured_at TEXT, bytes INTEGER NOT NULL, sha256 TEXT NOT NULL, duration INTEGER NOT NULL,
                    remote_id TEXT, upload_id TEXT, rejection TEXT, UNIQUE(actor, claim_id, id)
                )
            """.trimIndent())
            it.execSQL("""
                INSERT INTO evidence SELECT id, actor, claim_id, kind, captured_at, latitude, longitude,
                    measured_at, bytes, sha256, duration, remote_id, upload_id, rejection FROM current_evidence
            """.trimIndent())
            it.execSQL("DROP TABLE current_evidence")
        }
        repeat(2) {
            EvidenceStore(dir).use { store ->
                assertEquals(items.map { it.copy(queuedAt = null, lastResultAt = null) }, store.list(scope))
                for (item in items) assertArrayEquals(byteArrayOf(1, 2, 3), store.file(item).readBytes())
                val pending = items.single { it.status == EvidenceStatus.PENDING }
                assertEquals(pending.id, store.uploadId(pending))
                for (item in items.filter { it.status != EvidenceStatus.PENDING }) {
                    assertThrows(IllegalStateException::class.java) { store.fail(item, EvidenceError.NETWORK) }
                }
            }
        }
    }
}
