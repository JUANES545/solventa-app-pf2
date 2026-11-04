package co.solventa.mobile.feature.claim

import android.app.Application
import co.solventa.mobile.feature.evidence.EvidenceClaim
import co.solventa.mobile.feature.evidence.MobileEvidenceApi
import co.solventa.mobile.feature.evidence.evidenceAuth
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

internal val statusClaim = EvidenceClaim("c0ca4037-5e08-4ddf-967a-377b5e420101", "Choque",
    "2026-03-01T10:00:00Z", "Descripcion sintetica.")
internal val statusDate: Instant = Instant.parse("2026-03-03T11:30:00Z")
internal fun statusResponse(
    id: String = statusClaim.id, progress: ClaimProgress = ClaimProgress.REPORTED, date: Instant = statusDate,
): JSONObject = JSONObject().put("id", id).put("estado", progress.code)
    .put("descripcion", "Descripcion del estado desde el servicio.").put("actualizadoEn", date.toString())
internal fun statusList(vararg claims: EvidenceClaim = arrayOf(statusClaim)): String = JSONObject().put("siniestros",
    JSONArray(claims.map { JSONObject().put("id", it.id).put("tipoEvento", it.type)
        .put("fechaEvento", it.date).put("descripcion", it.description) })).toString()
internal fun statusApi(server: MockWebServer) =
    MobileClaimApi(MobileEvidenceApi(evidenceAuth(), server.url("/movil/v1").toString()))

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ClaimStatusApiTest {
    @Test fun readsOnlyAuthorizedResourcesWithoutMutationsAndKeepsServerDates() = runBlocking {
        MockWebServer().use { server ->
            val api = statusApi(server)
            server.enqueue(MockResponse().setBody(statusList()))
            assertEquals(listOf(statusClaim), api.claims("actor-a"))
            assertEquals("/movil/v1/siniestros", server.takeRequest().path)
            for (progress in ClaimProgress.entries) {
                server.enqueue(MockResponse().setBody(statusResponse(progress = progress).toString()))
                val detail = api.status("actor-a", statusClaim.id)
                assertEquals(progress, detail.progress)
                assertEquals(statusDate, detail.updatedAt)
                val request = server.takeRequest()
                assertEquals("GET", request.method)
                assertEquals("/movil/v1/siniestros/${statusClaim.id}/estado", request.path)
                assertEquals("no-store", request.getHeader("Cache-Control"))
                assertNotNull(request.getHeader("Authorization"))
                assertEquals(0, request.body.size)
                assertNull(request.getHeader("Idempotency-Key"))
            }
        }
    }

    @Test fun wrongIdentityUnknownStateAndInvalidFieldsCannotBecomeAStatus() = runBlocking {
        MockWebServer().use { server ->
            val api = statusApi(server)
            val invalid = listOf(
                statusResponse(id = UUID.randomUUID().toString()),
                statusResponse().put("estado", "registrado"),
                statusResponse().put("estado", "inventado"),
                statusResponse().put("descripcion", JSONObject.NULL),
                statusResponse().put("descripcion", 7),
                statusResponse().put("descripcion", " "),
                statusResponse().put("actualizadoEn", "2026-03-03T11:30:00"),
                statusResponse().put("actualizadoEn", "2026-02-30T11:30:00Z"),
                statusResponse().apply { remove("actualizadoEn") },
            ).map(JSONObject::toString) + listOf("{}", "<html>error</html>")
            for (body in invalid) {
                server.enqueue(MockResponse().setBody(body))
                try { api.status("actor-a", statusClaim.id); fail("Invalid status was accepted") }
                catch (error: Exception) { assertEquals(ClaimStatusError.PROTOCOL, claimStatusError(error)) }
            }
            val before = server.requestCount
            try { api.status("actor-a", "../otro"); fail() }
            catch (error: Exception) { assertEquals(ClaimStatusError.PROTOCOL, claimStatusError(error)) }
            assertEquals(before, server.requestCount)
        }
    }

    @Test fun unavailableIsDistinctFromWrongRouteAuthorizationAndServiceFailure() = runBlocking {
        MockWebServer().use { server ->
            val api = statusApi(server)
            for ((status, body, expected) in listOf(
                Triple(404, """{"code":"siniestro_no_encontrado"}""", ClaimStatusError.UNAVAILABLE),
                Triple(404, """{"code":"ruta_no_encontrada"}""", ClaimStatusError.NETWORK),
                Triple(404, "", ClaimStatusError.NETWORK),
                Triple(403, "", ClaimStatusError.SESSION),
                Triple(503, "<html>Unavailable</html>", ClaimStatusError.NETWORK),
                Triple(503, """{"code":"siniestro_no_encontrado"}""", ClaimStatusError.NETWORK),
            )) {
                server.enqueue(MockResponse().setResponseCode(status).setBody(body))
                try { api.status("actor-a", statusClaim.id); fail() }
                catch (error: Exception) { assertEquals(expected, claimStatusError(error)) }
            }
        }
    }

    @Test fun sessionRefreshIsBoundedAndAnotherActorCannotUseTheSession() = runBlocking {
        MockWebServer().use { server ->
            var renewals = 0
            val api = MobileClaimApi(MobileEvidenceApi(evidenceAuth(onRefresh = { renewals++ }),
                server.url("/movil/v1").toString()))
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setBody(statusResponse().toString()))
            assertEquals(statusDate, api.status("actor-a", statusClaim.id).updatedAt)
            assertEquals(1, renewals)
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setResponseCode(401))
            try { api.status("actor-a", statusClaim.id); fail() }
            catch (error: Exception) { assertEquals(ClaimStatusError.SESSION, claimStatusError(error)) }
            assertEquals(2, renewals)
            val requests = server.requestCount
            try { api.status("actor-b", statusClaim.id); fail() }
            catch (error: Exception) { assertEquals(ClaimStatusError.SESSION, claimStatusError(error)) }
            assertEquals(requests, server.requestCount)
        }
    }

    @Test fun emptyListIsSuccessButInvalidListNeverLooksEmpty() = runBlocking {
        MockWebServer().use { server ->
            val api = statusApi(server)
            server.enqueue(MockResponse().setBody(statusList(*emptyArray())))
            assertTrue(api.claims("actor-a").isEmpty())
            for (body in listOf("{}", statusList(statusClaim, statusClaim),
                statusList(statusClaim.copy(date = "incorrecta")), statusList(statusClaim.copy(type = "")))) {
                server.enqueue(MockResponse().setBody(body))
                try { api.claims("actor-a"); fail() }
                catch (error: Exception) { assertEquals(ClaimStatusError.PROTOCOL, claimStatusError(error)) }
            }
        }
    }

    @Test fun cancellationAndUnexpectedErrorsAreNotHidden() {
        try { claimStatusError(kotlinx.coroutines.CancellationException()); fail() }
        catch (_: kotlinx.coroutines.CancellationException) { }
        try { claimStatusError(IllegalStateException()); fail() }
        catch (_: IllegalStateException) { }
        assertEquals(ClaimStatusError.STORAGE, claimStatusError(
            co.solventa.mobile.feature.auth.AuthProblem(co.solventa.mobile.feature.auth.AuthError.STORAGE)))
    }
}
