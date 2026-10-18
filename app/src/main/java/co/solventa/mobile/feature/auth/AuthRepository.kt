package co.solventa.mobile.feature.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class AuthUser(val id: String, val name: String, val email: String)
data class AuthGrant(
    val user: AuthUser,
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val refreshExpiresAt: Long,
)

enum class AuthError { REQUIRED, NAME, EMAIL, PASSWORD, PASSWORD_MISMATCH, EMAIL_EXISTS, INVALID_REQUEST, INVALID_CREDENTIALS, EXPIRED, RATE_LIMIT, UNAVAILABLE, STORAGE, LOGOUT_REMOTE }
class AuthProblem(val reason: AuthError) : Exception()
class AuthHttpError(val status: Int) : Exception()

interface AuthApi {
    fun register(name: String, email: String, password: String): AuthUser
    fun login(email: String, password: String): AuthGrant
    fun refresh(token: String): AuthGrant
    fun current(token: String): AuthUser
    fun logout(accessToken: String, refreshToken: String)
}

interface AuthStore {
    fun read(): AuthGrant?
    fun write(grant: AuthGrant)
    fun clear()
}

fun validateLogin(email: String, password: String) {
    if (email.isBlank() || password.isBlank()) throw AuthProblem(AuthError.REQUIRED)
    if (email.trim().length > 254 || !email.trim().matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))) {
        throw AuthProblem(AuthError.EMAIL)
    }
    if (password.codePointCount(0, password.length) !in 8..128) throw AuthProblem(AuthError.PASSWORD)
}

fun validateRegistration(name: String, email: String, password: String, confirmation: String) {
    val trimmedName = name.trim()
    if (trimmedName.codePointCount(0, trimmedName.length) !in 1..120) throw AuthProblem(AuthError.NAME)
    validateLogin(email, password)
    if (password != confirmation) throw AuthProblem(AuthError.PASSWORD_MISMATCH)
}

class AuthRepository(
    private val api: AuthApi,
    private val store: AuthStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    suspend fun register(name: String, email: String, password: String, confirmation: String): AuthUser = withContext(Dispatchers.IO) {
        validateRegistration(name, email, password, confirmation)
        mutex.withLock {
            val user = api.register(name.trim(), email.trim(), password)
            // A new account must not restore a previously cached identity on the next resume.
            store.clear()
            user
        }
    }

    suspend fun login(email: String, password: String): AuthUser = withContext(Dispatchers.IO) {
        validateLogin(email, password)
        mutex.withLock {
            val grant = api.login(email.trim(), password)
            store.write(grant)
            grant.user
        }
    }

    suspend fun restore(): AuthUser? = withContext(Dispatchers.IO) {
        mutex.withLock {
            var grant = store.read() ?: return@withLock null
            if (grant.refreshExpiresAt <= now()) {
                store.clear()
                return@withLock null
            }
            try {
                if (grant.expiresAt <= now() + 30_000) {
                    grant = renew(grant)
                }
                try {
                    api.current(grant.accessToken)
                } catch (error: AuthHttpError) {
                    if (error.status != 401) throw error
                    grant = renew(grant)
                    api.current(grant.accessToken)
                }
            } catch (error: AuthHttpError) {
                if (error.status == 401) store.clear()
                throw error
            }
        }
    }

    private fun renew(grant: AuthGrant): AuthGrant {
        val renewed = api.refresh(grant.refreshToken)
        // Persist both rotated tokens together before any further protected request.
        store.write(renewed)
        return renewed
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val grant = store.read()
            try {
                if (grant != null) {
                    val active = if (grant.expiresAt <= now() + 30_000) renew(grant) else grant
                    api.logout(active.accessToken, active.refreshToken)
                }
            } finally {
                store.clear()
            }
        }
    }
}
