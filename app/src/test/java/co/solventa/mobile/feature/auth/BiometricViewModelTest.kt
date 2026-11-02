package co.solventa.mobile.feature.auth

import co.solventa.mobile.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*

@OptIn(ExperimentalCoroutinesApi::class)
class BiometricViewModelTest {
    private val repository = mock(BiometricRepository::class.java)
    private val device = mock(BiometricDevice::class.java)
    private val user = "22222222-2222-4222-8222-222222222222"
    private val credential = BiometricCredential("11111111-1111-4111-8111-111111111111",
        "synthetic", user, byteArrayOf(1))

    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun missingActorRejectsManagementAndAuthenticatedActorCannotUsePublicLogin() = runTest {
        val model = BiometricViewModel(repository)
        for (action in listOf<() -> Unit>(
            { model.enroll("password", device) }, { model.loadCredentials() },
            { model.revoke(credential.credentialId, device) },
        )) {
            action()
            advanceUntilIdle()
            assertEquals(authErrorMessage(AuthError.EXPIRED), model.state.value.message)
            assertFalse(model.state.value.busy)
        }
        model.bindActor(user)
        model.login(device) { fail("Unexpected authorization") }
        advanceUntilIdle()
        assertEquals(authErrorMessage(AuthError.EXPIRED), model.state.value.message)
        verifyNoInteractions(device)
    }

    @Test fun corruptStoredCredentialIsVisibleOnInitializationAndActorChange() {
        doAnswer { throw AuthProblem(AuthError.STORAGE) }.`when`(repository).credential()
        val model = BiometricViewModel(repository)
        assertEquals(authErrorMessage(AuthError.STORAGE), model.state.value.message)
        model.bindActor(user)
        assertEquals(user, model.state.value.actorUserId)
        assertEquals(authErrorMessage(AuthError.STORAGE), model.state.value.message)
    }

    @Test fun existingLocalCredentialPreventsCreatingAnotherKey() = runTest {
        `when`(repository.credential()).thenReturn(credential)
        val model = BiometricViewModel(repository)
        model.bindActor(user)
        model.enroll("password", device)
        advanceUntilIdle()
        assertEquals(R.string.biometric_already_linked, model.state.value.message)
        verifyNoInteractions(device)
    }

    @Test fun failuresKeepMessagesSpecificAndReleaseTheBusyState() = runTest {
        val errors = listOf(
            AuthHttpError(401) to R.string.biometric_rejected,
            AuthHttpError(403) to R.string.biometric_denied,
            AuthHttpError(409) to R.string.biometric_duplicate,
            AuthHttpError(429) to R.string.auth_rate_limit,
            AuthHttpError(503) to R.string.auth_unavailable,
            AuthProblem(AuthError.STORAGE) to authErrorMessage(AuthError.STORAGE),
            org.json.JSONException("invalid") to R.string.auth_unavailable,
            IllegalArgumentException("invalid") to R.string.biometric_invalid_response,
            java.time.DateTimeException("invalid") to R.string.biometric_invalid_response,
            ArithmeticException("invalid") to R.string.biometric_invalid_response,
            java.io.IOException("unavailable") to R.string.auth_unavailable,
        )
        for ((failure, message) in errors) {
            doAnswer { throw failure }.`when`(repository).credentials(user)
            val model = BiometricViewModel(repository)
            model.bindActor(user)
            model.loadCredentials()
            assertTrue(model.state.value.busy)
            advanceUntilIdle()
            assertFalse(model.state.value.busy)
            assertEquals(message, model.state.value.message)
            assertTrue(model.state.value.credentials.isEmpty())
        }
    }

    @Test fun concurrentLoadsRunOnceAndRevokingAnotherDevicePreservesLocalKey() = runTest {
        `when`(repository.credential()).thenReturn(credential)
        val remote = RemoteBiometricCredential("33333333-3333-4333-8333-333333333333", "2026-11-01T00:00:00Z", true)
        `when`(repository.credentials(user)).thenReturn(listOf(remote))
        val model = BiometricViewModel(repository)
        model.bindActor(user)
        model.loadCredentials()
        model.loadCredentials()
        advanceUntilIdle()
        verify(repository).credentials(user)
        assertEquals(listOf(remote), model.state.value.credentials)
        model.revoke(remote.id, device)
        advanceUntilIdle()
        verify(repository).revoke(remote.id, user)
        verifyNoInteractions(device)
        verify(repository, never()).clearLocalCredential()
        assertEquals(R.string.biometric_revoked, model.state.value.message)
        `when`(repository.credential()).thenReturn(null)
        model.revoke(remote.id, device)
        advanceUntilIdle()
        assertEquals(R.string.biometric_revoked, model.state.value.message)
        `when`(repository.credential()).thenReturn(credential.copy(userId = "another-actor"))
        model.revoke(credential.credentialId, device)
        advanceUntilIdle()
        verifyNoInteractions(device)
        verify(repository, never()).clearLocalCredential()
    }

    @Test fun allDeviceFailuresHaveRecoverableMessages() {
        val expected = mapOf(
            BiometricFailure.CANCELLED to R.string.biometric_cancelled,
            BiometricFailure.LOCKED_OUT to R.string.biometric_locked_out,
            BiometricFailure.KEY_MISSING to R.string.biometric_key_missing,
            BiometricFailure.KEY_INVALIDATED to R.string.biometric_key_missing,
            BiometricFailure.NO_HARDWARE to R.string.biometric_no_hardware,
            BiometricFailure.NOT_ENROLLED to R.string.biometric_not_enrolled,
            BiometricFailure.SECURITY_UPDATE to R.string.biometric_security_update,
            BiometricFailure.UNSUPPORTED to R.string.biometric_unsupported,
            BiometricFailure.IN_PROGRESS to R.string.biometric_already_linked,
        )
        for (failure in BiometricFailure.entries) {
            assertEquals(expected[failure] ?: R.string.biometric_key_unavailable, biometricFailureMessage(failure))
        }
    }
}
