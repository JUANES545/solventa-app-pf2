package co.solventa.mobile.feature.claim

import android.app.Application
import android.os.Looper
import co.solventa.mobile.feature.evidence.MobileEvidenceApi
import co.solventa.mobile.feature.evidence.evidenceAuth
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ClaimViewModelTest {
    private val app = RuntimeEnvironment.getApplication()
    private fun await(model: ClaimViewModel) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (model.state.value.busy && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertFalse("Operation did not finish", model.state.value.busy)
    }

    @Test fun doubleSubmitFailureRetryAndActorChangeRetainOneDurableOperation() {
        MockWebServer().use { server ->
            var posts = 0
            var succeed = false
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method == "GET") return MockResponse().setBody(
                        """{"polizas":[{"id":"${policy.id}","tipo":"automotive","estado":"vigente","fechaInicio":"2026-01-01","fechaFin":"2026-12-31"}]}""")
                    posts++
                    if (!succeed) return MockResponse().setResponseCode(503).setBody("""{"code":"servicio_no_disponible"}""")
                    return MockResponse().setResponseCode(201).setBody(JSONObject(request.body.readUtf8())
                        .put("id", "e2e33a80-edfa-4734-9f22-8c535694f111").put("estado", "registrado")
                        .put("registradoEn", "2026-11-04T18:00:00Z").toString())
                }
            }
            val api = MobileClaimApi(MobileEvidenceApi(evidenceAuth(), server.url("/movil/v1").toString()))
            val model = ClaimViewModel(app, api)
            model.show("actor-a"); await(model)
            model.submit()
            assertEquals(ClaimError.REQUIRED, model.state.value.error)
            assertEquals(0, posts)
            model.edit(draft)
            model.submit(); model.submit()
            assertTrue(model.state.value.busy)
            await(model)
            assertEquals(1, posts)
            val report = model.state.value.reports.single()
            assertEquals(ClaimError.NETWORK, report.error)
            assertNull(report.receipt)
            assertFalse(model.state.value.editing)
            model.close()
            model.bindActor("actor-b")
            assertTrue(model.state.value.reports.isEmpty())
            assertEquals(ClaimDraft(), model.state.value.draft)
            succeed = true
            val reopened = ClaimViewModel(app, api)
            reopened.show("actor-a"); await(reopened)
            assertEquals(2, posts)
            assertEquals(report.operation, reopened.state.value.reports.single().operation)
            assertNotNull(reopened.state.value.reports.single().receipt)
            reopened.retry(); await(reopened)
            assertEquals(2, posts)
            reopened.newReport()
            assertTrue(reopened.state.value.editing)
        }
    }

    @Test fun explicitRejectionCanBeCorrectedButAnUncertainOperationCannotBeEdited() {
        MockWebServer().use { server ->
            val api = MobileClaimApi(MobileEvidenceApi(evidenceAuth(), server.url("/movil/v1").toString()))
            val model = ClaimViewModel(app, api)
            model.submit()
            model.retry()
            server.enqueue(MockResponse().setBody(
                """{"polizas":[{"id":"${policy.id}","tipo":"automotive","estado":"vigente","fechaInicio":"2026-01-01","fechaFin":"2026-12-31"}]}"""))
            model.show("actor-a")
            model.edit(draft)
            model.newReport()
            model.retry()
            await(model)
            model.edit(draft)
            server.enqueue(MockResponse().setResponseCode(404).setBody("""{"code":"poliza_no_encontrada"}"""))
            model.submit(); await(model)
            val rejected = model.state.value.reports.single()
            model.correct(rejected.copy(actor = "actor-b"))
            assertFalse(model.state.value.editing)
            model.correct(rejected)
            assertEquals(rejected.operation, model.state.value.correction)
            model.edit(model.state.value.draft.copy(place = "Lugar corregido"))
            val correctedPayload = model.state.value.draft.payload(listOf(policy))
            server.enqueue(MockResponse().setBody(response(rejected.copy(payload = correctedPayload))))
            model.submit()
            model.correct(rejected)
            await(model)
            val confirmed = model.state.value.reports.single { it.receipt != null }
            assertEquals(confirmed.operation, model.state.value.reports.first().supersededBy)
            assertNotEquals(rejected.operation, confirmed.operation)
            assertNotNull(confirmed.receipt)
            model.correct(confirmed)
            assertFalse(model.state.value.editing)
            model.edit(draft)
            assertEquals(ClaimDraft(), model.state.value.draft)
            androidx.lifecycle.ViewModelStore().apply { put("claims", model); clear() }
        }
    }

    @Test fun typedErrorsRemainRecoverableAndUnknownFailuresAreNotSwallowed() {
        val errors = listOf(
            ClaimProblem(ClaimError.REQUIRED) to ClaimError.REQUIRED,
            co.solventa.mobile.feature.auth.AuthProblem(co.solventa.mobile.feature.auth.AuthError.UNAVAILABLE) to ClaimError.NETWORK,
            co.solventa.mobile.feature.auth.AuthProblem(co.solventa.mobile.feature.auth.AuthError.STORAGE) to ClaimError.STORAGE,
            co.solventa.mobile.feature.auth.AuthProblem(co.solventa.mobile.feature.auth.AuthError.EXPIRED) to ClaimError.SESSION,
            co.solventa.mobile.feature.auth.AuthHttpError(401) to ClaimError.SESSION,
            co.solventa.mobile.feature.auth.AuthHttpError(403) to ClaimError.SESSION,
            co.solventa.mobile.feature.auth.AuthHttpError(503) to ClaimError.NETWORK,
            android.database.sqlite.SQLiteException() to ClaimError.STORAGE,
            SecurityException() to ClaimError.STORAGE,
            IllegalArgumentException() to ClaimError.PROTOCOL,
            org.json.JSONException("invalid") to ClaimError.PROTOCOL,
            java.io.IOException() to ClaimError.NETWORK,
            co.solventa.mobile.feature.evidence.EvidenceProblem(co.solventa.mobile.feature.evidence.EvidenceError.SESSION) to ClaimError.SESSION,
        )
        errors.forEach { (error, expected) -> assertEquals(expected, claimError(error)) }
        for (status in listOf(401, 403, 400, 409, 422, 503)) {
            val error = co.solventa.mobile.feature.evidence.EvidenceHttpProblem(status, "code", false,
                co.solventa.mobile.feature.evidence.EvidenceError.PROTOCOL)
            assertEquals(when (status) { 401, 403 -> ClaimError.SESSION; 503 -> ClaimError.NETWORK; else -> ClaimError.PROTOCOL }, claimError(error))
        }
        try { claimError(kotlinx.coroutines.CancellationException()); fail() }
        catch (_: kotlinx.coroutines.CancellationException) { }
        try { claimError(IllegalStateException()); fail() }
        catch (_: IllegalStateException) { }
    }

    @Test fun storageFailureIsVisibleAndActorChangeCancelsLateResults() {
        val badApp = org.mockito.Mockito.mock(Application::class.java)
        org.mockito.Mockito.`when`(badApp.noBackupFilesDir).thenReturn(java.io.File(app.noBackupFilesDir, "missing/parent"))
        val api = MobileClaimApi(MobileEvidenceApi(evidenceAuth(), "http://127.0.0.1:1"))
        val broken = ClaimViewModel(badApp, api)
        broken.show("actor-a"); await(broken)
        assertEquals(ClaimError.STORAGE, broken.state.value.error)
        androidx.lifecycle.ViewModelStore().apply { put("broken", broken); clear() }
        val model = ClaimViewModel(app, api)
        model.show("actor-a")
        model.bindActor(null)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(model.state.value.actor)
        assertTrue(model.state.value.reports.isEmpty())
        androidx.lifecycle.ViewModelStore().apply { put("unused", model); clear() }
    }

    @Test fun offlinePolicyFailureKeepsCachedSelectionAndDraft() {
        ClaimStore(app.noBackupFilesDir).use { it.savePolicies("actor-a", listOf(policy)) }
        val model = ClaimViewModel(app, MobileClaimApi(MobileEvidenceApi(evidenceAuth(), "http://127.0.0.1:1")))
        model.show("actor-a"); await(model)
        assertTrue(model.state.value.cachedPolicies)
        assertEquals(listOf(policy), model.state.value.policies)
        model.edit(draft)
        model.submit(); await(model)
        assertEquals(draft.type, model.state.value.reports.single().payload.type)
        assertNull(model.state.value.reports.single().receipt)
        model.newReport()
        model.edit(draft.copy(type = "Otro evento"))
        model.retry(); await(model)
        assertEquals("Otro evento", model.state.value.draft.type)
    }
}
