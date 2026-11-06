package co.solventa.mobile.feature.evidence

import android.app.Application
import co.solventa.mobile.core.SyncResult
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
class DurableUploadRejectionTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = EvidenceScope("actor-a", UUID.randomUUID().toString())
    private val now = Instant.parse("2026-11-05T12:00:00Z")
    private val policy = EvidencePolicy(3, mapOf(EvidenceKind.PHOTO to 100L), 0)

    @Test fun durableStartRejectionContinuesQueueAndCanBeRemovedAfterIdenticalReplayWithoutAnUpload() = runBlocking {
        val directory = folder.newFolder()
        val first = add(directory, now.plusSeconds(86_400))
        val second = add(directory)
        val result = SyncResult(first.id, now, 400, "solicitud_invalida")
        val bodies = mutableListOf<String>()
        MockWebServer().use { server ->
            val uploadId = UUID.randomUUID().toString()
            val remote = UUID.randomUUID().toString()
            var offset = 0L
            var confirmed = false
            var deletes = 0
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method == "DELETE") deletes++
                    if (request.getHeader("Idempotency-Key") == first.id) {
                        bodies += request.body.readUtf8()
                        return rejection(result)
                    }
                    if (request.method == "PUT") offset = second.bytes
                    if (request.path!!.endsWith("/confirmacion")) confirmed = true
                    return MockResponse().setBody(load(second, uploadId, offset, remote.takeIf { confirmed }).toString())
                }
            }
            val api = MobileEvidenceApi(evidenceAuth(), server.url("/movil/v1").toString())
            EvidenceStore(directory) { now.plusSeconds(20) }.use { store ->
                EvidenceUploader(store, api.transport(store, EvidenceConfiguration(policy, 4))).retry(scope, { true }) { _, _, _ -> }
                assertEquals(EvidenceStatus.REJECTED, store.list(scope)[0].status)
                assertEquals(EvidenceError.INVALID_REQUEST, store.list(scope)[0].rejection)
                assertEquals(EvidenceStatus.CONFIRMED, store.list(scope)[1].status)
                assertNull(store.uploadId(first))
                assertNull(store.list(scope)[0].remoteId)
                assertEquals(result, store.list(scope)[0].serverResult)
            }
            EvidenceStore(directory) { now.plusSeconds(100) }.use { store ->
                val original = store.list(scope).first()
                val transport = api.transport(store, EvidenceConfiguration(policy, 4))
                try { transport.confirmed(scope, first.id); fail() }
                catch (error: EvidenceHttpProblem) {
                    assertTrue(error.permanent)
                    assertEquals(result, error.serverResult)
                }
                assertNull(transport.cancel(original))
                assertEquals(original, store.list(scope).first())
                assertEquals(now.plusSeconds(20), original.lastResultAt)
                assertEquals(3, bodies.size)
                assertEquals(1, bodies.distinct().size)
                assertEquals(0, deletes)
                val rejectedFile = store.file(original)
                store.removeRejected(original)
                assertFalse(rejectedFile.exists())
                assertEquals(second.id, store.list(scope).single().id)
                assertEquals(remote, store.list(scope).single().remoteId)
            }
        }
    }

    @Test fun missingForeignOrMismatchedDurabilityNeverMakesAnInvalidStartTerminal() = runBlocking {
        MockWebServer().use { server ->
            val api = MobileEvidenceApi(evidenceAuth(), server.url("/movil/v1").toString())
            for (variant in listOf("missing", "null", "foreign", "status", "code", "different-code")) {
                val directory = folder.newFolder()
                val item = add(directory)
                val result = SyncResult(item.id, now, 400, "solicitud_invalida").json()
                val body = JSONObject().put("code", "solicitud_invalida")
                when (variant) {
                    "missing" -> Unit
                    "null" -> body.put("ultimoResultado", JSONObject.NULL)
                    "foreign" -> body.put("ultimoResultado", result.put("operacionId", UUID.randomUUID().toString()))
                    "status" -> body.put("ultimoResultado", result.put("httpStatus", 422))
                    "code" -> body.put("ultimoResultado", result.put("code", "another"))
                    else -> body.put("code", "another").put("ultimoResultado", result.put("code", "another"))
                }
                server.enqueue(MockResponse().setResponseCode(400).setBody(body.toString()))
                EvidenceStore(directory).use { store ->
                    try {
                        EvidenceUploader(store, api.transport(store, EvidenceConfiguration(policy, 4)))
                            .retry(scope, { true }) { _, _, _ -> fail() }
                        fail()
                    } catch (error: EvidenceHttpProblem) { assertFalse(variant, error.permanent) }
                    assertEquals(EvidenceStatus.PENDING, store.list(scope).single().status)
                }
            }
        }
    }

    @Test fun otherPhasesStayRecoverableEvenWithTheSameValidDurable400() = runBlocking {
        for (phase in listOf("GET", "PUT", "confirmation")) {
            val directory = folder.newFolder()
            val item = add(directory)
            val uploadId = UUID.randomUUID().toString()
            EvidenceStore(directory).use { it.saveUploadId(item, uploadId) }
            MockWebServer().use { server ->
                var offset = 0L
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.method == phase || phase == "confirmation" && request.path!!.endsWith("/confirmacion")) {
                            return rejection(SyncResult(item.id, now, 400, "solicitud_invalida"))
                        }
                        if (request.method == "PUT") offset = item.bytes
                        return MockResponse().setBody(load(item, uploadId, offset).toString())
                    }
                }
                EvidenceStore(directory).use { store ->
                    try {
                        EvidenceUploader(store, MobileEvidenceApi(evidenceAuth(), server.url("/movil/v1").toString())
                            .transport(store, EvidenceConfiguration(policy, 4))).retry(scope, { true }) { _, _, _ -> }
                        fail()
                    } catch (error: EvidenceHttpProblem) { assertFalse(phase, error.permanent) }
                    assertEquals(EvidenceStatus.PENDING, store.list(scope).single().status)
                }
            }
        }
    }

    @Test fun losingAuthorizationDuringRemovalDoesNotAuthorizeLocalDeletion() = runBlocking {
        val directory = folder.newFolder()
        val item = add(directory)
        EvidenceStore(directory).use { store ->
            store.reject(item, EvidenceError.INVALID_REQUEST, "solicitud_invalida",
                SyncResult(item.id, now, 400, "solicitud_invalida"))
            val rejected = store.list(scope).single()
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(404).setBody("""{"code":"siniestro_no_encontrado"}"""))
                val transport = MobileEvidenceApi(evidenceAuth(), server.url("/movil/v1").toString())
                    .transport(store, EvidenceConfiguration(policy, 4))
                try { transport.cancel(rejected); fail() }
                catch (error: EvidenceHttpProblem) {
                    assertEquals(404, error.status)
                    assertFalse(error.permanent)
                }
                assertEquals(rejected, store.list(scope).single())
                assertTrue(store.file(rejected).exists())
            }
        }
    }

    private fun add(directory: File, capturedAt: Instant = now): EvidenceItem = EvidenceStore(directory).use { store ->
        val source = folder.newFile().apply { writeBytes(byteArrayOf(1, 2)) }
        store.enqueue(scope, EvidenceKind.PHOTO, source, capturedAt, null, 0, policy, emptySet())
    }

    private fun rejection(result: SyncResult) = MockResponse().setResponseCode(400)
        .setBody(JSONObject().put("code", result.code).put("ultimoResultado", result.json()).toString())

    private fun load(item: EvidenceItem, uploadId: String, offset: Long, remote: String? = null) =
        JSONObject().put("id", uploadId).put("siniestroId", item.scope.claimId)
            .put("estado", if (remote == null) "pendiente" else "confirmada").put("offset", offset)
            .put("tamanoBytes", item.bytes).put("mimeType", item.kind.mime).put("sha256", item.sha256)
            .put("evidenciaId", remote ?: JSONObject.NULL)
}
