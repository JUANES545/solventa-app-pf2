package co.solventa.mobile.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class AccessScreen { LOGIN, REGISTER, REGISTERED }

data class AuthUiState(
    val user: AuthUser? = null,
    val loading: Boolean = false,
    val error: AuthError? = null,
    val screen: AccessScreen = AccessScreen.LOGIN,
    val email: String = "",
)

@HiltViewModel
class AuthViewModel @Inject constructor(private val repository: AuthRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(AuthUiState())
    val state = mutableState.asStateFlow()

    fun login(email: String, password: String) {
        if (mutableState.value.loading) return
        try {
            validateLogin(email, password)
        } catch (problem: AuthProblem) {
            mutableState.value = AuthUiState(error = problem.reason)
            return
        }
        perform { AuthUiState(user = repository.login(email, password)) }
    }

    fun showRegistration() {
        if (!mutableState.value.loading) mutableState.value = AuthUiState(screen = AccessScreen.REGISTER)
    }

    fun showLogin() {
        if (!mutableState.value.loading) mutableState.value = AuthUiState(email = mutableState.value.email)
    }

    fun register(name: String, email: String, password: String, confirmation: String) {
        if (mutableState.value.loading) return
        try {
            validateRegistration(name, email, password, confirmation)
        } catch (problem: AuthProblem) {
            mutableState.value = mutableState.value.copy(error = problem.reason)
            return
        }
        mutableState.value = mutableState.value.copy(email = email.trim())
        perform {
            repository.register(name, email, password, confirmation)
            AuthUiState(screen = AccessScreen.REGISTERED, email = email.trim())
        }
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
                throw error
            }
        }
    }
    fun logout() = perform {
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

    private fun perform(action: suspend () -> AuthUiState) {
        if (mutableState.value.loading) return
        mutableState.value = mutableState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                mutableState.value = action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
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
