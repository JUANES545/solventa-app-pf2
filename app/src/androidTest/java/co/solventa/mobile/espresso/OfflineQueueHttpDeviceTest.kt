package co.solventa.mobile.espresso

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import co.solventa.mobile.BuildConfig
import co.solventa.mobile.core.SyncResult
import co.solventa.mobile.feature.auth.AuthRepository
import co.solventa.mobile.feature.auth.EncryptedAuthStore
import co.solventa.mobile.feature.auth.MobileAuthApi
import co.solventa.mobile.feature.claim.ClaimError
import co.solventa.mobile.feature.claim.ClaimPayload
import co.solventa.mobile.feature.claim.ClaimStore
import co.solventa.mobile.feature.claim.MobileClaimApi
import co.solventa.mobile.feature.claim.claimError
import co.solventa.mobile.feature.evidence.*
import java.io.File
import java.security.KeyStore
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@ClaimDeviceE2e
@RunWith(AndroidJUnit4::class)
class OfflineQueueHttpDeviceTest {
    @Test fun durableResultsAndUuidRecoveryUseTheRealHttpStack() = runBlocking {
        check(BuildConfig.APPLICATION_ID == "co.solventa.mobile.pf2.claimtest")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configName = InstrumentationRegistry.getArguments().getString("fixtureFile") ?: "sol193-http-fixture.json"
        check(Regex("sol193-http-fixture(?:-[0-9a-f-]{36})?\\.json").matches(configName)) {
            "Invalid private fixture filename"
        }
        val configFile = File(context.noBackupFilesDir, configName)
        check(configFile.isFile && configFile.length() in 1..16_384) {
            "SOL193 requires its private synthetic HTTP fixture; this test cannot run without it"
        }
        val config = try { JSONObject(configFile.readText()) }
        catch (_: Exception) { throw AssertionError("The private SOL193 fixture is not valid JSON") }
        check(config.getInt("schemaVersion") == 1 && config.getBoolean("synthetic"))
        check(config.getBoolean("dropConfirmationAcks")) { "SOL193 requires real confirmation ACK interruption" }
        val base = config.getString("baseUrl")
        check(base == "http://127.0.0.1:18731/movil/v1") { "SOL193 requires the dedicated loopback reverse" }
        val namespace = "sol193-http-${UUID.randomUUID()}"
        val directory = File(context.noBackupFilesDir, namespace).apply { check(mkdir()) }
        val credentials = EncryptedAuthStore(context, namespace, namespace)
        try {
            val auth = AuthRepository(MobileAuthApi(base), credentials)
            val actor = auth.login(config.getString("email"), config.getString("password")).id
            val evidenceApi = MobileEvidenceApi(auth, base)
            val claimApi = MobileClaimApi(evidenceApi)
            val policyId = config.getString("policyId")
            assertTrue(claimApi.policies(actor).any { it.id == policyId })
            val previousClaims = claimApi.claims(actor).map { it.id }.toSet()
            val validPayload = ClaimPayload(policyId, Instant.parse(config.getString("eventDate")),
                "Prueba HTTP SOL193", "Ubicación sintética", "Reporte sintético de validación de la cola.")
            val rejected = ClaimStore(directory).use { store ->
                store.enqueue(actor, validPayload.copy(date = Instant.parse(config.getString("futureEventDate"))))
            }
            val firstRejection = httpFailure(422, "fecha_evento_invalida") { claimApi.send(rejected) }
            val rejectedResult = checkNotNull(firstRejection.serverResult)
            assertEquals(rejected.operation, rejectedResult.operationId)
            ClaimStore(directory).use { store ->
                store.fail(rejected, claimError(firstRejection), firstRejection.code, rejectedResult)
            }
            assertEquals(rejectedResult,
                httpFailure(422, "fecha_evento_invalida") { claimApi.send(rejected) }.serverResult)
            val collision = httpFailure(409, "idempotencia_conflictiva") {
                claimApi.send(rejected.copy(payload = validPayload))
            }
            assertEquals(ClaimError.CONFLICT, claimError(collision))
            assertEquals(rejected.operation, collision.serverResult?.operationId)
            assertEquals(rejectedResult,
                httpFailure(422, "fecha_evento_invalida") { claimApi.send(rejected) }.serverResult)
            assertEquals(previousClaims, claimApi.claims(actor).map { it.id }.toSet())

            val corrected = ClaimStore(directory).use { store ->
                assertEquals(rejectedResult, store.list(actor).single().serverResult)
                store.enqueue(actor, validPayload, rejected.operation)
            }
            assertNotEquals(rejected.operation, corrected.operation)
            val receipt = claimApi.send(corrected)
            val acceptedResult = checkNotNull(receipt.serverResult)
            assertEquals(corrected.operation, acceptedResult.operationId)
            assertEquals(201, acceptedResult.httpStatus)
            assertEquals(receipt, claimApi.send(corrected))
            ClaimStore(directory).use { store ->
                store.confirm(corrected, receipt)
                store.confirm(corrected, claimApi.send(corrected))
                assertEquals(2, store.list(actor).size)
                assertEquals(corrected.operation, store.list(actor).first().supersededBy)
                assertEquals(rejectedResult, store.list(actor).first().serverResult)
                assertEquals(acceptedResult, store.list(actor).last().serverResult)
            }
            val claims = claimApi.claims(actor)
            assertEquals(1, claims.count { it.id == receipt.id })
            assertEquals(previousClaims + receipt.id, claims.map { it.id }.toSet())

            val configuration = evidenceApi.configuration(actor)
            val scope = EvidenceScope(actor, receipt.id)
            val previousEvidence = evidenceApi.confirmedIds(scope)
            val jpeg = File(directory, "synthetic.jpg")
            val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(android.graphics.Color.BLUE)
                jpeg.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
            } finally { bitmap.recycle() }
            val futureItem = EvidenceStore(directory).use { store ->
                store.enqueue(scope, EvidenceKind.PHOTO, jpeg, Instant.parse(config.getString("futureEventDate")),
                    null, 0, configuration.policy, previousEvidence)
            }
            val item = EvidenceStore(directory).use { store ->
                store.enqueue(scope, EvidenceKind.PHOTO, jpeg, Instant.now(), null, 0, configuration.policy, previousEvidence)
            }
            EvidenceStore(directory).use { store ->
                lostAck {
                    EvidenceUploader(store, evidenceApi.transport(store, configuration)).retry(scope, { true }) { _, _, _ -> }
                }
                val rejectedStart = store.list(scope).first()
                assertEquals(futureItem.id, rejectedStart.id)
                assertEquals(EvidenceStatus.REJECTED, rejectedStart.status)
                assertEquals(EvidenceError.INVALID_REQUEST, rejectedStart.rejection)
                assertNull(rejectedStart.remoteId)
                assertNull(store.uploadId(futureItem))
                assertEquals(EvidenceError.NETWORK, store.list(scope).last().lastError)
            }
            val confirmedId = EvidenceStore(directory).use { store ->
                assertNull(store.list(scope).single { it.id == item.id }.remoteId)
                EvidenceUploader(store, evidenceApi.transport(store, configuration)).retry(scope, { true }) { _, _, _ ->
                    fail("A server-confirmed upload must recover without sending bytes")
                }
                val restored = store.list(scope).single { it.id == item.id }
                val remoteId = checkNotNull(restored.remoteId)
                val confirmedResult = checkNotNull(restored.serverResult)
                assertEquals(item.id, confirmedResult.operationId)
                assertEquals(200, confirmedResult.httpStatus)
                assertNull(restored.lastError)
                val recoveredLoad = evidenceApi.session(actor) { grant ->
                    evidenceApi.request("POST", "/siniestros/${scope.claimId}/cargas", grant.accessToken,
                        body = JSONObject().put("mimeType", item.kind.mime).put("tamanoBytes", item.bytes)
                            .put("sha256", item.sha256).put("fechaCaptura", item.capturedAt.toString()),
                        idempotencyKey = item.id)
                }
                assertEquals(store.uploadId(item), recoveredLoad.getString("id"))
                assertEquals(remoteId, recoveredLoad.getString("evidenciaId"))
                assertEquals(confirmedResult, SyncResult.fromResponse(recoveredLoad, item.id))
                EvidenceUploader(store, evidenceApi.transport(store, configuration)).retry(scope, { true }) { _, _, _ -> fail() }
                val rejectedStart = store.list(scope).single { it.id == futureItem.id }
                val startResult = checkNotNull(rejectedStart.serverResult)
                val transport = evidenceApi.transport(store, configuration)
                assertEquals(400, startResult.httpStatus)
                assertEquals("solicitud_invalida", startResult.code)
                assertEquals(startResult, httpFailure(400, "solicitud_invalida") {
                    transport.confirmed(scope, futureItem.id)
                }.serverResult)
                assertNull(transport.cancel(rejectedStart))
                assertEquals(rejectedStart, store.list(scope).single { it.id == futureItem.id })
                store.removeRejected(rejectedStart)
                assertEquals(item.id, store.list(scope).single().id)
                remoteId
            }
            assertEquals(previousEvidence + confirmedId, evidenceApi.confirmedIds(scope))

            val invalidItem = EvidenceStore(directory).use { store ->
                store.enqueue(scope, EvidenceKind.PHOTO, jpeg, Instant.now(), null, 0,
                    configuration.policy, evidenceApi.confirmedIds(scope))
            }
            val corrupted = File(directory, "corrupted.jpg").apply {
                writeBytes(jpeg.readBytes().also { it[0] = (it[0].toInt() xor 1).toByte() })
            }
            EvidenceStore(directory).use { store ->
                lostAck {
                    evidenceApi.transport(store, configuration).upload(invalidItem, corrupted) {}
                }
                store.fail(invalidItem, EvidenceError.NETWORK)
            }
            EvidenceStore(directory).use { store ->
                assertNull(store.list(scope).single { it.id == invalidItem.id }.rejection)
                val uploader = EvidenceUploader(store, evidenceApi.transport(store, configuration))
                uploader.retry(scope, { true }) { _, _, _ -> fail("A recorded definitive rejection must not resend bytes") }
                val restored = store.list(scope).single { it.id == invalidItem.id }
                assertEquals(EvidenceStatus.REJECTED, restored.status)
                assertEquals(EvidenceError.FORMAT, restored.rejection)
                assertEquals("integridad_invalida", restored.errorCode)
                val invalidResult = checkNotNull(restored.serverResult)
                assertEquals(invalidItem.id, invalidResult.operationId)
                assertEquals(422, invalidResult.httpStatus)
                assertEquals("integridad_invalida", invalidResult.code)
                val recoveredLoad = evidenceApi.session(actor) { grant ->
                    evidenceApi.request("GET", "/siniestros/${scope.claimId}/cargas/${store.uploadId(invalidItem)}",
                        grant.accessToken, operationId = invalidItem.id)
                }
                assertEquals(invalidResult, SyncResult.fromResponse(recoveredLoad, invalidItem.id))
                assertArrayEquals(jpeg.readBytes(), store.file(restored).readBytes())
                uploader.retry(scope, { true }) { _, _, _ -> fail() }
                assertEquals(restored, store.list(scope).single { it.id == invalidItem.id })
            }
            assertEquals(previousEvidence + confirmedId, evidenceApi.confirmedIds(scope))
        } finally {
            try { credentials.clear() }
            finally {
                try { KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(namespace) } }
                finally {
                    check(context.deleteSharedPreferences(namespace))
                    check(directory.deleteRecursively())
                }
            }
        }
    }

    private suspend fun httpFailure(status: Int, code: String, request: suspend () -> Any?): EvidenceHttpProblem {
        try { request() }
        catch (error: EvidenceHttpProblem) {
            assertEquals(status, error.status)
            assertEquals(code, error.code)
            val result = checkNotNull(error.serverResult)
            assertEquals(status, result.httpStatus)
            assertEquals(code, result.code)
            return error
        }
        throw AssertionError("Expected a backend rejection with HTTP $status")
    }

    private suspend fun lostAck(request: suspend () -> Any?) {
        try { request() }
        catch (error: EvidenceProblem) {
            assertFalse("The confirmation response must be lost, not returned as an HTTP rejection", error is EvidenceHttpProblem)
            assertEquals(EvidenceError.NETWORK, error.reason)
            return
        }
        throw AssertionError("The fixture must interrupt the confirmation ACK after the backend processes it")
    }
}
