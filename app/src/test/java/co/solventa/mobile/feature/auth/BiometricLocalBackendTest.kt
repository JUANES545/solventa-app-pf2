package co.solventa.mobile.feature.auth

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class BiometricLocalBackendTest {
    @Test fun syntheticJcaProofWorksWithTheExplicitLocalBackend() = runBlocking {
        assumeTrue(System.getenv("SOLVENTA_BIOMETRIC_LOCAL_E2E") == "1")
        val email = checkNotNull(System.getenv("SOLVENTA_LOCAL_TEST_EMAIL"))
        val password = checkNotNull(System.getenv("SOLVENTA_LOCAL_TEST_PASSWORD"))
        val api = MobileAuthApi("http://127.0.0.1:18717/movil/v1")
        var storedGrant: AuthGrant? = null
        var credential: BiometricCredential? = null
        val authStore = object : AuthStore {
            override fun read() = storedGrant
            override fun write(grant: AuthGrant) { storedGrant = grant }
            override fun clear() { storedGrant = null }
        }
        val credentialStore = object : BiometricCredentialStore {
            override fun read() = credential
            override fun write(value: BiometricCredential) { credential = value }
            override fun clear() { credential = null }
        }
        val auth = AuthRepository(api, authStore)
        val biometrics = BiometricRepository(api, auth, credentialStore, "https://identidad.solventa.test")
        // Software key is only a JVM interoperability fixture, not Android biometric evidence.
        val pair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        fun sign(message: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(message)
            sign()
        }
        val user = auth.login(email, password)
        var confirmed = false
        try {
            val pending = biometrics.beginEnrollment(password, BiometricKey("jvm-local-only", pair.public.encoded), user.id)
            biometrics.confirmEnrollment(pending, sign(pending.challenge.message))
            confirmed = true
            val id = checkNotNull(credential).credentialId
            assertTrue(biometrics.credentials(user.id).any { it.id == id && it.active })
            auth.logout()
            assertNull(storedGrant)
            assertNotNull(credential)
            val login = biometrics.loginChallenge()
            val proof = sign(login.challenge.message)
            biometrics.login(login, proof)
            val accepted = checkNotNull(storedGrant)
            assertTrue(accepted.user.id == user.id)
            try {
                api.biometricLogin(login.challenge, proof)
                fail("A consumed challenge must reject replay")
            } catch (error: AuthHttpError) { assertEquals(401, error.status) }
            val invalid = biometrics.loginChallenge()
            try {
                biometrics.login(invalid, sign(invalid.challenge.message + byteArrayOf(10)))
                fail("A signature over different bytes must be rejected")
            } catch (error: AuthHttpError) { assertEquals(401, error.status) }
            assertTrue(storedGrant == accepted)
            biometrics.revoke(id, user.id)
            confirmed = false
            assertTrue(biometrics.credentials(user.id).none { it.id == id && it.active })
            try {
                api.refresh(accepted.refreshToken)
                fail("A revoked credential must invalidate its refresh family")
            } catch (error: AuthHttpError) { assertEquals(401, error.status) }
            try {
                api.biometricChallenge(id)
                fail("A revoked credential must not receive another login challenge")
            } catch (error: AuthHttpError) { assertEquals(401, error.status) }
        } finally {
            if (confirmed) {
                val cleanup = api.login(email, password)
                api.revokeBiometricCredential(cleanup.accessToken, checkNotNull(credential).credentialId)
                api.logout(cleanup.accessToken, cleanup.refreshToken)
            }
            auth.logout()
            credentialStore.clear()
        }
    }
}
