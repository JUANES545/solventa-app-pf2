package co.solventa.mobile.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class AccessScreen { LOGIN, REGISTER_REFERENCE, REGISTER, REGISTER_PHOTOS, REGISTER_CONFIRM, REGISTERED, RECOVER, RECOVER_CONFIRM, RECOVERED }

data class AuthUiState(
    val user: AuthUser? = null,
    val loading: Boolean = false,
    val error: AuthError? = null,
    val screen: AccessScreen = AccessScreen.LOGIN,
    val email: String = "",
    val offline: Boolean = false,
    val registeredId: String? = null,
)

@HiltViewModel
class AuthViewModel(private val repository: AuthRepository) : ViewModel() {
    private lateinit var recoveryApi: MobileAuthApi
    @Inject constructor(repository: AuthRepository, api: MobileAuthApi) : this(repository) { recoveryApi = api }
    private val mutableState = MutableStateFlow(AuthUiState())
    val state = mutableState.asStateFlow()
    private class RegistrationDraft(val name: String, val email: String, val password: String, val withPhotos: Boolean,
        var profile: RegistrationProfile? = null)
    private var registrationDraft: RegistrationDraft? = null
    private var operation = 0L
    private var operationSessionRevision = -1L

    init {
        viewModelScope.launch {
            repository.session.collectLatest { session ->
                if (session.user != null) {
                    while (repository.session.value == session) {
                        val remaining = repository.remainingSessionMillis(session)
                        if (remaining == 0L) {
                            expireAccess()
                            try {
                                repository.expireSession(session)
                            } catch (problem: AuthProblem) {
                                expireAccess(problem.reason)
                            }
                            break
                        }
                        delay(remaining)
                    }
                } else if (session.expired && repository.session.value == session &&
                    (!mutableState.value.loading || session.revision > operationSessionRevision)) {
                    expireAccess(session.error ?: AuthError.EXPIRED)
                }
            }
        }
    }

    private fun expireAccess(error: AuthError = AuthError.EXPIRED) {
        operation++
        registrationDraft = null
        mutableState.value = AuthUiState(error = error)
    }

    fun login(email: String, password: String) {
        if (mutableState.value.loading) return
        registrationDraft = null
        try {
            validateLogin(email, password)
        } catch (problem: AuthProblem) {
            mutableState.value = AuthUiState(error = problem.reason)
            return
        }
        perform { AuthUiState(user = repository.login(email, password)) }
    }

    fun showRegistration() {
        if (!mutableState.value.loading) {
            registrationDraft = null
            mutableState.value = AuthUiState(screen = AccessScreen.REGISTER)
        }
    }

    fun continueRegistration(termsSelected: Boolean) {
        if (!termsSelected || mutableState.value.screen != AccessScreen.REGISTER_REFERENCE ||
            mutableState.value.loading) return
        val draft = registrationDraft ?: return
        if (draft.withPhotos) {
            mutableState.value = mutableState.value.copy(screen = AccessScreen.REGISTER_PHOTOS)
        } else {
            registrationDraft = null
            mutableState.value = AuthUiState(screen = AccessScreen.REGISTER, email = draft.email)
            register(draft.name, draft.email, draft.password, draft.password)
        }
    }

    fun showLogin() {
        if (!mutableState.value.loading) {
            registrationDraft = null
            mutableState.value = AuthUiState(email = mutableState.value.email)
        }

    }

    fun recover(email: String) {
        if (!mutableState.value.loading) mutableState.value = AuthUiState(screen = AccessScreen.RECOVER, email = email)
    }

    fun requestLink(email: String) {
        if (mutableState.value.screen != AccessScreen.RECOVER) return
        perform {
            validateEmail(email)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { recoveryApi.requestRecovery(email.trim()) }
            AuthUiState(screen = AccessScreen.RECOVER_CONFIRM, email = email.trim())
        }

    }

    fun showLinkForm() {
        if (!mutableState.value.loading && mutableState.value.screen == AccessScreen.RECOVER) {
            mutableState.value = AuthUiState(screen = AccessScreen.RECOVER_CONFIRM, email = mutableState.value.email)
        }
    }

    fun changePassword(link: String, password: String) {
        val current = mutableState.value
        if (current.screen != AccessScreen.RECOVER_CONFIRM) return
        perform {
            val token = recoveryToken(link, co.solventa.mobile.BuildConfig.RECOVERY_LINK_ENTRY)
            if (password.codePointCount(0, password.length) !in 8..128) throw AuthProblem(AuthError.PASSWORD)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { recoveryApi.confirmRecovery(token, password) }
            AuthUiState(screen = AccessScreen.RECOVERED, email = current.email)
        }
    }

    fun register(name: String, email: String, password: String, confirmation: String) {
        if (mutableState.value.loading || mutableState.value.screen != AccessScreen.REGISTER) return
        try {
            validateRegistration(name, email, password, confirmation)
        } catch (problem: AuthProblem) {
            mutableState.value = mutableState.value.copy(error = problem.reason)
            return
        }
        mutableState.value = mutableState.value.copy(email = email.trim())
        perform {
            val registered = repository.register(name, email, password, confirmation)
            AuthUiState(screen = AccessScreen.REGISTERED, email = email.trim(), registeredId = registered.id)
        }
    }

