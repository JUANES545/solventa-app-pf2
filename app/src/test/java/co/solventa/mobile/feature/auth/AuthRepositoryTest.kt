package co.solventa.mobile.feature.auth

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AuthRepositoryTest {
    private val user = AuthUser("11111111-1111-4111-8111-111111111111", "Cliente", "cliente@example.org")
    private val grant = AuthGrant(user, "test-access", "test-refresh", 100_000, 200_000)

    private class MemoryStore(var grant: AuthGrant? = null) : AuthStore {
        override fun read() = grant
        override fun write(grant: AuthGrant) { this.grant = grant }
        override fun clear() { grant = null }
    }

    private inner class TestApi : AuthApi {
        var refreshCount = 0
        var currentError: Int? = null
        var refreshError: Int? = null
        var logoutError: Int? = null
        var loginValues: Pair<String, String>? = null
        var registeredValues: Triple<String, String, String>? = null
        var registrationError: Int? = null
        var revoked: String? = null
        var loginGrant = grant
        override fun register(name: String, email: String, password: String): AuthUser {
            registrationError?.let { throw AuthHttpError(it) }
            registeredValues = Triple(name, email, password)
            return user
        }
        override fun login(email: String, password: String): AuthGrant {
            loginValues = email to password
            return loginGrant
        }
        override fun refresh(token: String): AuthGrant {
            refreshCount++
            refreshError?.let { throw AuthHttpError(it) }
            return grant.copy(accessToken = "rotated-access", refreshToken = "rotated-refresh", expiresAt = 150_000)
        }
        override fun current(token: String): AuthUser {
            currentError?.let { throw AuthHttpError(it) }
            return loginGrant.user
        }
        override fun logout(accessToken: String, refreshToken: String) {
            revoked = refreshToken
            logoutError?.let { throw AuthHttpError(it) }
        }
    }

    @Test fun loginStoresGrantAndNeverTrimsPassword() = runBlocking {
        val api = TestApi()
        val store = MemoryStore()
        assertEquals(user, AuthRepository(api, store) { 0 }.login(" cliente@example.org ", " password "))
        assertEquals("cliente@example.org" to " password ", api.loginValues)
        assertEquals(grant, store.grant)
    }

    @Test fun authorizedManagementRenewsBeforeActionAndDoesNotClearSessionForWrongStepUpPassword() = runBlocking {
        val api = TestApi()
        val store = MemoryStore(grant)
        val repository = AuthRepository(api, store) { 90_000 }
        try {
            repository.withSession { current ->
                assertEquals("rotated-access", current.accessToken)
                throw AuthHttpError(401)
            }
            fail("Expected rejected step-up password")
        } catch (error: AuthHttpError) {
            assertEquals(401, error.status)
        }
        assertEquals(1, api.refreshCount)
        assertEquals("rotated-refresh", store.grant?.refreshToken)
    }

    @Test fun biometricGrantIsStoredOnlyAfterCurrentUserAndLifetimeAreValidated() = runBlocking {
        val api = TestApi()
        val store = MemoryStore()
        val repository = AuthRepository(api, store) { 0 }
        val generation = repository.beginBiometricLogin(user.id)
        for (invalid in listOf(grant.copy(expiresAt = 0),
            grant.copy(refreshExpiresAt = grant.expiresAt),
            grant.copy(user = user.copy(id = "22222222-2222-4222-8222-222222222222")))) {
            try {
                repository.acceptBiometricGrant(invalid, generation)
                fail("Expected invalid biometric grant")
            } catch (_: IllegalArgumentException) { }
            assertNull(store.grant)
        }
        api.currentError = 503
        try {
            repository.acceptBiometricGrant(grant, generation)
            fail("Expected unavailable authoritative session")
        } catch (error: AuthHttpError) {
            assertEquals(503, error.status)
        }
        assertNull(store.grant)
        api.currentError = null
        repository.acceptBiometricGrant(grant, generation)
        assertEquals(grant, store.grant)
    }

    @Test fun logoutRejectsALateBiometricGrantEvenWithoutAnExistingSession() = runBlocking {
        val store = MemoryStore()
        val repository = AuthRepository(TestApi(), store) { 0 }
        val generation = repository.beginBiometricLogin(user.id)
        repository.logout()
        try {
            repository.acceptBiometricGrant(grant, generation)
            fail("A late response must not resurrect the session")
        } catch (error: AuthProblem) { assertEquals(AuthError.EXPIRED, error.reason) }
        assertNull(store.grant)
    }

    @Test fun changingAccountRejectsALateBiometricGrantAndForeignManagement() = runBlocking {
        val api = TestApi()
        val store = MemoryStore()
        val repository = AuthRepository(api, store) { 0 }
        val generation = repository.beginBiometricLogin(user.id)
        val other = grant.copy(user = user.copy(id = "22222222-2222-4222-8222-222222222222"))
        api.loginGrant = other
        repository.login("other@example.org", "password")
        try {
            repository.acceptBiometricGrant(grant, generation)
            fail("A late response must not replace the new account")
        } catch (error: AuthProblem) { assertEquals(AuthError.EXPIRED, error.reason) }
        try {
            repository.withSession(user.id) { fail("Must not execute management for another actor") }
        } catch (error: AuthProblem) { assertEquals(AuthError.EXPIRED, error.reason) }
        assertEquals(other, store.grant)
    }

    @Test fun sameActorRefreshDoesNotInvalidateBiometricContext() = runBlocking {
        val store = MemoryStore(grant)
        val repository = AuthRepository(TestApi(), store) { 90_000 }
        val generation = repository.beginBiometricLogin(user.id)
        repository.restore()
        assertEquals("rotated-access", store.grant?.accessToken)
        repository.acceptBiometricGrant(grant, generation)
        assertEquals(grant, store.grant)
    }

    @Test fun registrationDoesNotCreateOrPersistASession() = runBlocking {
        val api = TestApi()
        val store = MemoryStore()
        assertEquals(user, AuthRepository(api, store).register(" Cliente ", " cliente@example.org ", " password ", " password "))
        assertEquals(Triple("Cliente", "cliente@example.org", " password "), api.registeredValues)
        assertNull(store.grant)
        assertNull(api.loginValues)
    }

    @Test fun successfulRegistrationCannotRestoreAnOlderCachedIdentity() = runBlocking {
        val store = MemoryStore(grant)
        AuthRepository(TestApi(), store).register("Cliente", "cliente@example.org", "password", "password")
        assertNull(store.grant)
    }

    @Test fun rejectedRegistrationDoesNotEraseAnExistingGrant() = runBlocking {
        val api = TestApi().apply { registrationError = 409 }
        val store = MemoryStore(grant)
        try {
            AuthRepository(api, store).register("Cliente", "cliente@example.org", "password", "password")
            fail("Expected duplicate account")
        } catch (error: AuthHttpError) { assertEquals(409, error.status) }
        assertEquals(grant, store.grant)
    }

    @Test fun invalidRegistrationDoesNotCallTheBackend() = runBlocking {
        val api = TestApi()
        for ((name, confirmation) in listOf("" to "password", "x".repeat(121) to "password", "Cliente" to "different")) {
            try {
                AuthRepository(api, MemoryStore()).register(name, "cliente@example.org", "password", confirmation)
                fail("Expected validation error")
            } catch (_: AuthProblem) { }
        }
        assertNull(api.registeredValues)
    }

    @Test fun characterLimitsMatchBackendUnicodeCharacters() {
        validateRegistration("😀".repeat(120), "cliente@example.org", "😀".repeat(8), "😀".repeat(8))
        for ((name, password, expected) in listOf(
            Triple("😀".repeat(121), "password", AuthError.NAME),
            Triple("Cliente", "😀".repeat(7), AuthError.PASSWORD),
            Triple("Cliente", "😀".repeat(129), AuthError.PASSWORD),
        )) {
            try {
                validateRegistration(name, "cliente@example.org", password, password)
                fail("Expected character limit")
            } catch (error: AuthProblem) { assertEquals(expected, error.reason) }
        }
    }

    @Test fun invalidInputDoesNotCallBackend() = runBlocking {
        val api = TestApi()
        for ((email, password) in listOf("" to "", "invalid" to "password", "a@b.co" to "short")) {
            try {
                AuthRepository(api, MemoryStore()).login(email, password)
                fail("Expected validation error")
            } catch (_: AuthProblem) { }
        }
        assertNull(api.loginValues)
    }

    @Test fun absentSessionDoesNotCallRefresh() = runBlocking {
        val api = TestApi()
        assertNull(AuthRepository(api, MemoryStore()) { 0 }.restore())
        assertEquals(0, api.refreshCount)
    }

    @Test fun validAccessIsCheckedWithoutRefresh() = runBlocking {
        val api = TestApi()
        assertEquals(user, AuthRepository(api, MemoryStore(grant)) { 0 }.restore())
        assertEquals(0, api.refreshCount)
    }

    @Test fun expiredAccessRotatesBothTokens() = runBlocking {
        val api = TestApi()
        val store = MemoryStore(grant.copy(expiresAt = 0))
        assertEquals(user, AuthRepository(api, store) { 0 }.restore())
        assertEquals("rotated-refresh", store.grant?.refreshToken)
        assertEquals(1, api.refreshCount)
    }

    @Test fun concurrentRestoreDoesNotReuseSingleUseRefresh() = runBlocking {
        val api = TestApi()
        val repository = AuthRepository(api, MemoryStore(grant.copy(expiresAt = 0))) { 0 }
        val first = async { repository.restore() }
        val second = async { repository.restore() }
        assertEquals(user, first.await())
        assertEquals(user, second.await())
        assertEquals(1, api.refreshCount)
    }

    @Test fun expiredRefreshClearsSession() = runBlocking {
        val store = MemoryStore(grant.copy(refreshExpiresAt = 0))
        assertNull(AuthRepository(TestApi(), store) { 0 }.restore())
        assertNull(store.grant)
    }

    @Test fun rejectedRefreshClearsSession() = runBlocking {
        val api = TestApi().apply { refreshError = 401 }
        val store = MemoryStore(grant.copy(expiresAt = 0))
        try { AuthRepository(api, store) { 0 }.restore(); fail("Expected rejection") }
        catch (error: AuthHttpError) { assertEquals(401, error.status) }
        assertNull(store.grant)
    }

    @Test fun unavailableBackendPreservesEncryptedGrant() = runBlocking {
        val api = TestApi().apply { currentError = 503 }
        val store = MemoryStore(grant)
        try { AuthRepository(api, store) { 0 }.restore(); fail("Expected unavailable service") }
        catch (error: AuthHttpError) { assertEquals(503, error.status) }
        assertEquals(grant, store.grant)
    }

    @Test fun repeatedUnauthorizedIsNotAnInfiniteRetry() = runBlocking {
        val api = TestApi().apply { currentError = 401 }
        val store = MemoryStore(grant)
        try { AuthRepository(api, store) { 0 }.restore(); fail("Expected expired session") }
        catch (_: AuthHttpError) { }
        assertEquals(1, api.refreshCount)
        assertNull(store.grant)
    }

    @Test fun logoutRevokesRefreshAndClearsLocalGrant() = runBlocking {
        val api = TestApi()
        val store = MemoryStore(grant)
        AuthRepository(api, store) { 0 }.logout()
        assertEquals(grant.refreshToken, api.revoked)
        assertNull(store.grant)
    }

    @Test fun logoutClearsLocalGrantEvenWithoutNetwork() = runBlocking {
        val api = TestApi().apply { logoutError = 503 }
        val store = MemoryStore(grant)
        try { AuthRepository(api, store) { 0 }.logout(); fail("Expected remote failure") }
        catch (_: AuthHttpError) { }
        assertNull(store.grant)
    }
}
