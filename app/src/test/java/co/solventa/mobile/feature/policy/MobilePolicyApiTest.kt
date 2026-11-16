package co.solventa.mobile.feature.policy

import android.app.Application
import co.solventa.mobile.feature.evidence.MobileEvidenceApi
import co.solventa.mobile.feature.evidence.evidenceAuth
import java.time.LocalDate
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

internal val policy = Policy("be794f6e-e01d-4d2f-ad25-c9bf118c117a", "b77d84cb-6ed8-490b-bcdd-09d147a79e33",
    PolicyProduct.AUTO, LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"),
    PolicyStatus.ACTIVE, true, null, null)
internal fun policyJson(value: Policy = policy) = JSONObject()
    .put("id", value.id).put("suscripcionId", value.subscriptionId).put("tipo", value.product.code)
    .put("fechaInicio", value.start.toString()).put("fechaFin", value.end.toString()).put("estado", value.status.code)
    .put("simulada", value.simulated).put("numero", value.number ?: JSONObject.NULL)
    .put("coberturaPrincipal", value.mainCoverage ?: JSONObject.NULL)
internal fun policyList(vararg values: Policy = arrayOf(policy)) =
    JSONObject().put("polizas", JSONArray(values.map(::policyJson))).toString()
internal fun policyApi(server: MockWebServer) =
    MobilePolicyApi(MobileEvidenceApi(evidenceAuth(), server.url("/movil/v1").toString()))

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class MobilePolicyApiTest {
    @Test fun contractUsesAuthenticatedReadOnlyRequestsAndDoesNotInventMissingFields() = runBlocking {
        MockWebServer().use { server ->
            val api = policyApi(server)
            val all = PolicyProduct.entries.mapIndexed { i, product ->
                policy.copy(id = UUID.randomUUID().toString(), product = product, status = PolicyStatus.entries[i % 3])
            }
            server.enqueue(MockResponse().setBody(policyList(*all.toTypedArray())))
            assertEquals(all, api.list("actor-a"))
            val list = server.takeRequest()
            assertEquals("/movil/v1/polizas", list.path)
            for (item in all) {
                server.enqueue(MockResponse().setBody(policyJson(item).toString()))
                assertEquals(item, api.detail("actor-a", item.id))
                val request = server.takeRequest()
                assertEquals("GET", request.method)
                assertEquals("/movil/v1/polizas/${item.id}", request.path)
                assertEquals("no-store", request.getHeader("Cache-Control"))
                assertNotNull(request.getHeader("Authorization"))
                assertNull(request.getHeader("Idempotency-Key"))
                assertEquals(0, request.body.size)
                assertNull(item.number)
                assertNull(item.mainCoverage)
            }
            server.enqueue(MockResponse().setBody(policyList(*emptyArray())))
            assertTrue(api.list("actor-a").isEmpty())
        }
    }

    @Test fun malformedOrMismatchedDataNeverBecomesAnEmptyListOrAUsablePolicy() = runBlocking {
        MockWebServer().use { server ->
            val api = policyApi(server)
            val invalid = listOf(
                policyJson().put("id", UUID.randomUUID().toString()),
                policyJson().put("suscripcionId", "../otro"),
                policyJson().put("estado", "cancelada"),
                policyJson().put("tipo", "otro"),
                policyJson().put("simulada", "true"),
                policyJson().put("simulada", false),
                policyJson().put("fechaInicio", "2026-02-30"),
                policyJson().put("fechaFin", "2025-12-31"),
                policyJson().put("numero", 123),
                policyJson().put("coberturaPrincipal", JSONObject()),
                policyJson().apply { remove("numero") },
                policyJson().put("numero", " "),
            )
            for (data in invalid) {
                server.enqueue(MockResponse().setBody(data.toString()))
                try { api.detail("actor-a", policy.id); fail("Malformed policy accepted") }
                catch (error: Exception) { assertEquals(PolicyError.PROTOCOL, policyError(error)) }
            }
            for (body in listOf("{}", policyList(policy, policy), "<html>")) {
                server.enqueue(MockResponse().setBody(body))
                try { api.list("actor-a"); fail() }
                catch (error: Exception) { assertEquals(PolicyError.PROTOCOL, policyError(error)) }
            }
            val count = server.requestCount
            try { api.detail("actor-a", "../other"); fail() }
            catch (error: Exception) { assertEquals(PolicyError.PROTOCOL, policyError(error)) }
            try { api.list("actor-b"); fail() }
            catch (error: Exception) { assertEquals(PolicyError.SESSION, policyError(error)) }
            assertEquals(count, server.requestCount)
        }
    }

    @Test fun errorsAndBoundedRefreshPreservePrivacy() = runBlocking {
        MockWebServer().use { server ->
            var renewals = 0
            val api = MobilePolicyApi(MobileEvidenceApi(evidenceAuth(onRefresh = { renewals++ }),
                server.url("/movil/v1").toString()))
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setBody(policyList()))
            assertEquals(listOf(policy), api.list("actor-a"))
            assertEquals(1, renewals)
            for ((status, code, expected) in listOf(
                Triple(404, "poliza_no_encontrada", PolicyError.UNAVAILABLE),
                Triple(404, "ruta_no_encontrada", PolicyError.NETWORK),
                Triple(403, "alcance_insuficiente", PolicyError.SESSION),
                Triple(503, "servicio_no_disponible", PolicyError.NETWORK),
                Triple(400, "solicitud_invalida", PolicyError.PROTOCOL),
            )) {
                server.enqueue(MockResponse().setResponseCode(status).setBody("""{"code":"$code"}"""))
                try { api.detail("actor-a", policy.id); fail() }
                catch (error: Exception) { assertEquals(expected, policyError(error)) }
            }
            repeat(2) { server.enqueue(MockResponse().setResponseCode(401)) }
            try { api.list("actor-a"); fail() }
            catch (error: Exception) { assertEquals(PolicyError.SESSION, policyError(error)) }
            assertEquals(2, renewals)
        }
    }
}
