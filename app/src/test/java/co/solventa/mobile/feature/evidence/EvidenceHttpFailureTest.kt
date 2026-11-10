package co.solventa.mobile.feature.evidence

import android.app.Application
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class EvidenceHttpFailureTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun unreadableErrorBodiesKeepKnownStatusWithoutInventingBackendCodes() {
        MockWebServer().use { server ->
            val api = MobileEvidenceApi(evidenceAuth(), server.url("/movil/v1").toString())
            for ((status, reason) in listOf(503 to EvidenceError.NETWORK, 403 to EvidenceError.SESSION,
                404 to EvidenceError.NETWORK, 422 to EvidenceError.PROTOCOL)) {
                for (body in listOf("<html>Service unavailable</html>", "", "{}", """{"code":null}""", """{"code":7}""", """{"code":""}""")) {
                    server.enqueue(MockResponse().setResponseCode(status).setBody(body))
                    try { api.request("GET", "/siniestros", "synthetic"); fail("$status must not succeed") }
                    catch (error: EvidenceProblem) {
                        assertTrue("$status must remain an HTTP failure, got ${error.reason}", error is EvidenceHttpProblem)
                        if (error is EvidenceHttpProblem) {
                            assertEquals(status, error.status)
                            assertEquals(reason, error.reason)
                            assertNull(error.code)
                            assertFalse(error.permanent)
                        }
                    }
                }
            }
        }
    }

    @Test fun validDefinitiveRejectionsKeepTheirCodeAndExistingEvidenceMeaning() {
        MockWebServer().use { server ->
            val api = MobileEvidenceApi(evidenceAuth(), server.url("/movil/v1").toString())
            for ((status, code, reason) in listOf(Triple(413, "limite_tamano", EvidenceError.SIZE),
                Triple(422, "formato_invalido", EvidenceError.FORMAT), Triple(409, "limite_cantidad", EvidenceError.COUNT))) {
                server.enqueue(MockResponse().setResponseCode(status).setBody("""{"code":"$code"}"""))
                try { api.request("POST", "/siniestros", "synthetic"); fail() }
                catch (error: EvidenceHttpProblem) {
                    assertEquals(status, error.status)
                    assertEquals(code, error.code)
                    assertEquals(reason, error.reason)
                    assertTrue(error.permanent)
                }

            }

            server.enqueue(MockResponse().setResponseCode(503).setBody("""{"code":"servicio_no_disponible"}"""))
            try { api.request("GET", "/siniestros", "synthetic"); fail() }
            catch (error: EvidenceHttpProblem) {
                assertEquals(503, error.status)
                assertEquals("servicio_no_disponible", error.code)
                assertFalse(error.permanent)
            }
        }
    }

    @Test fun emptyCollectionsAreSuccessfulButMissingResourcesAndFailuresAreNotEmpty() = runBlocking {
        MockWebServer().use { server ->
            val api = MobileEvidenceApi(evidenceAuth(), server.url("/movil/v1").toString())
            val scope = EvidenceScope("actor-a", UUID.randomUUID().toString())
            server.enqueue(MockResponse().setBody("""{"siniestros":[]}"""))
            assertTrue(api.claims(scope.actorId).isEmpty())
            server.enqueue(MockResponse().setBody("""{"evidencias":[]}"""))
            assertTrue(api.confirmedIds(scope).isEmpty())
            for ((status, body, reason) in listOf(
                Triple(404, """{"code":"siniestro_no_encontrado"}""", EvidenceError.UNAVAILABLE),
                Triple(404, """{"code":"ruta_no_encontrada"}""", EvidenceError.NETWORK),
                Triple(403, """{"code":"alcance_insuficiente"}""", EvidenceError.SESSION),
                Triple(503, """{"code":"servicio_no_disponible"}""", EvidenceError.NETWORK),
                Triple(200, "{}", EvidenceError.PROTOCOL),
            )) {
                server.enqueue(MockResponse().setResponseCode(status).setBody(body))
                try { api.confirmedIds(scope); fail("Failure must not become an empty evidence collection") }
                catch (error: EvidenceProblem) { assertEquals(reason, error.reason) }
            }
        }
    }

    @Test fun onlyTheAgreedStatusAndConflictCodeBlockAutomaticReplay() {
        MockWebServer().use { server ->
            val api = MobileEvidenceApi(evidenceAuth(), server.url("/movil/v1").toString())
            for ((status, code) in listOf(409 to "idempotencia_conflictiva", 503 to "idempotencia_conflictiva",
                422 to "idempotencia_conflictiva", 409 to "unknown_conflict", 409 to "offset_invalido")) {
                server.enqueue(MockResponse().setResponseCode(status).setBody("""{"code":"$code"}"""))
                try { api.request("POST", "/siniestros", "synthetic"); fail() }
                catch (error: EvidenceHttpProblem) {
                    val conflict = status == 409 && code == "idempotencia_conflictiva"
                    assertEquals(conflict, error.permanent)
                    assertEquals(conflict, error.reason == EvidenceError.CONFLICT)
                    assertEquals(code, error.code)
                    assertEquals(conflict, co.solventa.mobile.feature.claim.claimError(error) ==
                        co.solventa.mobile.feature.claim.ClaimError.CONFLICT)
                }
            }
        }
    }

    @Test fun invalidSuccessfulResponsesAndGlobalHttpFailuresNeverConfirmOrRejectEvidence() = runBlocking {
        MockWebServer().use { server ->
            EvidenceStore(folder.newFolder()).use { store ->
                val scope = EvidenceScope("actor-a", UUID.randomUUID().toString())
                val policy = EvidencePolicy(4, mapOf(EvidenceKind.PHOTO to 100L), 0)
                val source = folder.newFile().apply { writeBytes(byteArrayOf(1, 2)) }
                repeat(4) { store.enqueue(scope, EvidenceKind.PHOTO, source, Instant.now(), null, 0, policy, emptySet()) }
                val original = store.list(scope)
                val api = MobileEvidenceApi(evidenceAuth(), server.url("/movil/v1").toString())
                val uploader = EvidenceUploader(store, api.transport(store, EvidenceConfiguration(policy, 4)))
                for ((status, body, expected) in listOf(
                    Triple(200, "<html>Not a receipt</html>", EvidenceError.PROTOCOL),
                    Triple(201, "", EvidenceError.PROTOCOL),
                    Triple(503, "<html>Service unavailable</html>", EvidenceError.NETWORK),
                    Triple(403, "", EvidenceError.SESSION),
                )) {
                    val before = server.requestCount
                    server.enqueue(MockResponse().setResponseCode(status).setBody(body))
                    try { uploader.retry(scope, { true }) { _, _, _ -> fail("No upload can start") }; fail() }
                    catch (error: EvidenceProblem) {
                        assertEquals(expected, error.reason)
                        if (status < 400) assertFalse(error is EvidenceHttpProblem)
                    }
                    assertEquals(before + 1, server.requestCount)
                    assertEquals(original.mapIndexed { index, item ->
                        if (index == 0) item.copy(lastError = expected, lastResultAt = store.list(scope).first().lastResultAt) else item
                    }, store.list(scope))
                    assertTrue(store.list(scope).all { it.status == EvidenceStatus.PENDING && it.remoteId == null })
                }
            }
        }
    }
}
