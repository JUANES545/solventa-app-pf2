package co.solventa.mobile.feature.evidence

import android.app.Application
import co.solventa.mobile.core.SyncResult
import co.solventa.mobile.feature.claim.*
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ServerSyncResultTest {
    @get:Rule val folder = TemporaryFolder()
    private val serverAt = Instant.parse("2026-11-05T12:00:00Z")
    private val observedAt = serverAt.plusSeconds(60)

    @Test fun claimReplayPreservesServerDateSeparatelyFromLocalObservationAndSupportsLegacy() = runBlocking {
        MockWebServer().use { server ->
            val directory = folder.newFolder()
            val report = ClaimStore(directory) { observedAt }.use { it.enqueue("actor-a", payload()) }
            val api = MobileClaimApi(MobileEvidenceApi(evidenceAuth(), server.url("/movil/v1").toString()))
            val result = SyncResult(report.operation, serverAt, 201, null)
            val body = JSONObject(response(report)).put("ultimoResultado", result.json())
            server.enqueue(MockResponse().setBody(body.toString()))
            ClaimStore(directory) { observedAt }.use { it.confirm(report, api.send(report)) }
            ClaimStore(directory).use { store ->
                val restored = store.list("actor-a").single()
                assertEquals(result, restored.serverResult)
                assertEquals(result, restored.receipt?.serverResult)
                assertEquals(observedAt, restored.lastResultAt)
                assertEquals(Instant.parse("2026-11-04T18:00:00Z"), restored.receipt?.registeredAt)
            }
            server.enqueue(MockResponse().setBody(response(report)))
            assertNull(api.send(report).serverResult)
            server.enqueue(MockResponse().setBody(JSONObject(response(report)).put("ultimoResultado", JSONObject.NULL).toString()))
            assertNull(api.send(report).serverResult)
        }
    }

    @Test fun rejectedOperationKeepsServerDateAndExplicitCorrectionUsesANewUuid() = runBlocking {
        MockWebServer().use { server ->
            val directory = folder.newFolder()
            val report = ClaimStore(directory).use { it.enqueue("actor-a", payload()) }
            val result = SyncResult(report.operation, serverAt, 422, "fecha_evento_invalida")
            server.enqueue(MockResponse().setResponseCode(422).setBody(JSONObject()
                .put("code", result.code).put("ultimoResultado", result.json()).toString()))
            val api = MobileClaimApi(MobileEvidenceApi(evidenceAuth(), server.url("/movil/v1").toString()))
            try { api.send(report); fail() }
            catch (error: EvidenceHttpProblem) {
                ClaimStore(directory) { observedAt }.use { store ->
                    store.fail(report, claimError(error), error.code, error.serverResult)
                }
            }
            ClaimStore(directory).use { store ->
                val rejected = store.list("actor-a").single()
                assertEquals(result, rejected.serverResult)
                assertEquals(observedAt, rejected.lastResultAt)
                assertTrue(rejected.correctable)
                val correction = store.enqueue("actor-a", payload().copy(place = "Corrected"), report.operation)
                assertNotEquals(report.operation, correction.operation)
                assertNull(correction.serverResult)
                assertEquals(result, store.list("actor-a").first().serverResult)
                assertEquals(correction.operation, store.list("actor-a").first().supersededBy)
            }
        }
    }

    @Test fun invalidForeignOrInconsistentMetadataCannotConfirmOrClassifyATerminalConflict() = runBlocking {
        MockWebServer().use { server ->
            val report = ClaimReport(UUID.randomUUID().toString(), "actor-a", payload())
            val api = MobileClaimApi(MobileEvidenceApi(evidenceAuth(), server.url("/movil/v1").toString()))
            val valid = SyncResult(report.operation, serverAt, 201, null)
            val variants = listOf(
                valid.json().put("operacionId", UUID.randomUUID().toString()),
                valid.json().put("fecha", "not-a-date"),
                valid.json().put("fecha", "2026-11-05T12:00:00-05:00"),
                valid.json().put("httpStatus", "201"),
                valid.json().put("httpStatus", 503),
                valid.json().put("code", "unexpected"),
                valid.json().apply { remove("code") },
            )
            for (invalid in variants) {
                server.enqueue(MockResponse().setBody(JSONObject(response(report)).put("ultimoResultado", invalid).toString()))
                try { api.send(report); fail() }
                catch (error: Exception) { assertEquals(ClaimError.PROTOCOL, claimError(error)) }
            }
            server.enqueue(MockResponse().setResponseCode(409).setBody(JSONObject().put("code", "idempotencia_conflictiva")
                .put("ultimoResultado", valid.json()).toString()))
            try { api.send(report); fail() }
            catch (error: EvidenceHttpProblem) {
                assertFalse(error.permanent)
                assertNull(error.serverResult)
                assertEquals(ClaimError.PROTOCOL, claimError(error))
            }
            server.enqueue(MockResponse().setResponseCode(503).setBody("""{"code":"siniestro_no_integro"}"""))
            try { api.send(report); fail() }
            catch (error: EvidenceHttpProblem) {
                assertFalse(error.permanent)
                assertNull(error.serverResult)
                assertEquals(ClaimError.NETWORK, claimError(error))
            }
        }
    }

    @Test fun lostDefinitiveConfirmationRejectionIsRecoveredByGetWithoutUploadingAgain() = runBlocking {
        val directory = folder.newFolder()
        val item = add(directory)
        val uploadId = UUID.randomUUID().toString()
        val result = SyncResult(item.id, serverAt, 422, "integridad_invalida")
        EvidenceStore(directory).use { store ->
            store.saveUploadId(item, uploadId)
            store.fail(item, EvidenceError.NETWORK)
        }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(load(item, uploadId, result, item.bytes).toString()))
            EvidenceStore(directory) { observedAt }.use { store ->
                val uploader = EvidenceUploader(store, MobileEvidenceApi(evidenceAuth(), server.url("/movil/v1").toString())
                    .transport(store, EvidenceConfiguration(policy, 4)))
                uploader.retry(item.scope, { true }) { _, _, _ -> fail("Rejection must not upload") }
                uploader.retry(item.scope, { true }) { _, _, _ -> fail() }
                assertEquals(1, server.requestCount)
                assertEquals("GET", server.takeRequest().method)
            }
        }
        EvidenceStore(directory).use { store ->
            val rejected = store.list(item.scope).single()
            assertEquals(EvidenceStatus.REJECTED, rejected.status)
            assertEquals(result, rejected.serverResult)
            assertEquals(observedAt, rejected.lastResultAt)
            assertEquals("integridad_invalida", rejected.errorCode)
            assertEquals(uploadId, store.uploadId(rejected))
            assertArrayEquals(byteArrayOf(1, 2), store.file(rejected).readBytes())
        }
    }

    @Test fun recoverableIncompleteResultResumesAndConfirmedGetRecoversItsOriginalDate() = runBlocking {
        val directory = folder.newFolder()
        val item = add(directory)
        val uploadId = UUID.randomUUID().toString()
        val remote = UUID.randomUUID().toString()
        val confirmed = SyncResult(item.id, serverAt.plusSeconds(10), 200, null)
        EvidenceStore(directory).use { it.saveUploadId(item, uploadId) }
        MockWebServer().use { server ->
            var offset = 0L
            var puts = 0
            var confirmations = 0
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method == "PUT") { puts++; offset = item.bytes }
                    if (request.path!!.endsWith("/confirmacion")) confirmations++
                    val result = if (confirmations > 0) confirmed
                        else if (offset > 0) SyncResult(item.id, serverAt.plusSeconds(5), 200, null)
                        else SyncResult(item.id, serverAt, 409, "carga_incompleta")
                    return MockResponse().setBody(load(item, uploadId, result, offset,
                        remote.takeIf { confirmations > 0 }).toString())
                }
            }
            EvidenceStore(directory) { observedAt }.use { store ->
                val transport = MobileEvidenceApi(evidenceAuth(), server.url("/movil/v1").toString())
                    .transport(store, EvidenceConfiguration(policy, 4))
                val receipt = transport.upload(item, store.file(item)) {}
                assertEquals(confirmed, receipt.serverResult)
                // Simulate process death before persisting the successful confirmation response.
            }
            EvidenceStore(directory) { observedAt.plusSeconds(5) }.use { store ->
                val uploader = EvidenceUploader(store, MobileEvidenceApi(evidenceAuth(), server.url("/movil/v1").toString())
                    .transport(store, EvidenceConfiguration(policy, 4)))
                uploader.retry(item.scope, { true }) { _, _, _ -> fail() }
                assertEquals(confirmed, store.list(item.scope).single().serverResult)
                assertEquals(observedAt.plusSeconds(5), store.list(item.scope).single().lastResultAt)
                val requests = server.requestCount
                uploader.retry(item.scope, { true }) { _, _, _ -> fail() }
                assertEquals(requests, server.requestCount)
                assertEquals(1, puts)
                assertEquals(1, confirmations)
            }
        }
    }

    private val policy = EvidencePolicy(3, mapOf(EvidenceKind.PHOTO to 100L), 0)

    private fun add(directory: File): EvidenceItem = EvidenceStore(directory).use { store ->
        val source = folder.newFile().apply { writeBytes(byteArrayOf(1, 2)) }
        store.enqueue(EvidenceScope("actor-a", UUID.randomUUID().toString()), EvidenceKind.PHOTO,
            source, serverAt, null, 0, policy, emptySet())
    }

    private fun load(item: EvidenceItem, uploadId: String, result: SyncResult, offset: Long, remote: String? = null) =
        JSONObject().put("id", uploadId).put("siniestroId", item.scope.claimId)
            .put("estado", if (remote != null) "confirmada" else "pendiente").put("offset", offset)
            .put("tamanoBytes", item.bytes).put("mimeType", item.kind.mime).put("sha256", item.sha256)
            .put("evidenciaId", remote ?: JSONObject.NULL).put("ultimoResultado", result.json())
}
