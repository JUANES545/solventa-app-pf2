package co.solventa.mobile.feature.auth

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import co.solventa.mobile.R
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.security.Signature

internal enum class BiometricFailure {
    NO_HARDWARE, NOT_ENROLLED, UNAVAILABLE, SECURITY_UPDATE, UNSUPPORTED, UNKNOWN,
    CANCELLED, LOCKED_OUT, KEY_MISSING, KEY_INVALIDATED, KEY_UNAVAILABLE, INSECURE_KEY, IN_PROGRESS,
}

internal class BiometricProblem(val reason: BiometricFailure) : Exception()

internal sealed interface BiometricSigningEvent {
    data class Signed(val signatureDer: ByteArray) : BiometricSigningEvent
    data class Rejected(val reason: BiometricFailure) : BiometricSigningEvent
    data object NotRecognized : BiometricSigningEvent
}

internal interface BiometricDevice {
    fun createKey(): BiometricKey
    fun deleteKey(alias: String)
    suspend fun sign(alias: String, payload: ByteArray): ByteArray
}

internal class AndroidBiometricSigner(
    private val activity: FragmentActivity,
    private val keys: AndroidBiometricKeys = AndroidBiometricKeys(),
) : DefaultLifecycleObserver, BiometricDevice {
    private data class PendingSignature(val signature: Signature, val payload: ByteArray)
    private var pending: PendingSignature? = null
    private var continuation: CancellableContinuation<ByteArray>? = null
    private var destroyed = false
    private val manager = BiometricManager.from(activity)
    private val promptInfo = BiometricPrompt.PromptInfo.Builder()
        .setTitle(activity.getString(R.string.biometric_confirm_title))
        .setSubtitle(activity.getString(R.string.biometric_confirm_body))
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        .setNegativeButtonText(activity.getString(R.string.biometric_use_password))
        .build()
    private val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationFailed() {
                if (pending != null) onEvent(BiometricSigningEvent.NotRecognized)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                reject(biometricPromptFailure(errorCode))
            }

            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val expected = pending ?: return
                pending = null
                try {
                    val signed = signAuthenticatedChallenge(expected.signature, result.cryptoObject?.signature,
                        result.authenticationType, expected.payload)
                    onEvent(BiometricSigningEvent.Signed(signed))
                } catch (error: BiometricProblem) {
                    onEvent(BiometricSigningEvent.Rejected(error.reason))
                } catch (error: GeneralSecurityException) {
                    onEvent(BiometricSigningEvent.Rejected(biometricKeyFailure(error)))
                } catch (error: ProviderException) {
                    onEvent(BiometricSigningEvent.Rejected(biometricKeyFailure(error)))
                }
            }
        })

    init {
        activity.lifecycle.addObserver(this)
    }

    override fun createKey(): BiometricKey {
        biometricCapabilityFailure(manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG))?.let {
            throw BiometricProblem(it)
        }
        return keys.create()
    }

    override fun deleteKey(alias: String) = keys.delete(alias)

    override suspend fun sign(alias: String, payload: ByteArray): ByteArray {
        if (destroyed) throw BiometricProblem(BiometricFailure.CANCELLED)
        if (continuation != null) throw BiometricProblem(BiometricFailure.IN_PROGRESS)
        val signature = keys.signature(alias)
        return suspendCancellableCoroutine { waiting ->
            continuation = waiting
            waiting.invokeOnCancellation {
                activity.runOnUiThread { if (continuation === waiting) cancel() }
            }
            sign(signature, payload)
        }
    }

    fun sign(signature: Signature, payload: ByteArray) {
        if (pending != null) throw BiometricProblem(BiometricFailure.IN_PROGRESS)
        require(payload.isNotEmpty())
        biometricCapabilityFailure(manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG))?.let {
            onEvent(BiometricSigningEvent.Rejected(it))
            return
        }
        try {
            pending = PendingSignature(signature, payload.copyOf())
            prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(signature))
        } catch (error: GeneralSecurityException) {
            pending = null
            onEvent(BiometricSigningEvent.Rejected(biometricKeyFailure(error)))
        } catch (error: ProviderException) {
            pending = null
            onEvent(BiometricSigningEvent.Rejected(biometricKeyFailure(error)))
        } catch (_: IllegalStateException) {
            pending = null
            onEvent(BiometricSigningEvent.Rejected(BiometricFailure.CANCELLED))
        } catch (_: IllegalArgumentException) {
            pending = null
            onEvent(BiometricSigningEvent.Rejected(BiometricFailure.UNKNOWN))
        }
    }

    fun cancel() {
        reject(BiometricFailure.CANCELLED)
        prompt.cancelAuthentication()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        destroyed = true
        cancel()
        owner.lifecycle.removeObserver(this)
    }

    private fun reject(reason: BiometricFailure) {
        if (pending == null) return
        pending = null
        onEvent(BiometricSigningEvent.Rejected(reason))
    }

    private fun onEvent(event: BiometricSigningEvent) {
        if (event is BiometricSigningEvent.NotRecognized) return
        val waiting = continuation ?: return
        continuation = null
        if (!waiting.isActive) return
        when (event) {
            is BiometricSigningEvent.Signed -> waiting.resume(event.signatureDer)
            is BiometricSigningEvent.Rejected -> waiting.resumeWithException(BiometricProblem(event.reason))
            BiometricSigningEvent.NotRecognized -> Unit
        }
    }
}

internal fun signAuthenticatedChallenge(
    expected: Signature,
    actual: Signature?,
    authenticationType: Int,
    payload: ByteArray,
): ByteArray {
    if (authenticationType != BiometricPrompt.AUTHENTICATION_RESULT_TYPE_BIOMETRIC || actual !== expected) {
        throw BiometricProblem(BiometricFailure.UNKNOWN)
    }
    expected.update(payload)
    return expected.sign()
}

internal fun biometricCapabilityFailure(status: Int): BiometricFailure? = when (status) {
    BiometricManager.BIOMETRIC_SUCCESS -> null
    BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricFailure.NO_HARDWARE
    BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricFailure.NOT_ENROLLED
    BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> BiometricFailure.UNAVAILABLE
    BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> BiometricFailure.SECURITY_UPDATE
    BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED -> BiometricFailure.UNSUPPORTED
    else -> BiometricFailure.UNKNOWN
}

internal fun biometricPromptFailure(code: Int): BiometricFailure = when (code) {
    BiometricPrompt.ERROR_CANCELED, BiometricPrompt.ERROR_USER_CANCELED,
    BiometricPrompt.ERROR_NEGATIVE_BUTTON -> BiometricFailure.CANCELLED
    BiometricPrompt.ERROR_LOCKOUT, BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> BiometricFailure.LOCKED_OUT
    BiometricPrompt.ERROR_NO_BIOMETRICS -> BiometricFailure.NOT_ENROLLED
    BiometricPrompt.ERROR_HW_NOT_PRESENT -> BiometricFailure.NO_HARDWARE
    BiometricPrompt.ERROR_HW_UNAVAILABLE, BiometricPrompt.ERROR_TIMEOUT -> BiometricFailure.UNAVAILABLE
    BiometricPrompt.ERROR_SECURITY_UPDATE_REQUIRED -> BiometricFailure.SECURITY_UPDATE
    else -> BiometricFailure.UNKNOWN
}
