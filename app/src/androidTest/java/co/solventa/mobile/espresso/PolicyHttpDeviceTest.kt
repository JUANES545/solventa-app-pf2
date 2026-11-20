package co.solventa.mobile.espresso

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import co.solventa.mobile.BuildConfig
import co.solventa.mobile.feature.auth.AuthRepository
import co.solventa.mobile.feature.auth.EncryptedAuthStore
import co.solventa.mobile.feature.auth.MobileAuthApi
import co.solventa.mobile.feature.claim.ClaimPayload
import co.solventa.mobile.feature.claim.ClaimStore
import co.solventa.mobile.feature.claim.MobileClaimApi
import co.solventa.mobile.feature.evidence.MobileEvidenceApi
import co.solventa.mobile.feature.policy.MobilePolicyApi
import co.solventa.mobile.feature.policy.PolicyError
import co.solventa.mobile.feature.policy.PolicyStatus
import co.solventa.mobile.feature.policy.policyError
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
class PolicyHttpDeviceTest {
    @Test fun ownPolicyReadsSelectedReportAndStatusUseRealHttpWithoutNavigationMutations() = runBlocking {
        check(BuildConfig.APPLICATION_ID == "co.solventa.mobile.pf2.claimtest")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = InstrumentationRegistry.getArguments().getString("fixtureFile") ?: "sol189-http-fixture.json"
        check(Regex("sol189-http-fixture(?:-[0-9a-f-]{36})?\\.json").matches(name))
        val file = File(context.noBackupFilesDir, name)
        check(file.isFile && file.length() in 1..16_384) { "SOL189 requires its private synthetic HTTP fixture" }
        val config = try { JSONObject(file.readText()) }
        catch (_: Exception) { throw AssertionError("The private SOL189 fixture is not valid JSON") }
        check(config.getInt("schemaVersion") == 1 && config.getBoolean("synthetic"))
        val base = config.getString("baseUrl")
        check(base == "http://127.0.0.1:18731/movil/v1") { "SOL189 requires its dedicated loopback reverse" }
        val namespace = "sol189-http-${UUID.randomUUID()}"
        val directory = File(context.noBackupFilesDir, namespace).apply { check(mkdir()) }
        val credentials = EncryptedAuthStore(context, namespace, namespace)
        try {
            val auth = AuthRepository(MobileAuthApi(base), credentials)
            val actor = auth.login(config.getString("email"), config.getString("password")).id
            val http = MobileEvidenceApi(auth, base)
            val policies = MobilePolicyApi(http)
            val claims = MobileClaimApi(http)
            val before = claims.claims(actor).map { it.id }.toSet()
            val id = config.getString("policyId")
            val own = policies.list(actor).single { it.id == id }
            assertEquals(PolicyStatus.ACTIVE, own.status)
            assertTrue(own.simulated)
            assertTrue(own.number.isNotBlank())
            assertTrue(own.mainCoverage.isNotBlank())
            assertEquals(own, policies.detail(actor, id))
            assertEquals(own, policies.detail(actor, id))
            try { policies.detail(actor, config.getString("otherPolicyId")); fail("Foreign policy was exposed") }
            catch (error: Exception) { assertEquals(PolicyError.UNAVAILABLE, policyError(error)) }
            assertEquals(before, claims.claims(actor).map { it.id }.toSet())
            val report = ClaimStore(directory).use {
                it.enqueue(actor, ClaimPayload(id, Instant.parse(config.getString("eventDate")),
                    "Validación SOL189", "Lugar sintético", "Reporte sintético iniciado desde una póliza propia."))
            }
            val receipt = claims.send(report)
            assertEquals(receipt, claims.send(report))
            assertEquals(receipt.id, claims.status(actor, receipt.id).id)
            assertEquals(before + receipt.id, claims.claims(actor).map { it.id }.toSet())
            val accessBeforeLogout = checkNotNull(credentials.read()).accessToken
            auth.logout()
            assertNull(credentials.read())
            try { policies.list(actor); fail("Logged-out session was reused") }
            catch (error: Exception) { assertEquals(PolicyError.SESSION, policyError(error)) }
            try { http.request("GET", "/polizas", accessBeforeLogout); fail("Server accepted a logged-out access token") }
            catch (error: co.solventa.mobile.feature.evidence.EvidenceHttpProblem) {
                assertEquals(401, error.status)
                assertEquals("sesion_invalida", error.code)
            }
            val actorB = auth.login(config.getString("otherEmail"), config.getString("otherPassword")).id
            assertNotEquals(actor, actorB)
            assertFalse(policies.list(actorB).any { it.id == id })
            try { policies.detail(actorB, id); fail("Previous actor policy was exposed") }
            catch (error: Exception) { assertEquals(PolicyError.UNAVAILABLE, policyError(error)) }
            auth.logout()
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
}
