package co.solventa.mobile.feature.auth

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.ProviderException
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import javax.inject.Inject

internal data class BiometricKey(
    val alias: String,
    val publicKeySpki: ByteArray,
)

internal class AndroidBiometricKeys @Inject constructor() {
    private fun store(): KeyStore = try {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    } catch (error: GeneralSecurityException) {
        throw BiometricProblem(biometricKeyFailure(error))
    } catch (_: IOException) {
        throw BiometricProblem(BiometricFailure.KEY_UNAVAILABLE)
    } catch (error: ProviderException) {
        throw BiometricProblem(biometricKeyFailure(error))
    }

    fun create(): BiometricKey {
        val alias = PREFIX + UUID.randomUUID()
        val spec = biometricKeySpec(alias)
        val keys = store()
        try {
            val pair = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(spec)
            }.generateKeyPair()
            val info = KeyFactory.getInstance(pair.private.algorithm, "AndroidKeyStore")
                .getKeySpec(pair.private, KeyInfo::class.java)
            if (!isPerOperationBiometricKey(info)) throw BiometricProblem(BiometricFailure.INSECURE_KEY)
            return BiometricKey(alias, pair.public.encoded)
        } catch (error: GeneralSecurityException) {
            delete(alias)
            throw BiometricProblem(biometricKeyFailure(error))
        } catch (error: ProviderException) {
            delete(alias)
            throw BiometricProblem(biometricKeyFailure(error))
        } catch (error: BiometricProblem) {
            delete(alias)
            throw error
        }
    }

    fun signature(alias: String): Signature {
        requireOwnedAlias(alias)
        try {
            val key = store().getKey(alias, null) as? PrivateKey
                ?: throw BiometricProblem(BiometricFailure.KEY_MISSING)
            return Signature.getInstance("SHA256withECDSA").apply { initSign(key) }
        } catch (error: GeneralSecurityException) {
            throw BiometricProblem(biometricKeyFailure(error))
        } catch (error: ProviderException) {
            throw BiometricProblem(biometricKeyFailure(error))
        }
    }

    fun delete(alias: String) {
        requireOwnedAlias(alias)
        try {
            store().deleteEntry(alias)
        } catch (error: GeneralSecurityException) {
            throw BiometricProblem(biometricKeyFailure(error))
        } catch (error: ProviderException) {
            throw BiometricProblem(biometricKeyFailure(error))
        }
    }

    private fun requireOwnedAlias(alias: String) {
        require(alias.startsWith(PREFIX) && alias.removePrefix(PREFIX).matches(Regex("[a-f0-9-]{36}")))
    }

    companion object {
        private const val PREFIX = "solventa.mobile.biometric."
    }
}

@Suppress("DEPRECATION")
internal fun biometricKeySpec(alias: String): KeyGenParameterSpec {
    return KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
        .setDigests(KeyProperties.DIGEST_SHA256)
        .setUserAuthenticationRequired(true)
        .setInvalidatedByBiometricEnrollment(true)
        .apply {
            if (Build.VERSION.SDK_INT >= 30) {
                setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            } else {
                setUserAuthenticationValidityDurationSeconds(-1)
            }
        }.build()
}

@Suppress("DEPRECATION")
private fun isPerOperationBiometricKey(info: KeyInfo): Boolean =
    info.isUserAuthenticationRequired && info.isInvalidatedByBiometricEnrollment &&
        info.userAuthenticationValidityDurationSeconds <= 0 &&
        (Build.VERSION.SDK_INT < 30 || info.userAuthenticationType == KeyProperties.AUTH_BIOMETRIC_STRONG)

internal fun biometricKeyFailure(error: Throwable): BiometricFailure =
    if (generateSequence(error) { it.cause }.any { it is KeyPermanentlyInvalidatedException }) {
        BiometricFailure.KEY_INVALIDATED
    } else {
        BiometricFailure.KEY_UNAVAILABLE
    }
