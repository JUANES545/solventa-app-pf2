package co.solventa.mobile.mock

import android.app.Application
import co.solventa.mobile.feature.auth.*
import co.solventa.mobile.feature.assistance.*
import co.solventa.mobile.feature.claim.*
import co.solventa.mobile.feature.evidence.*
import co.solventa.mobile.feature.notification.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class MockBackendTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var fixture: MockBackend
    private lateinit var server: MockWebServer
    private lateinit var login: MobileAuthApi
    private lateinit var http: MobileEvidenceApi
    private lateinit var notifications: MobileNotificationApi
    private var refreshes = 0
    private lateinit var stateFile: File

    @Before fun start() {
        stateFile = File(folder.root, "server.json")
        fixture = MockBackend(stateFile).apply { delayMillis = 0 }
        server = MockWebServer().apply { dispatcher = fixture; start() }
        login = MobileAuthApi(server.url("/movil/v1").toString())
        var stored: AuthGrant? = login.login(MockBackend.EMAIL, MockBackend.PASSWORD)
        val auth = AuthRepository(object : AuthApi {
            override fun register(name: String, email: String, password: String) = login.register(name, email, password)
            override fun login(email: String, password: String) = login.login(email, password)
            override fun current(token: String) = login.current(token)
            override fun refresh(token: String): AuthGrant { refreshes++; return login.refresh(token) }
            override fun logout(accessToken: String, refreshToken: String) = login.logout(accessToken, refreshToken)
        }, object : AuthStore {
            override fun read() = stored
            override fun write(grant: AuthGrant) { stored = grant }
            override fun clear() { stored = null }
        })
        http = MobileEvidenceApi(auth, server.url("/movil/v1").toString())
        notifications = MobileNotificationApi(auth, server.url("/movil/v1").toString())
    }

    @After fun stop() { server.shutdown() }

    @Test fun photoReportCleanupIsSelectivePersistentAndDoesNotBreakIdempotency() = runBlocking {
        val api = MobileClaimApi(http)
        fun report(description: String = "Fotografía de práctica para comprobar carga y restauración.") =
            ClaimReport(UUID.randomUUID().toString(), MockBackend.ACTOR,
                ClaimPayload(MockBackend.POLICY, Instant.now().minusSeconds(120),
                    "Prueba de fotografía", "Lugar de prueba", description))
        val empty = report()
        val emptyId = api.send(empty).id
        val video = report().let { it.copy(payload = it.payload.copy(type = "Prueba de vídeo",
            description = "Clip de tráfico de dominio público.")) }
        val videoId = api.send(video).id
        val form = report().let { it.copy(payload = it.payload.copy(type = "Choque de prueba",
            description = "El vehículo recibió un golpe en el costado derecho. La puerta y el guardabarros presentan daños visibles. Adjunto fotografías para su revisión.")) }
        val formId = api.send(form).id
        val protectedId = api.send(report()).id
        val queued = report()
        val queuedId = api.send(queued).id
        val withNotice = api.send(report()).id
        fixture.queueNotice(UUID.randomUUID().toString(), withNotice)
        val withUpload = api.send(report()).id
        http.request("POST", "/siniestros/$withUpload/cargas", MockBackend.ACCESS,
            body = JSONObject().put("mimeType", "image/jpeg").put("tamanoBytes", 3)
                .put("sha256", "a".repeat(64)).put("fechaCaptura", Instant.now().toString()),
            idempotencyKey = UUID.randomUUID().toString())
        val userReport = api.send(report("Fotografía del usuario")).id
        val before = api.claims(MockBackend.ACTOR)
        val candidates = before.map { it.id }.toSet() - protectedId
        val removed = setOf(emptyId, videoId, formId)
        assertEquals(removed, fixture.removeEmptyTestClaims(candidates, setOf(queued.operation)))
        assertEquals(before.filterNot { it.id in removed }, api.claims(MockBackend.ACTOR))
        assertTrue(api.claims(MockBackend.ACTOR).map { it.id }.containsAll(listOf(queuedId, withNotice, withUpload, userReport)))
        assertEquals(emptyId, api.send(empty).id)
        assertFalse(api.claims(MockBackend.ACTOR).any { it.id == emptyId })
        assertTrue(fixture.removeEmptyTestClaims(candidates, setOf(queued.operation)).isEmpty())
        fixture = MockBackend(stateFile).apply { delayMillis = 0 }
        server.dispatcher = fixture
        assertEquals(before.filterNot { it.id in removed }, api.claims(MockBackend.ACTOR))
    }

    @Test fun loginRegistrationClaimsAssistanceAndPreferencesUseTheRealParsers() = runBlocking {
        assertEquals(MockBackend.ACTOR, login.register("Cliente ficticio", MockBackend.EMAIL, MockBackend.PASSWORD).id)
        assertEquals(MockBackend.EMAIL, login.current(MockBackend.ACCESS).email)
        val api = MobileClaimApi(http)
        assertEquals(MockBackend.POLICY, api.policies(MockBackend.ACTOR).single().id)
        assertEquals(ClaimProgress.REPORTED, api.status(MockBackend.ACTOR, MockBackend.CLAIM).progress)
        assertTrue(api.claims(MockBackend.ACTOR).any { it.id == MockBackend.CLAIM })
        assertEquals(4, api.claims(MockBackend.ACTOR).size)
        val report = ClaimReport(UUID.randomUUID().toString(), MockBackend.ACTOR,
            ClaimPayload(MockBackend.POLICY, Instant.now().minusSeconds(120), "Choque", "Lugar ficticio", "Descripción ficticia del evento"))
        val first = api.send(report)
        assertEquals(first.id, api.send(report).id)
        assertEquals(5, api.claims(MockBackend.ACTOR).size)
        val location = AssistanceLocation(4.6, -74.0, AssistanceLocationOrigin.MANUAL, Instant.now())
        val assistance = MobileAssistanceApi(http)
        val provider = assistance.providers(MockBackend.ACTOR, location, 10).single()
        val operation = UUID.randomUUID().toString()
        val receipt = assistance.request(MockBackend.ACTOR, first.id, provider, location, operation)
        assertEquals("registrada", receipt.status)
        assertEquals(receipt.id, assistance.request(MockBackend.ACTOR, first.id, provider, location, operation).id)
        assertEquals(4, notifications.notifications(MockBackend.ACTOR).size)
        assertEquals(12, notifications.preferences(MockBackend.ACTOR).size)
        assertTrue(notifications.updatePush(MockBackend.ACTOR, false).filter { it.channel == NotificationChannel.PUSH }.none { it.enabled })
        login.logout(MockBackend.ACCESS, MockBackend.REFRESH)
    }

    @Test fun emptyErrorsAndRefreshDoNotBecomeSuccessfulData() = runBlocking {
        fixture.choose(MockScenario.EMPTY)
        assertTrue(http.claims(MockBackend.ACTOR).isEmpty())
        assertTrue(MobileClaimApi(http).policies(MockBackend.ACTOR).isEmpty())
        assertTrue(notifications.notifications(MockBackend.ACTOR).isEmpty())
        fixture.choose(MockScenario.REFRESH)
        assertEquals(4, http.claims(MockBackend.ACTOR).size)
        assertEquals(1, refreshes)
        for ((scenario, reason) in listOf(
            MockScenario.SERVICE to EvidenceError.NETWORK,
            MockScenario.FORBIDDEN to EvidenceError.SESSION,
            MockScenario.MALFORMED to EvidenceError.PROTOCOL,
            MockScenario.DISCONNECTED to EvidenceError.NETWORK,
        )) {
            fixture.choose(scenario)
            try { http.claims(MockBackend.ACTOR); fail("Expected $scenario") }
            catch (error: EvidenceProblem) { assertEquals(reason, error.reason) }
        }
        fixture.choose(MockScenario.MISSING)
        try { http.confirmedIds(EvidenceScope(MockBackend.ACTOR, MockBackend.CLAIM)); fail("Expected unavailable") }
        catch (error: EvidenceProblem) { assertEquals(EvidenceError.UNAVAILABLE, error.reason) }
        fixture.choose(MockScenario.EXPIRED)
        try { http.claims(MockBackend.ACTOR); fail("Expected session error") }
        catch (error: AuthHttpError) { assertEquals(401, error.status) }
        fixture.choose(MockScenario.DATA)
        assertEquals(4, http.claims(MockBackend.ACTOR).size)
    }

    @Test fun evidenceUploadIsResumableIdempotentAndSurvivesServerRecreation() = runBlocking {
        val scope = EvidenceScope(MockBackend.ACTOR, MockBackend.CLAIM)
        val configuration = http.configuration(MockBackend.ACTOR)
        assertTrue(http.confirmedIds(scope).isEmpty())
        val source = folder.newFile("synthetic.jpg").apply { writeBytes(ByteArray(140_000) { (it % 127).toByte() }) }
        val store = EvidenceStore(folder.newFolder("queue"))
        try {
            val item = store.enqueue(scope, EvidenceKind.PHOTO, source, Instant.now(), null, 0,
                configuration.policy, http.confirmedIds(scope))
            val load = http.request("POST", "/siniestros/${scope.claimId}/cargas", MockBackend.ACCESS,
                body = JSONObject().put("mimeType", item.kind.mime).put("tamanoBytes", item.bytes)
                    .put("sha256", item.sha256).put("fechaCaptura", item.capturedAt.toString()), idempotencyKey = item.id)
            store.saveUploadId(item, load.getString("id"))
            http.request("PUT", "/siniestros/${scope.claimId}/cargas/${load.getString("id")}/contenido",
                MockBackend.ACCESS, bytes = source.readBytes().copyOf(1024), offset = 0)
            fixture = MockBackend(stateFile).apply { delayMillis = 0 }
            server.dispatcher = fixture
            val transport = http.transport(store, configuration)
            val progress = mutableListOf<Long>()
            val receipt = transport.upload(item, source) { progress.add(it) }
            assertEquals(1024L, progress.first())
            assertEquals(receipt.remoteId, transport.upload(item, source) { }.remoteId)
            assertTrue("Confirmed evidence must be persisted", stateFile.readText().contains(receipt.remoteId))
            fixture = MockBackend(stateFile).apply { delayMillis = 0 }
            server.dispatcher = fixture
            assertTrue("Restored server must list confirmed evidence", http.confirmedIds(scope).contains(receipt.remoteId))
            assertEquals(receipt.remoteId, http.transport(store, configuration).confirmed(scope, item.id)?.remoteId)
            val beforeDelete = JSONObject(stateFile.readText()).getJSONObject("uploads")
            assertTrue(beforeDelete.keys().asSequence().any { beforeDelete.getJSONObject(it).getString("bytes").isNotEmpty() })
            try {
                http.request("DELETE", "/siniestros/${scope.claimId}/evidencias/${receipt.remoteId}", "invalid")
                fail("Una sesión inválida no puede borrar evidencias")
            } catch (error: AuthHttpError) { assertEquals(401, error.status) }
            assertTrue(http.confirmedIds(scope).contains(receipt.remoteId))
            val wrongClaim = "11111111-1111-4111-8111-111111111112"
            try {
                http.request("DELETE", "/siniestros/$wrongClaim/evidencias/${receipt.remoteId}", MockBackend.ACCESS)
                fail("Una evidencia no pertenece a otro siniestro")
            } catch (error: EvidenceProblem) { assertEquals(EvidenceError.UNAVAILABLE, error.reason) }
            fixture.choose(MockScenario.ACK_LOST)
            try {
                http.request("DELETE", "/siniestros/${scope.claimId}/evidencias/${receipt.remoteId}", MockBackend.ACCESS)
                fail("La pérdida de confirmación no debe simular éxito")
            } catch (error: EvidenceProblem) { assertEquals(EvidenceError.NETWORK, error.reason) }
            fixture.choose(MockScenario.DATA)
            http.request("DELETE", "/siniestros/${scope.claimId}/evidencias/${receipt.remoteId}", MockBackend.ACCESS)
            fixture = MockBackend(stateFile).apply { delayMillis = 0 }
            server.dispatcher = fixture
            assertTrue(http.confirmedIds(scope).isEmpty())
            val afterDelete = JSONObject(stateFile.readText()).getJSONObject("uploads")
            assertFalse(afterDelete.keys().asSequence().any {
                afterDelete.getJSONObject(it).optString("evidenciaId") == receipt.remoteId
            })
            val pending = store.enqueue(scope, EvidenceKind.PHOTO, source, Instant.now(), null, 0, configuration.policy, emptySet())
            assertNull(http.transport(store, configuration).cancel(pending))
        } finally { store.close() }
    }

    @Test fun biometricChallengesVerifySignaturesSingleUseAndRevocation() {
        val key = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val challenge = decodeBiometricChallenge(login.beginEnrollment(MockBackend.ACCESS, MockBackend.PASSWORD, key.public.encoded),
            "enrolar", MockBackend.ACTOR, key.public.encoded, "urn:solventa:mock")
        fun sign(value: BiometricChallenge) = Signature.getInstance("SHA256withECDSA").run {
            initSign(key.private); update(value.message); sign()
        }
        val proof = sign(challenge)
        login.confirmEnrollment(MockBackend.ACCESS, challenge, proof)
        try { login.confirmEnrollment(MockBackend.ACCESS, challenge, proof); fail("Replay accepted") }
        catch (error: AuthHttpError) { assertEquals(401, error.status) }
        val next = decodeBiometricChallenge(login.biometricChallenge(challenge.credentialId),
            "login", MockBackend.ACTOR, key.public.encoded, "urn:solventa:mock", challenge.credentialId)
        assertEquals(MockBackend.ACTOR, login.biometricLogin(next, sign(next)).user.id)
        assertTrue(login.biometricCredentials(MockBackend.ACCESS).single().active)
        login.revokeBiometricCredential(MockBackend.ACCESS, challenge.credentialId)
        try { login.biometricChallenge(challenge.credentialId); fail("Revoked credential accepted") }
        catch (error: AuthHttpError) { assertEquals(401, error.status) }
    }

    @Test fun unknownRoutesWrongCredentialsAndChangedIdempotentPayloadFail() = runBlocking {
        try { login.login("real@example.test", "not-a-real-password"); fail("Unknown account accepted") }
        catch (error: AuthHttpError) { assertEquals(401, error.status) }
        val connection = URL(server.url("/not-a-fixture").toString()).openConnection() as HttpURLConnection
        try { assertEquals(404, connection.responseCode) } finally { connection.disconnect() }
        val api = MobileClaimApi(http)
        val report = ClaimReport(UUID.randomUUID().toString(), MockBackend.ACTOR,
            ClaimPayload(MockBackend.POLICY, Instant.now().minusSeconds(60), "Choque", "Lugar ficticio", "Descripción ficticia del evento"))
        api.send(report)
        try { api.send(report.copy(payload = report.payload.copy(place = "Otro lugar"))); fail("Conflict accepted") }
        catch (error: EvidenceHttpProblem) { assertEquals("idempotencia_conflictiva", error.code) }
    }

    @Test fun lostAcknowledgementCanBeRetriedWithoutCreatingAnotherClaim() = runBlocking {
        val api = MobileClaimApi(http)
        val report = ClaimReport(UUID.randomUUID().toString(), MockBackend.ACTOR,
            ClaimPayload(MockBackend.POLICY, Instant.now().minusSeconds(60), "Choque", "Lugar ficticio", "Reporte ficticio con respuesta perdida"))
        fixture.choose(MockScenario.ACK_LOST)
        try { api.send(report); fail("Lost response must remain a failure") }
        catch (error: EvidenceProblem) { assertEquals(EvidenceError.NETWORK, error.reason) }
        fixture.choose(MockScenario.DATA)
        val receipt = api.send(report)
        assertEquals(5, api.claims(MockBackend.ACTOR).size)
        assertEquals(receipt.id, api.send(report).id)
    }

    @Test fun queuedNoticeIsPersistentDeduplicatedAndRespectsPreferences() = runBlocking {
        val event = UUID.randomUUID().toString()
        val original = notifications.notifications(MockBackend.ACTOR).map { it.id }
        fixture.queueNotice(event, MockBackend.CLAIM)
        fixture.queueNotice(event, MockBackend.CLAIM)
        fixture = MockBackend(stateFile).apply { delayMillis = 0 }
        server.dispatcher = fixture
        val restored = notifications.notifications(MockBackend.ACTOR)
        assertEquals(original.size + 1, restored.size)
        assertTrue(restored.map { it.id }.containsAll(original))
        assertEquals(1, restored.count { it.id == event })
        notifications.updatePush(MockBackend.ACTOR, false)
        try {
            fixture.queueNotice(UUID.randomUUID().toString(), MockBackend.CLAIM)
            fail("No debe agregar avisos con push desactivado")
        } catch (_: IllegalStateException) { }
        assertEquals(restored, notifications.notifications(MockBackend.ACTOR))
    }

    @Test fun fixtureMigrationPreservesExistingClaimsUploadsAndNotices() = runBlocking {
        val value = JSONObject(stateFile.readText())
        val claims = value.getJSONArray("claims")
        claims.put(JSONObject(claims.getJSONObject(0).toString())
            .put("id", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa").put("descripcion", "Reporte previo"))
        value.put("expandedFixtures", false).put("expandedNotices", false)
        stateFile.writeText(value.toString())
        fixture = MockBackend(stateFile).apply { delayMillis = 0 }
        server.dispatcher = fixture
        assertEquals(5, http.claims(MockBackend.ACTOR).size)
        fixture = MockBackend(stateFile).apply { delayMillis = 0 }
        server.dispatcher = fixture
        assertEquals(5, http.claims(MockBackend.ACTOR).size)
        assertTrue(stateFile.readText().contains("Reporte previo"))
    }
}