    fun prepareRegistration(name: String, email: String, password: String, confirmation: String, withPhotos: Boolean) {
        if (mutableState.value.loading || mutableState.value.screen != AccessScreen.REGISTER) return
        try {
            validateRegistration(name, email, password, confirmation)
        } catch (problem: AuthProblem) {
            mutableState.value = mutableState.value.copy(error = problem.reason)
            return
        }
        registrationDraft = RegistrationDraft(name.trim(), email.trim(), password, withPhotos)
        mutableState.value = AuthUiState(screen = AccessScreen.REGISTER_REFERENCE, email = email.trim())
    }

    fun completeRegistrationPhotos() {
        if (mutableState.value.screen != AccessScreen.REGISTER_PHOTOS || registrationDraft == null) return
        mutableState.value = mutableState.value.copy(screen = AccessScreen.REGISTER_CONFIRM)
    }

    fun cancelRegistrationPhotos() {
        registrationDraft = null
        val screen = mutableState.value.screen
        if (screen == AccessScreen.REGISTER_REFERENCE || screen == AccessScreen.REGISTER_PHOTOS ||
            screen == AccessScreen.REGISTER_CONFIRM) {
            mutableState.value = AuthUiState(screen = AccessScreen.REGISTER, email = mutableState.value.email)
        }
    }

    fun completeProfile(profile:RegistrationProfile, photos:Boolean) {
        val draft=registrationDraft ?: return
        if (state.value.loading || state.value.screen!=AccessScreen.REGISTER_REFERENCE || !profile.valid()) return
        draft.profile=profile
        if (photos && draft.withPhotos) mutableState.value=state.value.copy(screen=AccessScreen.REGISTER_PHOTOS)
        else submitComplete(draft,profile)
    }

    private fun submitComplete(draft:RegistrationDraft, profile:RegistrationProfile) {
        perform {
            val user=repository.register(draft.name,draft.email,draft.password,draft.password,profile)
            registrationDraft=null
            AuthUiState(screen=AccessScreen.REGISTERED,email=draft.email,registeredId=user.id)
        }
    }

    fun confirmRegistration() {
        if (mutableState.value.screen != AccessScreen.REGISTER_CONFIRM || mutableState.value.loading) return
        val draft = registrationDraft ?: return
        draft.profile?.let { submitComplete(draft,it); return }
        registrationDraft = null
        mutableState.value = AuthUiState(screen = AccessScreen.REGISTER, email = draft.email)
        register(draft.name, draft.email, draft.password, draft.password)
    }

    override fun onCleared() {
        registrationDraft = null
        super.onCleared()
    }

    fun restore() {
        if (mutableState.value.screen != AccessScreen.LOGIN) return
        perform {
            try {
                val user = repository.restore()
                AuthUiState(user = user, email = mutableState.value.email,
                    error = if (user == null && mutableState.value.user != null) AuthError.EXPIRED else null)
            } catch (error: AuthHttpError) {
                if (error.status == 401) throw AuthProblem(AuthError.EXPIRED)
                if (error.status >= 500) return@perform localSession()
                throw error
            } catch (_: java.io.IOException) {
                localSession()
            }
        }
    }

    private suspend fun localSession(): AuthUiState {
        val user = repository.localUser() ?: throw AuthProblem(AuthError.EXPIRED)
        // Local access never bypasses withSession's server verification for protected requests.
        return AuthUiState(user = user, email = mutableState.value.email, offline = true, error = AuthError.UNAVAILABLE)
    }

    fun logout() {
        registrationDraft = null
        perform {
            try {
                repository.logout()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (problem: AuthProblem) {
                throw problem
            } catch (_: Exception) {
                throw AuthProblem(AuthError.LOGOUT_REMOTE)
            }
            AuthUiState()
        }
    }

    private fun perform(action: suspend () -> AuthUiState) {
        if (mutableState.value.loading) return
        mutableState.value = mutableState.value.copy(loading = true, error = null)
        operationSessionRevision = repository.session.value.revision
        val expectedOperation = ++operation
        viewModelScope.launch {
            try {
                val result = action()
                if (operation == expectedOperation) {
                    val session = repository.session.value
                    mutableState.value = if (result.user != null &&
                        (session.user?.id != result.user.id || repository.remainingSessionMillis(session) == 0L)) {
                        AuthUiState(error = AuthError.EXPIRED)
                    } else result
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (operation != expectedOperation) return@launch
                val reason = when (error) {
                    is AuthProblem -> error.reason
                    is AuthHttpError -> when (error.status) {
                        400 -> AuthError.INVALID_REQUEST
                        401 -> AuthError.INVALID_CREDENTIALS
                        409 -> AuthError.EMAIL_EXISTS
                        429 -> AuthError.RATE_LIMIT
                        else -> AuthError.UNAVAILABLE
                    }
                    else -> AuthError.UNAVAILABLE
                }
                mutableState.value = mutableState.value.copy(user = null, loading = false, error = reason)
            }
        }
    }
}
