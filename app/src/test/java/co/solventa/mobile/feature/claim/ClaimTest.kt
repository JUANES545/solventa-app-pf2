package co.solventa.mobile.feature.claim

import android.app.Application
import co.solventa.mobile.feature.evidence.evidenceAuth
import co.solventa.mobile.feature.evidence.MobileEvidenceApi
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

internal val policy = ClaimPolicy("f5ed9c42-7522-4c8f-a784-49d5db007111", "automotive",
    LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
internal val draft = ClaimDraft(policy.id, "2026-03-01", "12:30", "Choque", "Lugar sintetico", "Descripcion sintetica.")
internal fun payload() = draft.payload(listOf(policy), Instant.parse("2026-11-04T18:00:00Z"), ZoneOffset.UTC)
internal fun response(report: ClaimReport, id: String = UUID.randomUUID().toString()): String = JSONObject().apply {
    put("id", id); put("estado", "registrado"); put("registradoEn", "2026-11-04T18:00:00Z")
    put("polizaId", report.payload.policyId); put("fechaEvento", report.payload.date.toString())
    put("tipoEvento", report.payload.type); put("lugar", report.payload.place); put("descripcion", report.payload.description)
}.toString()

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ClaimTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun validatesRequiredFieldsDatesLimitsAndPolicyBeforeQueueing() {
        fun invalid(value: ClaimDraft, expected: ClaimError) {
            try { value.payload(listOf(policy), Instant.parse("2026-11-04T18:00:00Z"), ZoneOffset.UTC); fail() }
            catch (error: ClaimProblem) { assertEquals(expected, error.reason) }
        }
        invalid(draft.copy(policyId = ""), ClaimError.REQUIRED)
        invalid(draft.copy(type = " "), ClaimError.REQUIRED)
        invalid(draft.copy(place = ""), ClaimError.REQUIRED)
        invalid(draft.copy(description = ""), ClaimError.REQUIRED)
        invalid(draft.copy(date = ""), ClaimError.REQUIRED)
        invalid(draft.copy(time = ""), ClaimError.REQUIRED)
        invalid(draft.copy(policyId = UUID.randomUUID().toString()), ClaimError.POLICY)
        invalid(draft.copy(date = "2026-02-30"), ClaimError.DATE)
        invalid(draft.copy(time = "25:00"), ClaimError.DATE)
        invalid(draft.copy(date = "2025-12-31"), ClaimError.DATE)
        invalid(draft.copy(date = "2027-01-01"), ClaimError.DATE)
        invalid(draft.copy(date = "2026-11-05"), ClaimError.DATE)
        invalid(draft.copy(description = "123456789"), ClaimError.DESCRIPTION)
        invalid(draft.copy(description = "a".repeat(5001)), ClaimError.DESCRIPTION)
        invalid(draft.copy(type = "a".repeat(101)), ClaimError.LENGTH)
        invalid(draft.copy(place = "a".repeat(301)), ClaimError.LENGTH)
        invalid(draft.copy(description = "\uD83D\uDE00".repeat(5000)), ClaimError.LENGTH)
        assertEquals(5000, draft.copy(description = "a".repeat(5000), type = "a".repeat(100),
            place = "a".repeat(300)).payload(listOf(policy)).description.length)
        assertEquals(Instant.parse("2026-03-01T17:30:00Z"),
            draft.payload(listOf(policy), zone = ZoneOffset.ofHours(-5)).date)
    }

    @Test fun durableQueueAndPolicyCacheArePrivateToActorAndOnlyReceiptConfirms() {
        val directory = folder.newFolder()
        val report = ClaimStore(directory).use { store ->
            store.savePolicies("actor-a", listOf(policy))
            store.savePolicies("actor-b", listOf(policy.copy(type = "another")))
            store.enqueue("actor-a", payload()).also { store.fail(it, ClaimError.NETWORK) }
        }
        ClaimStore(directory).use { store ->
            val saved = store.list("actor-a").single()
            assertEquals(report.operation, saved.operation)
            assertEquals(report.payload, saved.payload)
            assertEquals(ClaimError.NETWORK, saved.error)
            assertNull(saved.receipt)
            assertTrue(store.list("actor-b").isEmpty())
            assertEquals(listOf(policy), store.policies("actor-a"))
            assertEquals("another", store.policies("actor-b").single().type)
            store.savePolicies("actor-a", emptyList())
            assertTrue(store.policies("actor-a").isEmpty())
            val receipt = ClaimReceipt(UUID.randomUUID().toString(), "registrado")
            store.confirm(saved, receipt)
            store.confirm(saved, receipt)
            assertEquals(receipt, store.list("actor-a").single().receipt)
            assertNull(store.list("actor-a").single().error)
            try { store.confirm(saved, receipt.copy(id = UUID.randomUUID().toString())); fail() }
            catch (_: IllegalArgumentException) { }
            try { store.confirm(saved.copy(actor = "actor-b"), receipt); fail() }
            catch (_: NoSuchElementException) { }
        }
    }

    @Test fun lostAcknowledgementReplaysSameKeyAndBodyAfterReopeningStore() = runBlocking {
        MockWebServer().use { server ->
            val directory = folder.newFolder()
            val report = ClaimStore(directory).use { it.enqueue("actor-a", payload()) }
            val api = MobileClaimApi(MobileEvidenceApi(evidenceAuth(), server.url("/movil/v1").toString()))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            try { api.send(report); fail() } catch (error: Exception) { assertEquals(ClaimError.NETWORK, claimError(error)) }
            val first = server.takeRequest()
            val receipt = UUID.randomUUID().toString()
            server.enqueue(MockResponse().setBody(response(report, receipt)))
            ClaimStore(directory).use { store ->
                val saved = store.list(report.actor).single()
                assertNull(saved.receipt)
                store.confirm(saved, api.send(saved))
                assertEquals(receipt, store.list(report.actor).single().receipt?.id)
            }

            val replay = server.takeRequest()
            assertEquals(report.operation, replay.getHeader("Idempotency-Key"))
            assertEquals(first.getHeader("Idempotency-Key"), replay.getHeader("Idempotency-Key"))
            assertEquals(first.body.readUtf8(), replay.body.readUtf8())
        }
    }

    @Test fun correctingRequiresADefinitiveRejectionAndCreatesANewKeyAtomically() {
        ClaimStore(folder.newFolder()).use { store ->
            val report = store.enqueue("actor-a", payload())
            store.fail(report, ClaimError.NETWORK)
            try { store.enqueue("actor-a", payload(), report.operation); fail() }
            catch (_: IllegalStateException) { }
            assertEquals(report.operation, store.list("actor-a").single().operation)
            store.fail(report, ClaimError.POLICY)
            assertTrue(store.list("actor-a").single().correctable)
            val corrected = store.enqueue("actor-a", payload().copy(place = "Lugar corregido"), report.operation)
            assertNotEquals(report.operation, corrected.operation)
            assertEquals(corrected, store.list("actor-a").last())
            val historical = store.list("actor-a").first()
            assertEquals(report.operation, historical.operation)
            assertEquals(payload(), historical.payload)
            assertEquals(ClaimError.POLICY, historical.error)
            assertEquals(corrected.operation, historical.supersededBy)
            assertFalse(historical.correctable)
            assertFalse(historical.retryable)
            assertEquals(payload(), payload().draft().payload(listOf(policy)))
            try { store.confirm(corrected, ClaimReceipt(UUID.randomUUID().toString(), "pendiente")); fail() }
            catch (_: IllegalArgumentException) { }
            try { store.confirm(corrected.copy(payload = payload()), ClaimReceipt(UUID.randomUUID().toString(), "registrado")); fail() }
            catch (_: IllegalArgumentException) { }
            try { store.fail(corrected.copy(actor = "actor-b"), ClaimError.NETWORK); fail() }
            catch (_: IllegalStateException) { }
            try { store.enqueue("", payload()); fail() }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun validatesConfirmationRefreshesSessionAndNeverSendsAsAnotherActor() = runBlocking {
        MockWebServer().use { server ->
            var renewals = 0
            val api = MobileClaimApi(MobileEvidenceApi(evidenceAuth(onRefresh = { renewals++ }), server.url("/movil/v1").toString()))
            val report = ClaimReport(UUID.randomUUID().toString(), "actor-a", payload())
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setResponseCode(201).setBody(response(report)))
            assertEquals("registrado", api.send(report).status)
            assertEquals(1, renewals)
            assertEquals(server.takeRequest().getHeader("Idempotency-Key"), server.takeRequest().getHeader("Idempotency-Key"))
            for (field in listOf("id", "estado", "polizaId", "fechaEvento", "tipoEvento", "lugar", "descripcion", "registradoEn")) {
                server.enqueue(MockResponse().setBody(JSONObject(response(report)).put(field, "invalid").toString()))
                try { api.send(report); fail(field) }
                catch (error: Exception) { assertEquals(field, ClaimError.PROTOCOL, claimError(error)) }
            }
            val count = server.requestCount
            try { api.send(report.copy(actor = "actor-b")); fail() }
            catch (error: Exception) { assertEquals(ClaimError.SESSION, claimError(error)) }
            assertEquals(count, server.requestCount)
            for ((code, status, expected) in listOf(Triple("poliza_no_encontrada", 404, ClaimError.POLICY),
                Triple("fecha_evento_invalida", 422, ClaimError.DATE), Triple("servicio_no_disponible", 503, ClaimError.NETWORK))) {
                server.enqueue(MockResponse().setResponseCode(status).setBody("""{"code":"$code"}"""))
                try { api.send(report); fail() } catch (error: Exception) { assertEquals(expected, claimError(error)) }
            }
        }
    }

    @Test fun parsesOnlyAuthorizedValidPolicyResponses() = runBlocking {
        MockWebServer().use { server ->
            val api = MobileClaimApi(MobileEvidenceApi(evidenceAuth(), server.url("/movil/v1").toString()))
            val json = """{"polizas":[{"id":"${policy.id}","tipo":"automotive","estado":"vigente","fechaInicio":"2026-01-01","fechaFin":"2026-12-31"}]}"""
            server.enqueue(MockResponse().setBody(json))
            assertEquals(listOf(policy), api.policies("actor-a"))
            for (body in listOf("{}", json.replace("vigente", "vencida"), json.replace("2026-12-31", "2025-01-01"))) {
                server.enqueue(MockResponse().setBody(body))
                try { api.policies("actor-a"); fail() } catch (error: Exception) { assertEquals(ClaimError.PROTOCOL, claimError(error)) }
            }
        }
    }
}
