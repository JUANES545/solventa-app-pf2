package co.solventa.mobile.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.solventa.mobile.R
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

internal data class BiometricUiState(
    val busy: Boolean = false,
    val localCredential: BiometricCredential? = null,
    val credentials: List<RemoteBiometricCredential> = emptyList(),
    val message: Int? = null,
    val actorUserId: String? = null,
)

@HiltViewModel
internal class BiometricViewModel @Inject constructor(private val repository: BiometricRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(BiometricUiState())
    val state = mutableState.asStateFlow()
    private var operation = 0L
    private var job: Job? = null

    init {
        mutableState.value = try {
            BiometricUiState(localCredential = repository.credential())
        } catch (problem: AuthProblem) {
            BiometricUiState(message = authErrorMessage(problem.reason))
        }
    }

    fun bindActor(userId: String?) {
        if (mutableState.value.actorUserId == userId) return
        operation++
        job?.cancel()
        mutableState.value = try {
            BiometricUiState(localCredential = repository.credential(), actorUserId = userId)
        } catch (problem: AuthProblem) {
            BiometricUiState(actorUserId = userId, message = authErrorMessage(problem.reason))
        }
    }

    fun enroll(password: String, device: BiometricDevice) = perform { actor, token ->
        if (actor == null) throw AuthProblem(AuthError.EXPIRED)
        if (repository.credential() != null) throw BiometricProblem(BiometricFailure.IN_PROGRESS)
        var candidate: BiometricKey? = null
        var published = false
        try {
            val key = withContext(Dispatchers.IO) { device.createKey().also { candidate = it } }
            val pending = repository.beginEnrollment(password, key, actor)
            val signature = device.sign(key.alias, pending.challenge.message)
            repository.confirmEnrollment(pending, signature)
            published = true
            update(token) { it.copy(message = R.string.biometric_enrolled) }
        } finally {
            val key = candidate
            if (!published && key != null) withContext(NonCancellable + Dispatchers.IO) {
                if (repository.credential()?.alias != key.alias) device.deleteKey(key.alias)
            }
        }
    }

    fun login(device: BiometricDevice, onAuthorized: () -> Unit) = perform { actor, token ->
        if (actor != null) throw AuthProblem(AuthError.EXPIRED)
        val pending = repository.loginChallenge()
        val signature = device.sign(pending.credential.alias, pending.challenge.message)
        repository.login(pending, signature)
        ensureCurrent(token)
        onAuthorized()
    }

    fun loadCredentials() = perform { actor, token ->
        if (actor == null) throw AuthProblem(AuthError.EXPIRED)
        val credentials = repository.credentials(actor)
        update(token) { it.copy(credentials = credentials) }
    }

    fun revoke(id: String, device: BiometricDevice) = perform { actor, token ->
        if (actor == null) throw AuthProblem(AuthError.EXPIRED)
        repository.revoke(id, actor)
        ensureCurrent(token)
        val local = repository.credential()
        if (local?.credentialId == id && local.userId == actor) {
            withContext(Dispatchers.IO) { device.deleteKey(local.alias) }
            repository.clearLocalCredential()
        }
        val credentials = repository.credentials(actor)
        update(token) { it.copy(credentials = credentials, message = R.string.biometric_revoked) }
    }

    private fun ensureCurrent(token: Long) {
        if (token != operation) throw CancellationException("Biometric operation superseded")
    }

    private fun update(token: Long, transform: (BiometricUiState) -> BiometricUiState) {
        ensureCurrent(token)
        mutableState.value = transform(mutableState.value)
    }

    private fun perform(action: suspend (String?, Long) -> Unit) {
        if (mutableState.value.busy) return
        val token = ++operation
        val actor = mutableState.value.actorUserId
        mutableState.value = mutableState.value.copy(busy = true, message = null)
        job = viewModelScope.launch {
            try {
                action(actor, token)
                val local = repository.credential()
                update(token) { it.copy(localCredential = local) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (problem: BiometricProblem) {
                if (token == operation) mutableState.value = mutableState.value.copy(message = biometricFailureMessage(problem.reason))
            } catch (error: AuthHttpError) {
                if (token == operation) mutableState.value = mutableState.value.copy(message = when (error.status) {
                    401 -> R.string.biometric_rejected
                    403 -> R.string.biometric_denied
                    409 -> R.string.biometric_duplicate
                    429 -> R.string.auth_rate_limit
                    else -> R.string.auth_unavailable
                })
            } catch (error: AuthProblem) {
                if (token == operation) mutableState.value = mutableState.value.copy(message = authErrorMessage(error.reason))
            } catch (_: org.json.JSONException) {
                if (token == operation) mutableState.value = mutableState.value.copy(message = R.string.auth_unavailable)
            } catch (_: IllegalArgumentException) {
                if (token == operation) mutableState.value = mutableState.value.copy(message = R.string.biometric_invalid_response)
            } catch (_: java.time.DateTimeException) {
                if (token == operation) mutableState.value = mutableState.value.copy(message = R.string.biometric_invalid_response)
            } catch (_: ArithmeticException) {
                if (token == operation) mutableState.value = mutableState.value.copy(message = R.string.biometric_invalid_response)
            } catch (_: java.io.IOException) {
                if (token == operation) mutableState.value = mutableState.value.copy(message = R.string.auth_unavailable)
            } finally {
                if (token == operation) mutableState.value = mutableState.value.copy(busy = false)
            }
        }
    }
}

internal fun biometricFailureMessage(failure: BiometricFailure): Int = when (failure) {
    BiometricFailure.CANCELLED -> R.string.biometric_cancelled
    BiometricFailure.LOCKED_OUT -> R.string.biometric_locked_out
    BiometricFailure.KEY_INVALIDATED, BiometricFailure.KEY_MISSING -> R.string.biometric_key_missing
    BiometricFailure.NO_HARDWARE -> R.string.biometric_no_hardware
    BiometricFailure.NOT_ENROLLED -> R.string.biometric_not_enrolled
    BiometricFailure.SECURITY_UPDATE -> R.string.biometric_security_update
    BiometricFailure.UNSUPPORTED -> R.string.biometric_unsupported
    BiometricFailure.IN_PROGRESS -> R.string.biometric_already_linked
    else -> R.string.biometric_key_unavailable
}
