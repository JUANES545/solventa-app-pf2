package co.solventa.mobile.feature.auth

import android.app.Application
import android.os.Build
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.InvalidKeyException
import java.security.spec.ECGenParameterSpec

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class AndroidBiometricKeysTest {
    @Config(sdk = [26, 29, 30, 35])
    @Suppress("DEPRECATION")
    @Test fun keyPolicyRequiresPerOperationBiometricsAndInvalidatesNewEnrollment() {
        val spec = biometricKeySpec("test-policy")
        assertEquals(KeyProperties.PURPOSE_SIGN, spec.purposes)
        assertEquals("secp256r1", (spec.algorithmParameterSpec as ECGenParameterSpec).name)
        assertArrayEquals(arrayOf(KeyProperties.DIGEST_SHA256), spec.digests)
        assertTrue(spec.isUserAuthenticationRequired)
        assertTrue(spec.isInvalidatedByBiometricEnrollment)
        assertNull(spec.attestationChallenge)
        if (Build.VERSION.SDK_INT >= 30) {
            assertEquals(0, spec.userAuthenticationValidityDurationSeconds)
            assertEquals(KeyProperties.AUTH_BIOMETRIC_STRONG, spec.userAuthenticationType)
        } else {
            assertEquals(-1, spec.userAuthenticationValidityDurationSeconds)
        }
    }

    @Test fun invalidatedKeysAreReportedEvenWhenWrappedByTheProvider() {
        assertEquals(BiometricFailure.KEY_INVALIDATED,
            biometricKeyFailure(InvalidKeyException(KeyPermanentlyInvalidatedException())))
        assertEquals(BiometricFailure.KEY_UNAVAILABLE, biometricKeyFailure(InvalidKeyException()))
    }

    @Test fun unrelatedKeystoreAliasesCannotBeReadOrDeleted() {
        val keys = AndroidBiometricKeys()
        listOf("solventa.mobile.auth.v1", "another-app", "solventa.mobile.biometric.invalid").forEach { alias ->
            assertThrows(IllegalArgumentException::class.java) { keys.signature(alias) }
            assertThrows(IllegalArgumentException::class.java) { keys.delete(alias) }
        }
    }
}
