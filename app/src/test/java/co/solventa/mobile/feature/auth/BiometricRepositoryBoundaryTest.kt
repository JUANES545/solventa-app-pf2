package co.solventa.mobile.feature.auth

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class BiometricRepositoryBoundaryTest {
    private val id = "11111111-1111-4111-8111-111111111111"
    private val user = AuthUser("22222222-2222-4222-8222-222222222222", "Cliente", "cliente@example.org")
    private val credential = BiometricCredential(id, "synthetic-key", user.id, byteArrayOf(1, 2, 3))
    private val grant = AuthGrant(user, "synthetic-access", "synthetic-refresh", Long.MAX_VALUE, Long.MAX_VALUE)

    @Test fun publicCredentialRoundTripsAndCorruptionRemainsAnExplicitStorageError() {
        val context = RuntimeEnvironment.getApplication()
        val store = LocalBiometricCredentialStore(context)
        assertNull(store.read())
        store.write(credential)
        val restored = requireNotNull(store.read())
        assertEquals(credential.credentialId, restored.credentialId)
        assertEquals(credential.userId, restored.userId)
        assertEquals(credential.alias, restored.alias)
        assertArrayEquals(credential.publicKey, restored.publicKey)
        val preferences = context.getSharedPreferences("biometric-credential", Context.MODE_PRIVATE)
        val valid = JSONObject(requireNotNull(preferences.getString("credential", null)))
        for (invalid in listOf("not-json", JSONObject(valid.toString()).put("id", "invalid").toString())) {
            preferences.edit().putString("credential", invalid).commit()
            assertEquals(AuthError.STORAGE, assertThrows(AuthProblem::class.java) { store.read() }.reason)
        }
        store.clear()
        assertNull(store.read())
    }

    @Test fun localCredentialCommitFailuresAreNotIgnored() {
        val context = mock(Context::class.java)
        val preferences = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java, RETURNS_SELF)
        `when`(context.getSharedPreferences("biometric-credential", Context.MODE_PRIVATE)).thenReturn(preferences)
        `when`(preferences.edit()).thenReturn(editor)
        val store = LocalBiometricCredentialStore(context)
        assertEquals(AuthError.STORAGE, assertThrows(AuthProblem::class.java) { store.write(credential) }.reason)
        assertEquals(AuthError.STORAGE, assertThrows(AuthProblem::class.java) { store.clear() }.reason)
    }

    @Test fun expiredOrMismatchedPendingOperationsNeverReachConfirmationOrLogin() = runBlocking {
        val store = object : BiometricCredentialStore {
            override fun read(): BiometricCredential? = null
            override fun write(credential: BiometricCredential) = error("Unexpected write")
            override fun clear() = Unit
        }
        val api = mock(BiometricApi::class.java)
        val authStore = object : AuthStore {
            override fun read() = grant
            override fun write(grant: AuthGrant) = error("Unexpected grant")
            override fun clear() = Unit
        }
        val authApi = mock(AuthApi::class.java)
        `when`(authApi.current(grant.accessToken)).thenReturn(user)
        val repository = BiometricRepository(api, AuthRepository(authApi, authStore), store,
            "https://identidad.solventa.test")
        val expired = BiometricChallenge(id, base64Url(ByteArray(32)), byteArrayOf(1), 0)
        val valid = expired.copy(expiresAt = Long.MAX_VALUE)
        for (pending in listOf(
            PendingBiometricEnrollment(credential, expired),
            PendingBiometricEnrollment(credential.copy(userId = id), valid),
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repository.confirmEnrollment(pending, byteArrayOf(1)) }
            }
        }
        for (challenge in listOf(expired, valid.copy(credentialId = user.id))) {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repository.login(PendingBiometricLogin(credential, challenge, 0), byteArrayOf(1)) }
            }
        }
        assertEquals(BiometricFailure.KEY_MISSING,
            assertThrows(BiometricProblem::class.java) { runBlocking { repository.loginChallenge() } }.reason)
        verifyNoInteractions(api)
    }
}
