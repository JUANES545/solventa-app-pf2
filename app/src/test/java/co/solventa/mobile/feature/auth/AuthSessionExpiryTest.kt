package co.solventa.mobile.feature.auth

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthSessionExpiryTest {
    private val models = mutableListOf<AuthViewModel>()
    private fun model(repository: AuthRepository) = AuthViewModel(repository).also { models += it }
    private fun sessionTest(block: suspend TestScope.() -> Unit) = runTest {
        try {
            block()
        } finally {
            models.forEach { it.viewModelScope.cancel() }
        }
    }
    private val user = AuthUser("actor-a", "Cliente", "cliente@example.org")
    private var grant = AuthGrant(user, "access", "refresh", 60_000, 100_000)
    private var stored: AuthGrant? = null
    private var clears = 0
    private var clearFails = false
    private var refreshCalls = 0
    private var logoutCalls = 0
    private var refreshError: Int? = null
    private var currentError: Int? = null
    private var logoutError: Int? = null
    private var onRefresh: () -> Unit = {}
    private var onCurrent: () -> Unit = {}
    private val store = object : AuthStore {
        override fun read() = stored
        override fun write(grant: AuthGrant) { stored = grant }
        override fun clear() {
            clears++
            if (clearFails) throw AuthProblem(AuthError.STORAGE)
            stored = null
        }
    }
    private val api = object : AuthApi {
        override fun register(name: String, email: String, password: String) = user
        override fun login(email: String, password: String) = grant
        override fun refresh(token: String): AuthGrant {
            refreshCalls++
            refreshError?.let { throw AuthHttpError(it) }
            onRefresh()
            return grant
        }
        override fun current(token: String): AuthUser {
            currentError?.let { throw AuthHttpError(it) }
            onCurrent()
            return grant.user
        }
        override fun logout(accessToken: String, refreshToken: String) {
            logoutCalls++
            logoutError?.let { throw AuthHttpError(it) }
        }
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun foregroundDeadlineRemovesActorWithoutRestoreOrRemoteLogout() = sessionTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val repository = AuthRepository(api, store, dispatcher) { testScheduler.currentTime }
        val auth = model(repository)
        auth.login(user.email, "password")
        runCurrent()
        assertEquals(user, auth.state.value.user)
        advanceTimeBy(99_999)
        runCurrent()
        assertEquals(user, auth.state.value.user)
        advanceTimeBy(1)
        runCurrent()
        assertNull(auth.state.value.user)
        assertEquals(AccessScreen.LOGIN, auth.state.value.screen)
        assertEquals(AuthError.EXPIRED, auth.state.value.error)
        assertNull(stored)
        assertEquals(1, clears)
        assertEquals(0, refreshCalls)
        assertEquals(0, logoutCalls)
    }

    @Test fun rotationAndAccountChangeCancelOldDeadlines() = sessionTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val repository = AuthRepository(api, store, dispatcher) { testScheduler.currentTime }
        val auth = model(repository)
        auth.login(user.email, "password")
        runCurrent()
        val old = repository.session.value
        advanceTimeBy(40_000)
        grant = grant.copy(accessToken = "rotated", expiresAt = 160_000, refreshExpiresAt = 200_000)
        repository.withSession(user.id) { assertEquals("rotated", it.accessToken) }
        runCurrent()
        advanceTimeBy(60_000)
        repository.expireSession(old)
        runCurrent()
        assertEquals(user, auth.state.value.user)
        assertNotNull(stored)
        grant = grant.copy(user = user.copy(id = "actor-b"), expiresAt = 250_000, refreshExpiresAt = 300_000)
        repository.login(user.email, "password")
        auth.restore()
        runCurrent()
        advanceTimeBy(100_000)
        runCurrent()
        assertEquals("actor-b", auth.state.value.user?.id)
        assertEquals(0, clears)
        advanceTimeBy(100_000)
        runCurrent()
        assertNull(auth.state.value.user)
    }

    @Test fun concurrentRejectedRefreshNotifiesAuthOnceAndRejectsLateBiometrics() = sessionTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val repository = AuthRepository(api, store, dispatcher) { testScheduler.currentTime }
        val auth = model(repository)
        auth.login(user.email, "password")
        runCurrent()
        val generation = repository.beginBiometricLogin(user.id)
        advanceTimeBy(40_000)
        refreshError = 401
        val requests = List(2) {
            async {
                try {
                    repository.withSession(user.id) { fail("Expired request must not execute") }
                    fail("Expected expired session")
                } catch (_: AuthHttpError) {
                } catch (error: AuthProblem) {
                    assertEquals(AuthError.EXPIRED, error.reason)
                }
            }
        }
        requests.forEach { it.await() }
        runCurrent()
        assertNull(auth.state.value.user)
        assertEquals(AuthError.EXPIRED, auth.state.value.error)
        assertEquals(1, clears)
        assertEquals(1, refreshCalls)
        try {
            repository.acceptBiometricGrant(grant, generation)
            fail("Late response must not restore access")
        } catch (error: AuthProblem) {
            assertEquals(AuthError.EXPIRED, error.reason)
        }
        assertNull(stored)
    }

    @Test fun resourceErrorsAndUnavailableBackendPreserveAnUnexpiredSession() = sessionTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val repository = AuthRepository(api, store, dispatcher) { testScheduler.currentTime }
        val auth = model(repository)
        auth.login(user.email, "password")
        runCurrent()
        for (status in listOf(401, 403, 404)) {
            try {
                repository.withSession(user.id) { throw AuthHttpError(status) }
                fail("Expected resource/step-up error")
            } catch (error: AuthHttpError) {
                assertEquals(status, error.status)
            }
        }
        currentError = 503
        auth.restore()
        runCurrent()
        assertEquals(user, auth.state.value.user)
        assertTrue(auth.state.value.offline)
        assertEquals(0, clears)
        advanceTimeBy(100_000)
        runCurrent()
        assertNull(auth.state.value.user)
        assertNull(stored)
    }

    @Test fun explicitRefreshAndAuthoritativeRejectionInvalidateButFailedLogoutStillClears() = sessionTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val repository = AuthRepository(api, store, dispatcher) { testScheduler.currentTime }
        val auth = model(repository)
        auth.login(user.email, "password")
        runCurrent()
        refreshError = 401
        try { repository.refreshSession(user.id); fail() } catch (_: AuthHttpError) { }
        runCurrent()
        assertEquals(AuthError.EXPIRED, auth.state.value.error)
        refreshError = null
        auth.login(user.email, "password")
        runCurrent()
        currentError = 401
        try { repository.withSession(user.id) { fail() }; fail() } catch (_: AuthHttpError) { }
        runCurrent()
        assertNull(auth.state.value.user)
        assertNull(stored)
        currentError = null
        auth.login(user.email, "password")
        runCurrent()
        logoutError = 503
        auth.logout()
        runCurrent()
        assertNull(auth.state.value.user)
        assertEquals(AuthError.LOGOUT_REMOTE, auth.state.value.error)
        assertNull(stored)
    }

    @Test fun lateCurrentAndRefreshResponsesCannotRestoreAnExpiredActor() = sessionTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        var clock = 0L
        val repository = AuthRepository(api, store, dispatcher) { clock }
        val auth = model(repository)
        auth.login(user.email, "password")
        runCurrent()
        onCurrent = { clock = 100_000 }
        auth.restore()
        runCurrent()
        assertNull(auth.state.value.user)
        assertEquals(AuthError.EXPIRED, auth.state.value.error)
        assertNull(stored)
        onCurrent = {}
        clock = 0
        auth.login(user.email, "password")
        runCurrent()
        val old = repository.session.value
        grant = grant.copy(expiresAt = 200_000, refreshExpiresAt = 300_000)
        onRefresh = { clock = 100_000 }
        try { repository.refreshSession(user.id); fail() } catch (error: AuthProblem) {
            assertEquals(AuthError.EXPIRED, error.reason)
        }
        runCurrent()
        assertNull(stored)
        assertNull(auth.state.value.user)
        onRefresh = {}
        auth.login(user.email, "password")
        runCurrent()
        repository.expireSession(old)
        runCurrent()
        assertEquals(user, auth.state.value.user)
        assertEquals(grant, stored)
    }

    @Test fun unavailableRefreshAndForeignActorDoNotEraseValidGrant() = sessionTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val repository = AuthRepository(api, store, dispatcher) { testScheduler.currentTime }
        val auth = model(repository)
        auth.login(user.email, "password")
        runCurrent()
        refreshError = 503
        try { repository.refreshSession(user.id); fail() } catch (_: AuthHttpError) { }
        try { repository.withSession("actor-b") { fail() }; fail() } catch (_: AuthProblem) { }
        runCurrent()
        assertEquals(user, auth.state.value.user)
        assertEquals(grant, stored)
        assertEquals(0, clears)
    }

    @Test fun failedDeadlineCleanupStillRemovesAccessAndReportsStorageFailure() = sessionTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val repository = AuthRepository(api, store, dispatcher) { testScheduler.currentTime }
        val auth = model(repository)
        auth.login(user.email, "password")
        runCurrent()
        clearFails = true
        advanceTimeBy(100_000)
        runCurrent()
        assertNull(auth.state.value.user)
        assertEquals(AuthError.STORAGE, auth.state.value.error)
        assertTrue(repository.session.value.expired)
        assertEquals(1, clears)
    }
}
