package co.solventa.mobile.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AuthUiState(val user: AuthUser? = null, val loading: Boolean = false, val error: AuthError? = null)

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
        perform { repository.login(email, password) }
    }
    fun restore() = perform { repository.restore() }
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
        null
    }

    private fun perform(action: suspend () -> AuthUser?) {
        if (mutableState.value.loading) return
        mutableState.value = mutableState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                mutableState.value = AuthUiState(user = action())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val reason = when (error) {
                    is AuthProblem -> error.reason
                    is AuthHttpError -> when (error.status) {
                        401 -> AuthError.INVALID_CREDENTIALS
                        429 -> AuthError.RATE_LIMIT
                        else -> AuthError.UNAVAILABLE
                    }
                    else -> AuthError.UNAVAILABLE
                }
                mutableState.value = AuthUiState(error = reason)
            }
        }
    }
}
