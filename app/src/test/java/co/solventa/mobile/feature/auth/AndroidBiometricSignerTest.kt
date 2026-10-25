package co.solventa.mobile.feature.auth

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class AndroidBiometricSignerTest {
    @Test fun capabilityAndPromptErrorsRemainRecoverableWithoutAuthentication() {
        assertNull(biometricCapabilityFailure(BiometricManager.BIOMETRIC_SUCCESS))
        val capabilities = mapOf(
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED to BiometricFailure.NOT_ENROLLED,
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE to BiometricFailure.NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE to BiometricFailure.UNAVAILABLE,
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED to BiometricFailure.SECURITY_UPDATE,
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED to BiometricFailure.UNSUPPORTED,
            BiometricManager.BIOMETRIC_STATUS_UNKNOWN to BiometricFailure.UNKNOWN,
            999 to BiometricFailure.UNKNOWN,
        )
        capabilities.forEach { (status, expected) -> assertEquals(expected, biometricCapabilityFailure(status)) }
        val promptErrors = mapOf(
            BiometricPrompt.ERROR_CANCELED to BiometricFailure.CANCELLED,
            BiometricPrompt.ERROR_USER_CANCELED to BiometricFailure.CANCELLED,
            BiometricPrompt.ERROR_NEGATIVE_BUTTON to BiometricFailure.CANCELLED,
            BiometricPrompt.ERROR_LOCKOUT to BiometricFailure.LOCKED_OUT,
            BiometricPrompt.ERROR_LOCKOUT_PERMANENT to BiometricFailure.LOCKED_OUT,
            BiometricPrompt.ERROR_NO_BIOMETRICS to BiometricFailure.NOT_ENROLLED,
            BiometricPrompt.ERROR_HW_NOT_PRESENT to BiometricFailure.NO_HARDWARE,
            BiometricPrompt.ERROR_HW_UNAVAILABLE to BiometricFailure.UNAVAILABLE,
            BiometricPrompt.ERROR_TIMEOUT to BiometricFailure.UNAVAILABLE,
            BiometricPrompt.ERROR_SECURITY_UPDATE_REQUIRED to BiometricFailure.SECURITY_UPDATE,
            999 to BiometricFailure.UNKNOWN,
        )
        promptErrors.forEach { (code, expected) -> assertEquals(expected, biometricPromptFailure(code)) }
    }

    @Test fun passwordPinUnknownTypeAndMissingOrDifferentCryptoObjectNeverSign() {
        val signature = Signature.getInstance("SHA256withECDSA")
        val other = Signature.getInstance("SHA256withECDSA")
        listOf(BiometricPrompt.AUTHENTICATION_RESULT_TYPE_DEVICE_CREDENTIAL,
            BiometricPrompt.AUTHENTICATION_RESULT_TYPE_UNKNOWN).forEach { type ->
            val failure = assertThrows(BiometricProblem::class.java) {
                signAuthenticatedChallenge(signature, signature, type, byteArrayOf(1))
            }
            assertEquals(BiometricFailure.UNKNOWN, failure.reason)
        }
        listOf(null, other).forEach { actual ->
            assertThrows(BiometricProblem::class.java) {
                signAuthenticatedChallenge(signature, actual,
                    BiometricPrompt.AUTHENTICATION_RESULT_TYPE_BIOMETRIC, byteArrayOf(1))
            }
        }
    }

    @Test fun standardSignatureEncodingVerifiesOnlyTheExactPayload() {
        // JVM-only software key checks encoding, not biometric or hardware authorization.
        val pair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val signature = Signature.getInstance("SHA256withECDSA").apply { initSign(pair.private) }
        val payload = "synthetic-server-challenge".toByteArray()
        val result = signAuthenticatedChallenge(signature, signature,
            BiometricPrompt.AUTHENTICATION_RESULT_TYPE_BIOMETRIC, payload)
        assertEquals(0x30, result[0].toInt())
        val verifier = Signature.getInstance("SHA256withECDSA").apply {
            initVerify(pair.public)
            update(payload)
        }
        assertTrue(verifier.verify(result))
        verifier.update("different-purpose".toByteArray())
        assertFalse(verifier.verify(result))
        assertEquals("X.509", pair.public.format)
    }
}
