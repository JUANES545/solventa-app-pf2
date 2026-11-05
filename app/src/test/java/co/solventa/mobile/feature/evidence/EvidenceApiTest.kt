package co.solventa.mobile.feature.evidence

import android.app.Application
import co.solventa.mobile.feature.auth.AuthApi
import co.solventa.mobile.feature.auth.AuthGrant
import co.solventa.mobile.feature.auth.AuthRepository
import co.solventa.mobile.feature.auth.AuthStore
import co.solventa.mobile.feature.auth.AuthUser
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

internal fun evidenceAuth(actor: String = "actor-a", onRefresh: () -> Unit = {}): AuthRepository {
    val user = AuthUser(actor, "Synthetic", "synthetic@example.test")
    var stored = AuthGrant(user, "synthetic-access", "synthetic-refresh", Long.MAX_VALUE - 1, Long.MAX_VALUE)
    return AuthRepository(object : AuthApi {
        override fun register(name: String, email: String, password: String) = user
        override fun login(email: String, password: String) = stored
        override fun refresh(token: String): AuthGrant {
            onRefresh()
            return stored.copy(accessToken = "synthetic-access-rotated", refreshToken = "synthetic-refresh-rotated")
        }
        override fun current(token: String) = user
        override fun logout(accessToken: String, refreshToken: String) = Unit
    }, object : AuthStore {
        override fun read() = stored
        override fun write(grant: AuthGrant) { stored = grant }
        override fun clear() = Unit
    })
}

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class EvidenceApiTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun resumesAcceptedChunkAfterLostResponseAndNeverReplaysConfirmedBytes() = resume(false)
    @Test fun tokenExpiryDuringUploadRenewsAndResumesFromServerOffset() = resume(true)

    private fun resume(expireMidway: Boolean) = runBlocking {
        MockWebServer().use { server ->
            EvidenceStore(folder.newFolder()).use { store ->
                val scope = EvidenceScope("actor-a", UUID.randomUUID().toString())
                val source = folder.newFile().apply { writeBytes(ByteArray(8) { it.toByte() }) }
                val policy = EvidencePolicy(2, mapOf(EvidenceKind.PHOTO to 100L), 0)
                val item = store.enqueue(scope, EvidenceKind.PHOTO, source, Instant.now(), null, 0, policy, emptySet())
                val uploadId = UUID.randomUUID().toString()
                val remoteId = UUID.randomUUID().toString()
                var offset = 0L
                var confirmed = false
                var posts = 0
                var chunks = 0
                var expired = false
                var renewals = 0
                fun load() = JSONObject().put("id", uploadId).put("siniestroId", scope.claimId)
                    .put("estado", if (confirmed) "confirmada" else "pendiente").put("offset", offset)
                    .put("tamanoBytes", 8).put("mimeType", "image/jpeg").put("sha256", item.sha256)
                    .put("evidenciaId", if (confirmed) remoteId else JSONObject.NULL).toString()
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        assertEquals(if (renewals > 0) "Bearer synthetic-access-rotated" else "Bearer synthetic-access",
                            request.getHeader("Authorization"))
                        val path = request.path!!
                        if (path.endsWith("/cargas") && request.method == "POST") {
                            posts++
                            assertEquals(item.id, request.getHeader("Idempotency-Key"))
                            val body = JSONObject(request.body.readUtf8())
                            assertFalse(body.has("ubicacion"))
                            assertEquals(item.sha256, body.getString("sha256"))
                            return MockResponse().setResponseCode(201).setBody(load())
                        }
                        if (request.method == "PUT") {
                            if (expireMidway && offset == 4L && !expired) {
                                expired = true
                                return MockResponse().setResponseCode(401).setBody("""{"code":"sesion_invalida"}""")
                            }
                            assertEquals(offset.toString(), request.getHeader("Upload-Offset"))
                            assertEquals("4", request.getHeader("Content-Length"))
                            assertArrayEquals(ByteArray(4) { (it + offset).toByte() }, request.body.readByteArray())
                            offset += 4
                            chunks++
                            if (chunks == 1 && !expireMidway) return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                        }
                        if (path.endsWith("/confirmacion")) {
                            assertEquals(8, offset)
                            confirmed = true
                        }
                        return MockResponse().setBody(load())
                    }
                }
                val api = MobileEvidenceApi(evidenceAuth(onRefresh = { renewals++ }), server.url("/movil/v1").toString())
                val uploader = EvidenceUploader(store, api.transport(store, EvidenceConfiguration(policy, 4)))
                if (!expireMidway) {
                    try { uploader.retry(scope, { true }) { _, _, _ -> }; fail("Lost ACK must remain pending") }
                    catch (error: EvidenceProblem) { assertEquals(EvidenceError.NETWORK, error.reason) }
                    assertEquals(EvidenceStatus.PENDING, store.list(scope).single().status)
                }
                uploader.retry(scope, { true }) { _, _, _ -> }
                assertEquals(uploadId, store.uploadId(item))
                uploader.retry(scope, { true }) { _, _, _ -> fail() }
                assertEquals(1, posts)
                assertEquals(2, chunks)
                assertEquals(remoteId, store.list(scope).single().remoteId)
                assertEquals(if (expireMidway) 1 else 0, renewals)
            }
        }
    }

    @Test fun policyComesFromResponseAndInvalidServerPolicyIsRejected() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"maxCantidad":7,"maxChunkBytes":99,"maxVideoSeconds":45,"formatos":[{"mimeType":"image/jpeg","maxBytes":1234},{"mimeType":"video/mp4","maxBytes":5678}]}"""))
            val api = MobileEvidenceApi(evidenceAuth(), server.url("/movil/v1").toString())
            val configuration = api.configuration("actor-a")
            assertEquals(7, configuration.policy.maxCount)
            assertEquals(1234L, configuration.policy.maxBytes[EvidenceKind.DOCUMENT])
            assertEquals(5678L, configuration.policy.maxBytes[EvidenceKind.VIDEO])
            assertEquals(45_000, configuration.policy.maxVideoDurationMillis)
            assertEquals(99, configuration.maxChunkBytes)
            server.enqueue(MockResponse().setBody("""{"maxCantidad":7,"maxChunkBytes":0,"maxVideoSeconds":45,"formatos":[]}"""))
            try { api.configuration("actor-a"); fail() }
            catch (error: EvidenceProblem) { assertEquals(EvidenceError.PROTOCOL, error.reason) }
        }
    }

    @Test fun malformedClaimsAndMissingPolicyFieldsBecomeProtocolErrors() = runBlocking {
        MockWebServer().use { server ->
            val api = MobileEvidenceApi(evidenceAuth(), server.url("/movil/v1").toString())
            for (json in listOf("{}", """{"siniestros":[{"id":"invalid","tipoEvento":"a","fechaEvento":"b","descripcion":"c"}]}""")) {
                server.enqueue(MockResponse().setBody(json))
                try { api.claims("actor-a"); fail() }
                catch (error: EvidenceProblem) { assertEquals(EvidenceError.PROTOCOL, error.reason) }
            }
            server.enqueue(MockResponse().setBody("{}"))
            try { api.configuration("actor-a"); fail() }
            catch (error: EvidenceProblem) { assertEquals(EvidenceError.PROTOCOL, error.reason) }
        }
    }

    @Test fun cancellationReconcilesAConcurrentConfirmationAndNeverDeletesConfirmedBytes() = runBlocking {
        MockWebServer().use { server ->
            EvidenceStore(folder.newFolder()).use { store ->
                val scope = EvidenceScope("actor-a", UUID.randomUUID().toString())
                val source = folder.newFile().apply { writeBytes(byteArrayOf(1, 2)) }
                val policy = EvidencePolicy(2, mapOf(EvidenceKind.PHOTO to 100L), 0)
                val item = store.enqueue(scope, EvidenceKind.PHOTO, source, Instant.now(), null, 0, policy, emptySet())
                val uploadId = UUID.randomUUID().toString()
                val remoteId = UUID.randomUUID().toString()
                store.saveUploadId(item, uploadId)
                store.reject(item, EvidenceError.FORMAT)
                fun load(state: String) = JSONObject().put("id", uploadId).put("siniestroId", scope.claimId)
                    .put("estado", state).put("offset", 2).put("tamanoBytes", 2).put("mimeType", "image/jpeg")
                    .put("sha256", item.sha256).put("evidenciaId", if (state == "confirmada") remoteId else JSONObject.NULL).toString()
                server.enqueue(MockResponse().setBody(load("pendiente")))
                server.enqueue(MockResponse().setResponseCode(409).setBody("""{"code":"carga_confirmada"}"""))
                server.enqueue(MockResponse().setBody(load("confirmada")))
                val api = MobileEvidenceApi(evidenceAuth(), server.url("/movil/v1").toString())
                val receipt = checkNotNull(api.transport(store, EvidenceConfiguration(policy, 4)).cancel(item))
                store.confirm(item, receipt)
                assertEquals(EvidenceStatus.CONFIRMED, store.list(scope).single().status)
                assertTrue(store.file(item).exists())
                assertThrows(IllegalArgumentException::class.java) { store.removeRejected(item) }
                Unit
            }
        }
    }

    @Test fun lostCancellationAcknowledgementIsRecoveredFromCancelledState() = runBlocking {
        MockWebServer().use { server ->
            EvidenceStore(folder.newFolder()).use { store ->
                val scope = EvidenceScope("actor-a", UUID.randomUUID().toString())
                val source = folder.newFile().apply { writeBytes(byteArrayOf(1)) }
                val policy = EvidencePolicy(2, mapOf(EvidenceKind.PHOTO to 100L), 0)
                val item = store.enqueue(scope, EvidenceKind.PHOTO, source, Instant.now(), null, 0, policy, emptySet())
                val uploadId = UUID.randomUUID().toString()
                store.saveUploadId(item, uploadId)
                store.reject(item, EvidenceError.FORMAT)
                server.enqueue(MockResponse().setBody(JSONObject().put("id", uploadId).put("siniestroId", scope.claimId)
                    .put("estado", "cancelada").put("offset", 0).put("tamanoBytes", 1).put("mimeType", "image/jpeg")
                    .put("sha256", item.sha256).put("evidenciaId", JSONObject.NULL)
                    .put("ultimoResultado", JSONObject.NULL).toString()))
                val api = MobileEvidenceApi(evidenceAuth(), server.url("/movil/v1").toString())
                assertNull(api.transport(store, EvidenceConfiguration(policy, 4)).cancel(item))
                store.removeRejected(item)
                assertTrue(store.list(scope).isEmpty())
                assertEquals(1, server.requestCount)
            }
        }
    }
}
