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

internal val policy = Policy("be794f6e-e01d-4d2f-ad25-c9bf118c117a",
    PolicyProduct.AUTO, LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"),
    PolicyStatus.ACTIVE, true, "POL-20260101-BE794F6E", "Daños y hurto")
internal fun policyJson(value: Policy = policy) = JSONObject()
    .put("id", value.id).put("suscripcionId", "b77d84cb-6ed8-490b-bcdd-09d147a79e33")
    .put("producto", JSONObject().put("tipo", value.product.code).put("nombre", "Seguro de Auto"))
    .put("vigencia", JSONObject().put("inicio", value.start.toString()).put("fin", value.end.toString()))
    .put("estado", value.status.code).put("simulada", value.simulated).put("numero", value.number)
    .put("coberturaPrincipal", JSONObject().put("codigo", "danos_hurto").put("nombre", value.mainCoverage))
internal fun policyList(vararg values: Policy = arrayOf(policy)) =
    JSONObject().put("polizas", JSONArray(values.map { policyJson(it).apply { remove("suscripcionId") } })).toString()
internal fun policyApi(server: MockWebServer) =
    MobilePolicyApi(MobileEvidenceApi(evidenceAuth(), server.url("/movil/v1").toString()))

// Migue765/backend-solventa@cdc1f849, serializers de consulta.py (producto travel).
internal fun contractPolicyJson(simulated: Boolean, detail: Boolean) = JSONObject(
    """{
        "id":"00000000-0000-0000-0000-000000000001",
        "numero":"POL-20261119-00000000",
        "producto":{"tipo":"travel","nombre":"Seguro de Viaje"},
        "estado":"vigente",
        "vigencia":{"inicio":"2026-11-19","fin":"2026-11-29"},
        "coberturaPrincipal":{"codigo":"asistencia_medica","nombre":"Asistencia médica"},
        "simulada":true
    }"""
).put("simulada", simulated).apply {
    if (detail) {
        put("suscripcionId", "00000000-0000-0000-0000-000000000002")
        put("emitidaEn", "2026-11-19T12:00:00+00:00")
        put("version", JSONObject.NULL)
        put("plan", JSONObject.NULL)
        put("coberturas", JSONArray("""[{"codigo":"asistencia_medica","nombre":"Asistencia médica",
            "descripcion":"Atención por accidente o enfermedad durante el viaje.","opcional":false,"incluida":true}]"""))
        put("prima", JSONObject.NULL)
        put("condiciones", JSONArray("""["La cobertura aplica durante las fechas y destinos informados."]"""))
        put("consultadaEn", "2026-11-20T12:00:00+00:00")
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class MobilePolicyApiTest {
    @Test fun currentBffSummaryAndFullDetailAcceptBothSimulationFlagsWithoutChangingFields() = runBlocking {
        MockWebServer().use { server ->
            val api = policyApi(server)
            for (simulated in listOf(true, false)) {
                val summary = contractPolicyJson(simulated, detail = false)
                assertFalse(summary.has("suscripcionId"))
                server.enqueue(MockResponse().setBody(JSONObject().put("polizas", JSONArray().put(summary)).toString()))
                val listed = api.list("actor-a").single()
                assertEquals("00000000-0000-0000-0000-000000000001", listed.id)
                assertEquals("POL-20261119-00000000", listed.number)
                assertEquals("Asistencia médica", listed.mainCoverage)
                assertEquals(LocalDate.of(2026, 11, 19), listed.start)
                assertEquals(LocalDate.of(2026, 11, 29), listed.end)
                assertEquals(PolicyProduct.TRAVEL, listed.product)
                assertEquals(PolicyStatus.ACTIVE, listed.status)
                assertEquals(simulated, listed.simulated)
                server.enqueue(MockResponse().setBody(contractPolicyJson(simulated, detail = true).toString()))
                assertEquals(listed, api.detail("actor-a", listed.id))
            }
        }
    }

    @Test fun contractUsesAuthenticatedReadOnlyRequestsForEveryProductAndStatus() = runBlocking {
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
                policyJson().apply { getJSONObject("producto").put("tipo", "otro") },
                policyJson().put("simulada", "true"),
                policyJson().apply { remove("simulada") },
                policyJson().apply { getJSONObject("vigencia").put("inicio", "2026-02-30") },
                policyJson().apply { getJSONObject("vigencia").put("fin", "2025-12-31") },
                policyJson().put("numero", 123),
                policyJson().put("coberturaPrincipal", JSONObject()),
                policyJson().put("coberturaPrincipal", "Daños y hurto"),
                policyJson().put("coberturaPrincipal", JSONObject.NULL),
                policyJson().apply { getJSONObject("coberturaPrincipal").put("codigo", " ") },
                policyJson().apply { getJSONObject("coberturaPrincipal").put("nombre", 123) },
                policyJson().apply { getJSONObject("producto").remove("nombre") },
                policyJson().apply { remove("suscripcionId") },
                policyJson().put("numero", JSONObject.NULL),
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
