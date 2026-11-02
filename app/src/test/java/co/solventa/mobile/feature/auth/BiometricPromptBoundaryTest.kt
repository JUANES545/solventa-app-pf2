package co.solventa.mobile.feature.auth

import android.app.Application
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedConstruction
import org.mockito.MockedStatic
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyPairGenerator
import java.security.ProviderException
import java.security.Signature

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class BiometricPromptBoundaryTest {
    private val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
    private val activity get() = controller.get()
    private val keys = mock(AndroidBiometricKeys::class.java)
    private val manager = mock(BiometricManager::class.java)
    private lateinit var managers: MockedStatic<BiometricManager>
    private lateinit var prompts: MockedConstruction<BiometricPrompt>
    private lateinit var callback: BiometricPrompt.AuthenticationCallback
    private lateinit var signer: AndroidBiometricSigner
    private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private val signature get() = Signature.getInstance("SHA256withECDSA").apply { initSign(pair.private) }
    private val payload = "synthetic-challenge".toByteArray()
    private val prompt get() = prompts.constructed().single()

    private fun authenticationResult(crypto: BiometricPrompt.CryptoObject?, type: Int) =
        mock(BiometricPrompt.AuthenticationResult::class.java).also {
            `when`(it.cryptoObject).thenReturn(crypto)
            `when`(it.authenticationType).thenReturn(type)
        }

    @Before fun setUp() {
        managers = mockStatic(BiometricManager::class.java)
        managers.`when`<BiometricManager> { BiometricManager.from(activity) }.thenReturn(manager)
        prompts = mockConstruction(BiometricPrompt::class.java) { _, context ->
            callback = context.arguments()[2] as BiometricPrompt.AuthenticationCallback
        }
        signer = AndroidBiometricSigner(activity, keys)
    }

    @After fun tearDown() {
        controller.pause().stop().destroy()
        prompts.close()
        managers.close()
    }

    @Test fun onlySuccessfulBiometricCryptoObjectCompletesTheWaitingSignature() = runBlocking {
        val expected = signature
        `when`(keys.signature("owned")).thenReturn(expected)
        val result = async(start = CoroutineStart.UNDISPATCHED) { signer.sign("owned", payload) }
        callback.onAuthenticationFailed()
        assertFalse(result.isCompleted)
        callback.onAuthenticationSucceeded(authenticationResult(
            BiometricPrompt.CryptoObject(expected), BiometricPrompt.AUTHENTICATION_RESULT_TYPE_BIOMETRIC))
        val bytes = result.await()
        assertTrue(Signature.getInstance("SHA256withECDSA").run {
            initVerify(pair.public); update(payload); verify(bytes)
        })
        callback.onAuthenticationFailed()
        callback.onAuthenticationSucceeded(authenticationResult(null,
            BiometricPrompt.AUTHENTICATION_RESULT_TYPE_UNKNOWN))
        callback.onAuthenticationError(BiometricPrompt.ERROR_TIMEOUT, "timeout")
    }

    @Test fun rejectionCancellationAndWrongCryptoNeverReturnASignature() = runBlocking {
        supervisorScope {
            for (action in 0..3) {
                `when`(keys.signature("owned")).thenReturn(signature)
                val result = async(start = CoroutineStart.UNDISPATCHED) { signer.sign("owned", payload) }
                when (action) {
                    0 -> callback.onAuthenticationError(BiometricPrompt.ERROR_LOCKOUT, "locked")
                    1 -> callback.onAuthenticationSucceeded(authenticationResult(null,
                        BiometricPrompt.AUTHENTICATION_RESULT_TYPE_BIOMETRIC))
                    2 -> signer.cancel()
                    else -> signer.onDestroy(activity)
                }
                val failure = runCatching { result.await() }.exceptionOrNull() as BiometricProblem
                assertEquals(when (action) {
                    0 -> BiometricFailure.LOCKED_OUT
                    1 -> BiometricFailure.UNKNOWN
                    else -> BiometricFailure.CANCELLED
                }, failure.reason)
            }
            assertEquals(BiometricFailure.CANCELLED,
                (runCatching { signer.sign("owned", payload) }.exceptionOrNull() as BiometricProblem).reason)
        }
    }

    @Test fun concurrentRequestsAndCancelledCoroutinesCannotShareThePrompt() = runBlocking {
        supervisorScope {
            `when`(keys.signature("owned")).thenReturn(signature)
            val pending = async(start = CoroutineStart.UNDISPATCHED) { signer.sign("owned", payload) }
            assertEquals(BiometricFailure.IN_PROGRESS,
                (runCatching { signer.sign("owned", payload) }.exceptionOrNull() as BiometricProblem).reason)
            assertEquals(BiometricFailure.IN_PROGRESS,
                assertThrows(BiometricProblem::class.java) { signer.sign(signature, payload) }.reason)
            pending.cancel()
            pending.join()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            verify(prompt, atLeastOnce()).cancelAuthentication()
            callback.onAuthenticationError(BiometricPrompt.ERROR_CANCELED, "cancelled")
        }
    }

    @Test fun capabilityIsRequiredBeforeCreatingOrUsingAKey() = runBlocking {
        `when`(manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG))
            .thenReturn(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED)
        assertEquals(BiometricFailure.NOT_ENROLLED,
            assertThrows(BiometricProblem::class.java) { signer.createKey() }.reason)
        supervisorScope {
            `when`(keys.signature("owned")).thenReturn(signature)
            val result = async(start = CoroutineStart.UNDISPATCHED) { signer.sign("owned", payload) }
            assertEquals(BiometricFailure.NOT_ENROLLED,
                (runCatching { result.await() }.exceptionOrNull() as BiometricProblem).reason)
        }
        verify(keys, never()).create()
        `when`(manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG))
            .thenReturn(BiometricManager.BIOMETRIC_SUCCESS)
        val key = BiometricKey("owned", pair.public.encoded)
        `when`(keys.create()).thenReturn(key)
        assertSame(key, signer.createKey())
        signer.deleteKey("owned")
        verify(keys).delete("owned")
        assertThrows(IllegalArgumentException::class.java) { signer.sign(signature, byteArrayOf()) }
        Unit
    }

    @Test fun promptAndCryptoProviderFailuresRemainExplicit() = runBlocking {
        supervisorScope {
            for ((error, reason) in listOf(
                ProviderException("unavailable") to BiometricFailure.KEY_UNAVAILABLE,
                IllegalStateException("destroyed") to BiometricFailure.CANCELLED,
                IllegalArgumentException("invalid") to BiometricFailure.UNKNOWN,
            )) {
                `when`(keys.signature("owned")).thenReturn(signature)
                doThrow(error).`when`(prompt).authenticate(any(BiometricPrompt.PromptInfo::class.java),
                    any(BiometricPrompt.CryptoObject::class.java))
                val result = async(start = CoroutineStart.UNDISPATCHED) { signer.sign("owned", payload) }
                assertEquals(reason, (runCatching { result.await() }.exceptionOrNull() as BiometricProblem).reason)
            }
            doNothing().`when`(prompt).authenticate(any(BiometricPrompt.PromptInfo::class.java),
                any(BiometricPrompt.CryptoObject::class.java))
            for (error in listOf(java.security.SignatureException("unavailable"), ProviderException("unavailable"))) {
                val broken = mock(Signature::class.java)
                doThrow(error).`when`(broken).update(payload)
                `when`(keys.signature("owned")).thenReturn(broken)
                val result = async(start = CoroutineStart.UNDISPATCHED) { signer.sign("owned", payload) }
                callback.onAuthenticationSucceeded(authenticationResult(
                    BiometricPrompt.CryptoObject(broken), BiometricPrompt.AUTHENTICATION_RESULT_TYPE_BIOMETRIC))
                assertEquals(BiometricFailure.KEY_UNAVAILABLE,
                    (runCatching { result.await() }.exceptionOrNull() as BiometricProblem).reason)
            }
        }
    }
}
