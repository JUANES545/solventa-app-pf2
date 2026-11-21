package co.solventa.mobile.feature.identity

import android.app.Application
import co.solventa.mobile.feature.auth.AuthHttpError
import co.solventa.mobile.feature.auth.AuthProblem
import co.solventa.mobile.feature.evidence.EvidenceProblem
import co.solventa.mobile.feature.evidence.MobileEvidenceApi
import co.solventa.mobile.feature.evidence.evidenceAuth
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

internal val pendingIdentity = IdentityVerification("b77d84cb-6ed8-490b-bcdd-09d147a79e33",
    IdentityStatus.PENDING, 0, 3, false, null)
internal val rejectedIdentity = pendingIdentity.copy(status = IdentityStatus.REJECTED, attempts = 1,
    retryAllowed = true, reason = IdentityReason.DOCUMENT)
internal val approvedIdentity = pendingIdentity.copy(status = IdentityStatus.APPROVED, attempts = 2)
internal fun identityJson(value: IdentityVerification = pendingIdentity) = JSONObject()
    .put("verificacionId", value.id).put("estado", value.status.code)
    .put("intentos", value.attempts).put("maxIntentos", value.maxAttempts)
    .put("reintentoPermitido", value.retryAllowed).put("simulada", true)
    .put("motivo", value.reason?.code ?: JSONObject.NULL)

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class MobileIdentityApiTest {
    @Test fun authenticatedContractSendsOnlyImmutableSyntheticReferencesAndReadsCanonicalState() = runBlocking {
        MockWebServer().use { server ->
            val api = MobileIdentityApi(MobileEvidenceApi(evidenceAuth(), server.url("/movil/v1").toString()))
            server.enqueue(MockResponse().setBody(identityJson().toString()))
            assertEquals(pendingIdentity, api.start("actor-a"))
            val start = server.takeRequest()
            assertEquals("POST", start.method)
            assertEquals("/movil/v1/verificaciones-identidad", start.path)
            assertEquals("{}", start.body.readUtf8())
            val attemptId = UUID.randomUUID().toString()
            for ((value, key) in listOf(rejectedIdentity to attemptId, rejectedIdentity to attemptId,
                approvedIdentity to UUID.randomUUID().toString())) {
                server.enqueue(MockResponse().setBody(identityJson(value).toString()))
                assertEquals(value, api.submit("actor-a", value.id, key))
                val request = server.takeRequest()
                assertEquals("POST", request.method)
                assertEquals("/movil/v1/verificaciones-identidad/${value.id}/intentos", request.path)
                assertEquals("Bearer synthetic-access", request.getHeader("Authorization"))
                assertEquals("no-store", request.getHeader("Cache-Control"))
                val body = JSONObject(request.body.readUtf8())
                assertEquals(setOf("intentoId", "documentoRef", "selfieRef"), body.keys().asSequence().toSet())
                assertEquals(key, body.getString("intentoId"))
                assertEquals("fixture:documento-v1", body.getString("documentoRef"))
                assertEquals("fixture:selfie-v1", body.getString("selfieRef"))
            }
            server.enqueue(MockResponse().setBody(identityJson(approvedIdentity).toString()))
            assertEquals(approvedIdentity, api.current("actor-a", pendingIdentity.id))
            val get = server.takeRequest()
            assertEquals("GET", get.method)
            assertEquals(0, get.body.size)
        }
    }

    @Test fun invalidOrContradictoryServerResultsNeverBecomeApproval() = runBlocking {
        MockWebServer().use { server ->
            val api = MobileIdentityApi(MobileEvidenceApi(evidenceAuth(), server.url("/movil/v1").toString()))
            val invalid = listOf(
                identityJson().put("verificacionId", UUID.randomUUID().toString()),
                identityJson().put("verificacionId", "../other"),
                identityJson().put("simulada", false),
                identityJson().put("simulada", "true"),
                identityJson().put("estado", "validado"),
                identityJson().put("estado", "aprobado"),
                identityJson().put("intentos", 1),
                identityJson().put("intentos", 0.5),
                identityJson().put("maxIntentos", 5),
                identityJson().put("reintentoPermitido", true),
                identityJson(rejectedIdentity).put("motivo", JSONObject.NULL),
                identityJson(rejectedIdentity).put("motivo", "otro"),
                identityJson(rejectedIdentity).put("intentos", 3),
                identityJson(approvedIdentity).put("motivo", "documento_no_valido"),
                identityJson(approvedIdentity).put("reintentoPermitido", true),
                identityJson().apply { remove("motivo") },
            )
            for (body in invalid) {
                server.enqueue(MockResponse().setBody(body.toString()))
                try { api.current("actor-a", pendingIdentity.id); fail("Invalid result accepted") }
                catch (error: EvidenceProblem) { assertEquals(IdentityError.PROTOCOL, identityError(error)) }
            }
            val requests = server.requestCount
            try { api.current("actor-b", pendingIdentity.id); fail() } catch (_: AuthProblem) {}
            try { api.current("actor-a", "../other"); fail() } catch (_: EvidenceProblem) {}
            assertEquals(requests, server.requestCount)
        }
    }

    @Test fun errorsAndTokenRenewalAreBoundedWithoutChangingAttemptBody() = runBlocking {
        MockWebServer().use { server ->
            var renewals = 0
            val api = MobileIdentityApi(MobileEvidenceApi(evidenceAuth(onRefresh = { renewals++ }),
                server.url("/movil/v1").toString()))
            val attemptId = UUID.randomUUID().toString()
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setBody(identityJson(rejectedIdentity).toString()))
            assertEquals(rejectedIdentity, api.submit("actor-a", pendingIdentity.id, attemptId))
            assertEquals(1, renewals)
            val first = server.takeRequest()
            val second = server.takeRequest()
            assertEquals(first.body.readUtf8(), second.body.readUtf8())
            assertEquals("Bearer synthetic-access-rotated", second.getHeader("Authorization"))
            for ((status, code, expected) in listOf(
                Triple(400, "solicitud_invalida", IdentityError.PROTOCOL),
                Triple(403, "acceso_denegado", IdentityError.DENIED),
                Triple(404, "verificacion_no_encontrada", IdentityError.NOT_FOUND),
                Triple(409, "conflicto_idempotencia", IdentityError.CONFLICT),
                Triple(409, "transicion_invalida", IdentityError.CONFLICT),
                Triple(503, "servicio_no_disponible", IdentityError.UNAVAILABLE),
            )) {
                server.enqueue(MockResponse().setResponseCode(status).setBody("""{"code":"$code"}"""))
                try { api.current("actor-a", pendingIdentity.id); fail() }
                catch (error: EvidenceProblem) { assertEquals(expected, identityError(error)) }
            }
            repeat(2) { server.enqueue(MockResponse().setResponseCode(401)) }
            try { api.current("actor-a", pendingIdentity.id); fail() } catch (_: AuthHttpError) {}
            assertEquals(2, renewals)
        }
    }
}
