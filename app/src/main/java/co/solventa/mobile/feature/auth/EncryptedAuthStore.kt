package co.solventa.mobile.feature.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject

class EncryptedAuthStore internal constructor(
    context: Context,
    preferencesName: String,
    private val alias: String,
) : AuthStore {
    @Inject constructor(@ApplicationContext context: Context) :
        this(context, "mobile-auth", "solventa.mobile.auth.v1")

    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keys.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    override fun write(grant: AuthGrant) {
        try {
            val json = JSONObject().put("tokenType", "Bearer")
                .put("usuario", JSONObject().put("id", grant.user.id).put("nombre", grant.user.name)
                    .put("correo", grant.user.email).put("rol", "cliente"))
                .put("accessToken", grant.accessToken).put("refreshToken", grant.refreshToken)
                .put("expiraEn", java.time.Instant.ofEpochMilli(grant.expiresAt).toString())
                .put("refreshExpiraEn", java.time.Instant.ofEpochMilli(grant.refreshExpiresAt).toString())
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            val encrypted = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
            val value = Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
            if (!preferences.edit().putString("grant", value).commit()) throw AuthProblem(AuthError.STORAGE)
        } catch (_: Exception) {
            throw AuthProblem(AuthError.STORAGE)
        }
    }

    override fun read(): AuthGrant? {
        val value = preferences.getString("grant", null) ?: return null
        try {
            val bytes = Base64.decode(value, Base64.NO_WRAP)
            require(bytes.size > 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            }
            return decodeGrant(JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)))
        } catch (_: Exception) {
            clear()
            throw AuthProblem(AuthError.STORAGE)
        }
    }

    override fun clear() {
        if (!preferences.edit().remove("grant").commit()) throw AuthProblem(AuthError.STORAGE)
    }
}
