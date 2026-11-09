package co.solventa.mobile.feature.claim

import android.app.Application
import android.os.Looper
import co.solventa.mobile.feature.evidence.MobileEvidenceApi
import co.solventa.mobile.feature.evidence.evidenceAuth
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ClaimRetryRegressionTest {
    private val app = RuntimeEnvironment.getApplication()

    private fun await(model: ClaimViewModel) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (model.state.value.busy && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertFalse("Retry must release the form", model.state.value.busy)
    }

    @Test fun ambiguousStatusAndCodeNeverAllowCorrectionOrChangeOperation() {
        MockWebServer().use { server ->
            val original = ClaimStore(app.noBackupFilesDir).use { it.enqueue("actor-a", payload()) }
            val model = ClaimViewModel(app, MobileClaimApi(MobileEvidenceApi(evidenceAuth(), server.url("/movil/v1").toString())))
            val cases = listOf(503 to "poliza_no_encontrada", 503 to "fecha_evento_invalida",
                404 to "fecha_evento_invalida", 422 to "poliza_no_encontrada",
                403 to "poliza_no_encontrada", 400 to "fecha_evento_invalida", 404 to "otro_codigo")
            for ((status, code) in cases) {
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        if (request.method == "POST") MockResponse().setResponseCode(status).setBody("""{"code":"$code"}""")
                        else MockResponse().setBody("""{"polizas":[]}""")
                }
                model.show("actor-a"); await(model)
                val report = model.state.value.reports.single()
                assertFalse("$status/$code must not authorize a new key", report.correctable)
                assertEquals(original.operation, report.operation)
                assertEquals(original.payload, report.payload)
                assertNull(report.receipt)
                model.correct(report)
                assertNull(model.state.value.correction)
                ClaimStore(app.noBackupFilesDir).use { store ->
                    try { store.enqueue("actor-a", payload().copy(place = "Changed"), report.operation); fail() }
                    catch (_: IllegalStateException) { }
                    assertEquals(original.operation, store.list("actor-a").single().operation)
                }
            }
        }
    }

    @Test fun networkFailureStopsAfterOneReportAndDoesNotFetchPolicies() = globalFailure(503, ClaimError.NETWORK)

    @Test fun sessionFailureStopsAfterOneReportAndDoesNotFetchPolicies() = globalFailure(403, ClaimError.SESSION)

    @Test fun htmlServiceFailureStopsAfterOneReportAndDoesNotFetchPolicies() =
        globalFailure(503, ClaimError.NETWORK, "<html>Service unavailable</html>")

    @Test fun serviceFailureWithoutCodeStopsAfterOneReportAndDoesNotFetchPolicies() =
        globalFailure(503, ClaimError.NETWORK, "{}")

    @Test fun emptyForbiddenResponseStopsAfterOneReportAndRetainsSessionFailure() =
        globalFailure(403, ClaimError.SESSION, "")

    private fun globalFailure(status: Int, expected: ClaimError, body: String = """{"code":"servicio_no_disponible"}""") {
        MockWebServer().use { server ->
            val originals = ClaimStore(app.noBackupFilesDir).use { store ->
                store.savePolicies("actor-a", listOf(policy))
                repeat(4) { store.enqueue("actor-a", payload().copy(type = "Event $it")) }
                store.list("actor-a")
            }
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) =
                    MockResponse().setResponseCode(status).setBody(body)
            }
            val model = ClaimViewModel(app, MobileClaimApi(MobileEvidenceApi(evidenceAuth(), server.url("/movil/v1").toString())))
            model.show("actor-a"); await(model)
            assertEquals(1, server.requestCount)
            assertEquals("POST", server.takeRequest().method)
            assertEquals(expected, model.state.value.error)
            val saved = model.state.value.reports
            assertEquals(originals.map { it.operation to it.payload }, saved.map { it.operation to it.payload })
            assertTrue(saved.all { it.receipt == null })
            assertEquals(expected, saved.first().error)
            assertEquals(originals.drop(1), saved.drop(1))
            model.newReport()
            model.edit(draft.copy(type = "Another report"))
            assertEquals("Another report", model.state.value.draft.type)
            model.submit(); await(model)
            assertEquals(2, server.requestCount)
            assertEquals(5, model.state.value.reports.size)
            assertTrue(model.state.value.reports.all { it.receipt == null })
        }
    }

    @Test fun individualRejectionDoesNotBlockOtherValidReports() {
        MockWebServer().use { server ->
            val reports = ClaimStore(app.noBackupFilesDir).use { store ->
                repeat(4) { store.enqueue("actor-a", payload().copy(type = "Event $it")) }
                store.list("actor-a")
            }
            val rejected = reports.first()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method == "GET") return MockResponse().setBody("""{"polizas":[]}""")
                    val report = reports.single { it.operation == request.getHeader("Idempotency-Key") }
                    return if (report.operation == rejected.operation) {
                        MockResponse().setResponseCode(404).setBody("""{"code":"poliza_no_encontrada"}""")
                    } else MockResponse().setResponseCode(201).setBody(response(report, UUID.randomUUID().toString()))
                }
            }
            val model = ClaimViewModel(app, MobileClaimApi(MobileEvidenceApi(evidenceAuth(), server.url("/movil/v1").toString())))
            model.show("actor-a"); await(model)
            assertEquals(5, server.requestCount)
            assertTrue(model.state.value.reports.first().correctable)
            assertNull(model.state.value.reports.first().receipt)
            assertTrue(model.state.value.reports.drop(1).all { it.receipt != null })
            assertEquals(ClaimError.POLICY, model.state.value.error)
        }
    }

    @Test fun collisionPersistsItsReasonAndDateWithoutResendingOrBlockingLaterReports() {
        MockWebServer().use { server ->
            val reports = ClaimStore(app.noBackupFilesDir).use { store ->
                List(3) { store.enqueue("actor-a", payload().copy(type = "Event $it")) }
            }
            val posted = mutableListOf<String>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method == "GET") return MockResponse().setBody("""{"polizas":[]}""")
                    val report = reports.single { it.operation == request.getHeader("Idempotency-Key") }
                    posted += report.operation
                    return if (report == reports.first()) {
                        MockResponse().setResponseCode(409).setBody("""{"code":"idempotencia_conflictiva"}""")
                    } else MockResponse().setBody(response(report))
                }
            }
            val api = MobileClaimApi(MobileEvidenceApi(evidenceAuth(), server.url("/movil/v1").toString()))
            val model = ClaimViewModel(app, api)
            model.show("actor-a"); await(model)
            assertEquals(reports.map { it.operation }, posted)
            val conflict = model.state.value.reports.first()
            assertEquals(ClaimError.CONFLICT, conflict.error)
            assertEquals("idempotencia_conflictiva", conflict.errorCode)
            assertNotNull(conflict.lastResultAt)
            assertFalse(conflict.retryable)
            model.correct(conflict)
            assertNull(model.state.value.correction)
            val recreated = ClaimViewModel(app, api)
            recreated.show("actor-a"); await(recreated)
            assertEquals(conflict, recreated.state.value.reports.first())
            assertTrue(recreated.state.value.reports.drop(1).all { it.receipt?.registeredAt != null })
            assertEquals(3, posted.size)
            androidx.lifecycle.ViewModelStore().apply { put("first", model); put("second", recreated); clear() }
        }
    }

    @Test fun submittingANewReportDoesNotJumpAnOlderPendingOperation() {
        MockWebServer().use { server ->
            val first = ClaimStore(app.noBackupFilesDir).use { store ->
                store.savePolicies("actor-a", listOf(policy))
                store.enqueue("actor-a", payload())
            }
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) =
                    MockResponse().setResponseCode(503).setBody("""{"code":"servicio_no_disponible"}""")
            }
            val model = ClaimViewModel(app, MobileClaimApi(MobileEvidenceApi(evidenceAuth(), server.url("/movil/v1").toString())))
            model.show("actor-a"); await(model)
            model.newReport(); model.edit(draft.copy(type = "Newest")); model.submit(); await(model)
            assertEquals(2, server.requestCount)
            repeat(2) { assertEquals(first.operation, server.takeRequest().getHeader("Idempotency-Key")) }
            val newest = model.state.value.reports.last()
            assertEquals("Newest", newest.payload.type)
            assertNull(newest.error)
            assertNull(newest.lastResultAt)
            androidx.lifecycle.ViewModelStore().apply { put("claims", model); clear() }
        }
    }
}
