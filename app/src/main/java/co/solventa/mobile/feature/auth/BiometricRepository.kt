package co.solventa.mobile.feature.auth

import android.content.Context
import co.solventa.mobile.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

internal interface BiometricCredentialStore {
    fun read(): BiometricCredential?
    fun write(credential: BiometricCredential)
    fun clear()
}

@Singleton
internal class LocalBiometricCredentialStore @Inject constructor(@ApplicationContext context: Context) : BiometricCredentialStore {
    private val preferences = context.getSharedPreferences("biometric-credential", Context.MODE_PRIVATE)

    override fun read(): BiometricCredential? {
        val value = preferences.getString("credential", null) ?: return null
        try {
            val json = JSONObject(value)
            return BiometricCredential(canonicalUuid(strictString(json, "id")), strictString(json, "alias"),
                canonicalUuid(strictString(json, "user")), decodeBase64Url(strictString(json, "key"), 91))
        } catch (_: org.json.JSONException) {
            throw AuthProblem(AuthError.STORAGE)
        } catch (_: IllegalArgumentException) {
            throw AuthProblem(AuthError.STORAGE)
        }
    }

    override fun write(credential: BiometricCredential) {
        val json = JSONObject().put("id", credential.credentialId).put("alias", credential.alias)
            .put("user", credential.userId).put("key", base64Url(credential.publicKey))
        if (!preferences.edit().putString("credential", json.toString()).commit()) throw AuthProblem(AuthError.STORAGE)
    }

    override fun clear() {
        if (!preferences.edit().remove("credential").commit()) throw AuthProblem(AuthError.STORAGE)
    }
}

internal data class PendingBiometricEnrollment(val credential: BiometricCredential, val challenge: BiometricChallenge)
internal data class PendingBiometricLogin(val credential: BiometricCredential, val challenge: BiometricChallenge, val sessionGeneration: Long)

@Singleton
internal class BiometricRepository internal constructor(
    private val api: BiometricApi,
    private val auth: AuthRepository,
    private val store: BiometricCredentialStore,
    private val issuer: String,
) {
    @Inject constructor(api: MobileAuthApi, auth: AuthRepository, store: LocalBiometricCredentialStore) :
        this(api, auth, store, BuildConfig.AUTH_ISSUER)

    fun credential() = store.read()

    suspend fun beginEnrollment(password: String, key: BiometricKey, actorUserId: String): PendingBiometricEnrollment {
        val existing = store.read()
        return auth.withSession(actorUserId) { grant ->
            require(existing == null) { "A credential is already linked on this installation." }
            validateLogin(grant.user.email, password)
            val challenge = decodeBiometricChallenge(api.beginEnrollment(grant.accessToken, password, key.publicKeySpki),
                "enrolar", grant.user.id, key.publicKeySpki, issuer)
            PendingBiometricEnrollment(BiometricCredential(challenge.credentialId, key.alias, grant.user.id, key.publicKeySpki), challenge)
        }
    }

    suspend fun confirmEnrollment(pending: PendingBiometricEnrollment, signature: ByteArray) = auth.withSession { grant ->
        require(grant.user.id == pending.credential.userId && pending.challenge.expiresAt > System.currentTimeMillis())
        api.confirmEnrollment(grant.accessToken, pending.challenge, signature)
        try {
            store.write(pending.credential)
        } catch (error: AuthProblem) {
            api.revokeBiometricCredential(grant.accessToken, pending.credential.credentialId)
            throw error
        }
    }

    suspend fun loginChallenge(): PendingBiometricLogin = withContext(Dispatchers.IO) {
        val credential = store.read() ?: throw BiometricProblem(BiometricFailure.KEY_MISSING)
        val generation = auth.beginBiometricLogin(credential.userId)
        val challenge = decodeBiometricChallenge(api.biometricChallenge(credential.credentialId), "login",
            credential.userId, credential.publicKey, issuer, credential.credentialId)
        PendingBiometricLogin(credential, challenge, generation)
    }

    suspend fun login(pending: PendingBiometricLogin, signature: ByteArray) {
        val (credential, challenge, generation) = pending
        val grant = withContext(Dispatchers.IO) {
            require(challenge.expiresAt > System.currentTimeMillis() && challenge.credentialId == credential.credentialId)
            api.biometricLogin(challenge, signature).also { require(it.user.id == credential.userId) }
        }
        auth.acceptBiometricGrant(grant, generation)
    }

    suspend fun credentials(actorUserId: String) = auth.withSession(actorUserId) { api.biometricCredentials(it.accessToken) }

    suspend fun revoke(credentialId: String, actorUserId: String) = auth.withSession(actorUserId) {
        if (api.biometricCredentials(it.accessToken).none { entry -> entry.id == credentialId }) {
            throw AuthProblem(AuthError.INVALID_REQUEST)
        }
        api.revokeBiometricCredential(it.accessToken, canonicalUuid(credentialId))
    }

    fun clearLocalCredential() = store.clear()
}
