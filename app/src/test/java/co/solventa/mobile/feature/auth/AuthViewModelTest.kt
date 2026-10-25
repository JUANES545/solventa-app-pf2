package co.solventa.mobile.feature.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {
    private val user = AuthUser("11111111-1111-4111-8111-111111111111", "Cliente", "cliente@example.org")
    private val grant = AuthGrant(user, "access", "refresh", Long.MAX_VALUE, Long.MAX_VALUE)
    private var stored: AuthGrant? = null
    private var failure: Exception? = null
    private var calls = 0
    private val store = object : AuthStore {
        override fun read() = stored
        override fun write(grant: AuthGrant) { stored = grant }
        override fun clear() { stored = null }
    }
    private val api = object : AuthApi {
        private fun check() { calls++; failure?.let { throw it } }
        override fun register(name: String, email: String, password: String): AuthUser { check(); return user }
        override fun login(email: String, password: String): AuthGrant { check(); return grant }
        override fun refresh(token: String): AuthGrant { check(); return grant }
        override fun current(token: String): AuthUser { check(); return user }
        override fun logout(accessToken: String, refreshToken: String) { check() }
    }
    private fun model() = AuthViewModel(AuthRepository(api, store))

    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun registrationPreservesPublicNavigationAndRequiresExplicitLogin() = runTest {
        val auth = model()
        auth.showRegistration()
        auth.restore()
        assertEquals(0, calls)
        auth.register("Cliente", " cliente@example.org ", "password", "password")
        auth.register("Cliente", user.email, "password", "password")
        auth.login(user.email, "password")
        auth.showLogin()
        auth.showRegistration()
        val result = auth.state.first { !it.loading }
        assertEquals(AccessScreen.REGISTERED, result.screen)
        assertEquals(user.email, result.email)
        assertNull(result.user)
        assertNull(stored)
        assertEquals(1, calls)
        auth.showLogin()
        assertEquals(user.email, auth.state.value.email)
        auth.login(user.email, "password")
        assertEquals(user, auth.state.first { !it.loading }.user)
        auth.logout()
        assertNull(auth.state.first { !it.loading }.user)
        assertNull(stored)
    }

    @Test fun invalidFormsDoNotReachTheBackend() {
        val auth = model()
        auth.login("", "")
        assertEquals(AuthError.REQUIRED, auth.state.value.error)
        auth.showRegistration()
        auth.register("", user.email, "password", "password")
        assertEquals(AuthError.NAME, auth.state.value.error)
        assertEquals(AccessScreen.REGISTER, auth.state.value.screen)
        assertEquals(0, calls)
    }

    @Test fun backendFailuresKeepRegistrationRecoverable() = runTest {
        val errors = mapOf(400 to AuthError.INVALID_REQUEST, 401 to AuthError.INVALID_CREDENTIALS,
            409 to AuthError.EMAIL_EXISTS, 429 to AuthError.RATE_LIMIT, 503 to AuthError.UNAVAILABLE)
        for ((status, reason) in errors) {
            failure = AuthHttpError(status)
            val auth = model()
            auth.showRegistration()
            auth.register("Cliente", user.email, "password", "password")
            val result = auth.state.first { !it.loading }
            assertEquals(reason, result.error)
            assertEquals(AccessScreen.REGISTER, result.screen)
            assertNull(result.user)
        }
        failure = IllegalStateException()
        val auth = model()
        auth.login(user.email, "password")
        assertEquals(AuthError.UNAVAILABLE, auth.state.first { !it.loading }.error)
    }

    @Test fun restoreDistinguishesExpiredRejectedAndValidSessions() = runTest {
        val auth = model()
        auth.restore()
        assertNull(auth.state.first { !it.loading }.error)
        stored = grant
        auth.restore()
        assertEquals(user, auth.state.first { !it.loading }.user)
        stored = grant.copy(refreshExpiresAt = 0)
        auth.restore()
        assertEquals(AuthError.EXPIRED, auth.state.first { !it.loading }.error)
        stored = grant
        failure = AuthHttpError(401)
        auth.restore()
        assertEquals(AuthError.EXPIRED, auth.state.first { !it.loading }.error)
        assertNull(stored)
        stored = grant
        failure = AuthHttpError(503)
        auth.restore()
        assertEquals(AuthError.UNAVAILABLE, auth.state.first { !it.loading }.error)
    }

    @Test fun failedLogoutStillRemovesLocalAccess() = runTest {
        for (problem in listOf(AuthHttpError(503), AuthProblem(AuthError.STORAGE))) {
            stored = grant
            failure = problem
            val auth = model()
            auth.logout()
            val result = auth.state.first { !it.loading }
            assertEquals(if (problem is AuthProblem) AuthError.STORAGE else AuthError.LOGOUT_REMOTE, result.error)
            assertNull(result.user)
            assertNull(stored)
        }
    }

    @Test fun photosAndExplicitConfirmationPrecedeASingleRegistrationWithoutSession() = runTest {
        val auth = model()
        auth.showRegistration()
        auth.prepareRegistration("Cliente", " cliente@example.org ", "password", "password", withPhotos = true)
        auth.prepareRegistration("Otro", user.email, "other-password", "other-password", withPhotos = true)
        auth.register("Cliente", user.email, "password", "password")
        auth.restore()
        assertEquals(AccessScreen.REGISTER_REFERENCE, auth.state.value.screen)
        auth.completeRegistrationPhotos()
        auth.confirmRegistration()
        auth.continueRegistration(false)
        assertEquals(AccessScreen.REGISTER_REFERENCE, auth.state.value.screen)
        assertEquals(0, calls)
        auth.continueRegistration(true)
        assertEquals(AccessScreen.REGISTER_PHOTOS, auth.state.value.screen)
        assertFalse(auth.state.value.toString().contains("password"))
        assertEquals(0, calls)
        auth.confirmRegistration()
        assertEquals(0, calls)
        auth.completeRegistrationPhotos()
        assertEquals(AccessScreen.REGISTER_CONFIRM, auth.state.value.screen)
        assertEquals(0, calls)
        auth.confirmRegistration()
        auth.confirmRegistration()
        val result = auth.state.first { !it.loading }
        assertEquals(AccessScreen.REGISTERED, result.screen)
        assertEquals(user.email, result.email)
        assertNull(result.user)
        assertNull(stored)
        assertEquals(1, calls)
        auth.cancelRegistrationPhotos()
        auth.completeRegistrationPhotos()
        auth.confirmRegistration()
        auth.register("Cliente", user.email, "password", "password")
        auth.restore()
        assertEquals(result, auth.state.value)
        assertEquals(1, calls)
    }

    @Test fun cancellingTermsOrEitherPhotoStageDiscardsTheDraftWithoutSubmitting() {
        for (stage in 0..2) {
            val auth = model()
            auth.showRegistration()
            auth.prepareRegistration("Cliente", user.email, "password", "password", withPhotos = true)
            if (stage > 0) auth.continueRegistration(true)
            if (stage == 2) auth.completeRegistrationPhotos()
            auth.cancelRegistrationPhotos()
            auth.continueRegistration(true)
            auth.completeRegistrationPhotos()
            auth.confirmRegistration()
            assertEquals(AccessScreen.REGISTER, auth.state.value.screen)
            assertFalse(auth.state.value.loading)
            assertNull(auth.state.value.user)
            assertEquals(0, calls)
        }
    }

    @Test fun invalidPhotoRegistrationNeverCreatesADraft() {
        val auth = model()
        auth.showRegistration()
        auth.prepareRegistration("Cliente", user.email, "password", "different", withPhotos = true)
        auth.continueRegistration(true)
        auth.completeRegistrationPhotos()
        auth.confirmRegistration()
        assertEquals(AuthError.PASSWORD_MISMATCH, auth.state.value.error)
        assertEquals(AccessScreen.REGISTER, auth.state.value.screen)
        assertEquals(0, calls)
    }

    @Test fun newModelAndLeavingRegistrationCannotReuseAPhotoDraft() {
        val auth = model()
        auth.showRegistration()
        auth.prepareRegistration("Cliente", user.email, "password", "password", withPhotos = true)
        auth.continueRegistration(true)
        val recreated = model()
        recreated.completeRegistrationPhotos()
        recreated.confirmRegistration()
        assertEquals(AccessScreen.LOGIN, recreated.state.value.screen)
        auth.showLogin()
        auth.completeRegistrationPhotos()
        auth.confirmRegistration()
        assertEquals(AccessScreen.LOGIN, auth.state.value.screen)
        auth.showRegistration()
        auth.continueRegistration(true)
        auth.completeRegistrationPhotos()
        auth.confirmRegistration()
        assertEquals(AccessScreen.REGISTER, auth.state.value.screen)
        assertEquals(0, calls)
    }

    @Test fun failedFinalRegistrationRequiresNewInputAndDoesNotReuseCredentials() = runTest {
        failure = AuthHttpError(503)
        val auth = model()
        auth.showRegistration()
        auth.prepareRegistration("Cliente", user.email, "password", "password", withPhotos = true)
        auth.continueRegistration(true)
        auth.completeRegistrationPhotos()
        auth.confirmRegistration()
        val result = auth.state.first { !it.loading }
        assertEquals(AccessScreen.REGISTER, result.screen)
        assertEquals(AuthError.UNAVAILABLE, result.error)
        assertNull(result.user)
        assertNull(stored)
        auth.confirmRegistration()
        auth.completeRegistrationPhotos()
        auth.confirmRegistration()
        assertEquals(1, calls)
    }

    @Test fun validFormThenTermsPrecedeASingleNormalRegistrationWithoutSession() = runTest {
        val auth = model()
        auth.continueRegistration(true)
        assertEquals(AccessScreen.LOGIN, auth.state.value.screen)
        auth.showRegistration()
        assertEquals(AccessScreen.REGISTER, auth.state.value.screen)
        auth.prepareRegistration("Cliente", user.email, "password", "different", withPhotos = false)
        assertEquals(AuthError.PASSWORD_MISMATCH, auth.state.value.error)
        auth.continueRegistration(true)
        assertEquals(AccessScreen.REGISTER, auth.state.value.screen)
        assertEquals(0, calls)
        auth.prepareRegistration("Cliente", user.email, "password", "password", withPhotos = false)
        auth.restore()
        assertEquals(AccessScreen.REGISTER_REFERENCE, auth.state.value.screen)
        auth.continueRegistration(false)
        assertEquals(AccessScreen.REGISTER_REFERENCE, auth.state.value.screen)
        assertEquals(0, calls)
        assertNull(auth.state.value.user)
        assertNull(stored)
        auth.continueRegistration(true)
        auth.continueRegistration(true)
        val result = auth.state.first { !it.loading }
        assertEquals(AccessScreen.REGISTERED, result.screen)
        assertEquals(user.email, result.email)
        assertNull(result.user)
        assertNull(stored)
        assertEquals(1, calls)
    }
}
