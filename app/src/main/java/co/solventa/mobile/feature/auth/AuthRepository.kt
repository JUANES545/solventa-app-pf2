package co.solventa.mobile.feature.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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

internal data class AuthSession(
    val user: AuthUser? = null,
    val generation: Long = 0,
    val revision: Long = 0,
    val refreshExpiresAt: Long = 0,
    val expired: Boolean = false,
    val error: AuthError? = null,
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
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var sessionGeneration = 0L
    private val mutableSession = MutableStateFlow(AuthSession())
    internal val session = mutableSession.asStateFlow()

    private fun publish(grant: AuthGrant) {
        mutableSession.value = AuthSession(grant.user, sessionGeneration,
            mutableSession.value.revision + 1, grant.refreshExpiresAt)
    }

    private fun clearSession(expired: Boolean = false) {
        sessionGeneration++
        var error: AuthError? = null
        try {
            store.clear()
        } catch (problem: AuthProblem) {
            error = problem.reason
            throw problem
        } finally {
            mutableSession.value = AuthSession(generation = sessionGeneration,
                revision = mutableSession.value.revision + 1, expired = expired, error = error)
        }
    }

    private fun requireLive(grant: AuthGrant) {
        if (grant.refreshExpiresAt <= now()) {
            clearSession(expired = true)
            throw AuthProblem(AuthError.EXPIRED)
        }
    }

    internal fun remainingSessionMillis(session: AuthSession): Long =
        (session.refreshExpiresAt - now()).coerceAtLeast(0)

    internal suspend fun expireSession(expected: AuthSession) = withContext(dispatcher) {
        mutex.withLock {
            if (mutableSession.value == expected && remainingSessionMillis(expected) == 0L) {
                clearSession(expired = true)
            }
        }
    }

    suspend fun register(name: String, email: String, password: String, confirmation: String): AuthUser = withContext(dispatcher) {
        validateRegistration(name, email, password, confirmation)
        mutex.withLock {
            val user = api.register(name.trim(), email.trim(), password)
            // A new account must not restore a previously cached identity on the next resume.
            clearSession()
            user
        }
    }

    suspend fun login(email: String, password: String): AuthUser = withContext(dispatcher) {
        validateLogin(email, password)
        mutex.withLock {
            sessionGeneration++
            val grant = api.login(email.trim(), password)
            store.write(grant)
            publish(grant)
            grant.user
        }
    }

    suspend fun restore(): AuthUser? = withContext(dispatcher) {
        mutex.withLock {
            var grant = store.read() ?: return@withLock null
            if (grant.refreshExpiresAt <= now()) {
                clearSession(expired = true)
                return@withLock null
            }
            if (grant.expiresAt <= now() + 30_000) {
                grant = renew(grant)
            }
            val user = currentUser(grant) { grant = it }
            requireLive(grant)
            publish(grant)
            user
        }
    }

    internal suspend fun localUser(): AuthUser? = withContext(dispatcher) {
        mutex.withLock {
            val grant = store.read() ?: return@withLock null
            if (grant.refreshExpiresAt <= now()) {
                clearSession(expired = true)
                return@withLock null
            }
            publish(grant)
            grant.user
        }
    }

    private fun renew(grant: AuthGrant): AuthGrant {
        requireLive(grant)
        val renewed = try {
            api.refresh(grant.refreshToken)
        } catch (error: AuthHttpError) {
            if (error.status == 401) clearSession(expired = true)
            throw error
        }
        // A response arriving after the old deadline cannot resurrect local access.
        requireLive(grant)
        require(renewed.user.id == grant.user.id)
        require(renewed.expiresAt > now() && renewed.refreshExpiresAt > renewed.expiresAt)
        // Persist both rotated tokens together before any further protected request.
        store.write(renewed)
        publish(renewed)
        return renewed
    }

    private fun currentUser(grant: AuthGrant, onRenewed: (AuthGrant) -> Unit): AuthUser {
        var active = grant
        try {
            val user = try {
                api.current(active.accessToken)
            } catch (error: AuthHttpError) {
                if (error.status != 401) throw error
                active = renew(active)
                onRenewed(active)
                api.current(active.accessToken)
            }
            require(user.id == active.user.id)
            return user
        } catch (error: AuthHttpError) {
            if (error.status == 401 && !mutableSession.value.expired) clearSession(expired = true)
            throw error
        }
    }

    internal suspend fun <T> withSession(expectedUserId: String? = null, action: (AuthGrant) -> T): T = withContext(dispatcher) {
        mutex.withLock {
            var grant = store.read() ?: throw AuthProblem(AuthError.EXPIRED)
            if (expectedUserId != null && grant.user.id != expectedUserId) throw AuthProblem(AuthError.EXPIRED)
            requireLive(grant)
            if (grant.expiresAt <= now() + 30_000) grant = renew(grant)
            currentUser(grant) { grant = it }
            requireLive(grant)
            publish(grant)
            // Resource and step-up errors must not invalidate an authenticated session.
            action(grant)
        }
    }

    internal suspend fun refreshSession(expectedUserId: String) = withContext(dispatcher) {
        mutex.withLock {
            val grant = store.read() ?: throw AuthProblem(AuthError.EXPIRED)
            if (grant.user.id != expectedUserId) throw AuthProblem(AuthError.EXPIRED)
            requireLive(grant)
            renew(grant)
            Unit
        }
    }

    internal suspend fun beginBiometricLogin(userId: String): Long = mutex.withLock {
        val current = store.read()
        if (current != null && current.user.id != userId) throw AuthProblem(AuthError.EXPIRED)
        sessionGeneration
    }

    internal suspend fun acceptBiometricGrant(grant: AuthGrant, expectedGeneration: Long) = withContext(dispatcher) {
        mutex.withLock {
            if (sessionGeneration != expectedGeneration) throw AuthProblem(AuthError.EXPIRED)
            require(grant.expiresAt > now() && grant.refreshExpiresAt > grant.expiresAt)
            require(api.current(grant.accessToken).id == grant.user.id)
            store.write(grant)
            sessionGeneration++
            publish(grant)
        }
    }

    suspend fun logout() = withContext(dispatcher) {
        mutex.withLock {
            sessionGeneration++
            val grant = store.read()
            try {
                if (grant != null) {
                    val active = if (grant.expiresAt <= now() + 30_000) renew(grant) else grant
                    api.logout(active.accessToken, active.refreshToken)
                }
            } finally {
                clearSession()
            }
        }
    }
}
