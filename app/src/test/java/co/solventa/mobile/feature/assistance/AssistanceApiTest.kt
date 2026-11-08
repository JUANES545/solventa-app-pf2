package co.solventa.mobile.feature.assistance

import android.app.Application
import co.solventa.mobile.feature.evidence.evidenceAuth
import co.solventa.mobile.feature.evidence.MobileEvidenceApi
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

internal val assistanceLocation = AssistanceLocation(
    4.60971,
    -74.08175,
    AssistanceLocationOrigin.PERMISSION,
    Instant.parse("2026-03-16T12:30:00Z"),
)
internal val assistanceProvider = AssistanceProvider(
    "11111111-1111-4111-8111-111111111111",
    "Gruas Centro",
    "grua",
    2.4,
)

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class AssistanceApiTest {
    private fun api(server: MockWebServer) = MobileAssistanceApi(
        MobileEvidenceApi(evidenceAuth(), server.url("/movil/v1").toString()),
    )

    @Test fun queriesProvidersAndRegistersOnlyAfterBackendConfirmation() = runBlocking {
        MockWebServer().use { server ->
            val api = api(server)
            server.enqueue(
                MockResponse().setBody(
                    """{"prestadores":[{"id":"${assistanceProvider.id}","nombre":"${assistanceProvider.name}","tipoServicio":"${assistanceProvider.serviceType}","distanciaKm":2.4}]}""",
                ),
            )
            assertEquals(
                listOf(assistanceProvider),
                api.providers("actor-a", assistanceLocation, 50),
            )
            val query = server.takeRequest()
            assertEquals("/movil/v1/prestadores/consulta", query.path)
            assertEquals("POST", query.method)
            assertEquals(
                setOf("ubicacion", "radioKm"),
                JSONObject(query.body.readUtf8()).keys().asSequence().toSet(),
            )

            val assistanceId = UUID.randomUUID().toString()
            val operationId = UUID.randomUUID().toString()
            server.enqueue(
                MockResponse().setResponseCode(201).setBody(
                    """{"id":"$assistanceId","estado":"registrada","prestadorId":"${assistanceProvider.id}","tipoAsistencia":"grua","creadaEn":"2026-03-16T12:31:00Z"}""",
                ),
            )
            val receipt = api.request(
                "actor-a",
                UUID.randomUUID().toString(),
                assistanceProvider,
                assistanceLocation,
                operationId,
            )
            assertEquals(assistanceId, receipt.id)
            assertEquals("registrada", receipt.status)
            val registration = server.takeRequest()
            assertEquals(operationId, registration.getHeader("Idempotency-Key"))
            val body = JSONObject(registration.body.readUtf8())
            assertEquals(
                setOf("siniestroId", "prestadorId", "tipoAsistencia", "ubicacion"),
                body.keys().asSequence().toSet(),
            )
            assertFalse(body.toString().contains("medidaEn"))
        }
    }

    @Test fun malformedSensitiveAndUnregisteredResponsesNeverLookSuccessful() = runBlocking {
        MockWebServer().use { server ->
            val api = api(server)
            for (body in listOf(
                """{"prestadores":[{"id":"${assistanceProvider.id}","nombre":"Gruas","tipoServicio":"grua","distanciaKm":2.4,"telefono":"3001234567"}]}""",
                """{"prestadores":[{"id":"invalid","nombre":"Gruas","tipoServicio":"grua","distanciaKm":2.4}]}""",
            )) {
                server.enqueue(MockResponse().setBody(body))
                try {
                    api.providers("actor-a", assistanceLocation, 50)
                    fail("La respuesta inválida fue aceptada")
                } catch (problem: AssistanceProblem) {
                    assertEquals(AssistanceError.PROTOCOL, problem.reason)
                }
            }
            for (body in listOf(
                """{"id":"${UUID.randomUUID()}","estado":"pendiente","prestadorId":"${assistanceProvider.id}","tipoAsistencia":"grua","creadaEn":"2026-03-16T12:31:00Z"}""",
                """{"id":"${UUID.randomUUID()}","estado":"registrada","prestadorId":"${assistanceProvider.id}","tipoAsistencia":"grua","creadaEn":"sin-fecha"}""",
            )) {
                server.enqueue(MockResponse().setBody(body))
                try {
                    api.request(
                        "actor-a",
                        UUID.randomUUID().toString(),
                        assistanceProvider,
                        assistanceLocation,
                        UUID.randomUUID().toString(),
                    )
                    fail("La solicitud no registrada fue aceptada")
                } catch (problem: AssistanceProblem) {
                    assertEquals(AssistanceError.PROTOCOL, problem.reason)
                }
            }
        }
    }

    @Test fun backendFailuresMapToExplicitStates() = runBlocking {
        MockWebServer().use { server ->
            val api = api(server)
            for ((code, responseCode, expected) in listOf(
                Triple("prestador_no_disponible", 409, AssistanceError.PROVIDER_UNAVAILABLE),
                Triple("recurso_no_autorizado", 403, AssistanceError.RESOURCE_UNAUTHORIZED),
                Triple("idempotencia_conflictiva", 409, AssistanceError.CONFLICT),
            )) {
                server.enqueue(
                    MockResponse().setResponseCode(responseCode)
                        .setBody("""{"code":"$code"}"""),
                )
                try {
                    api.request(
                        "actor-a",
                        UUID.randomUUID().toString(),
                        assistanceProvider,
                        assistanceLocation,
                        UUID.randomUUID().toString(),
                    )
                    fail()
                } catch (problem: AssistanceProblem) {
                    assertEquals(expected, problem.reason)
                }
            }
        }
    }
}
