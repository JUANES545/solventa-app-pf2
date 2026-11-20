package co.solventa.mobile.feature.identity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.solventa.mobile.feature.auth.AuthHttpError
import co.solventa.mobile.feature.auth.AuthProblem
import co.solventa.mobile.feature.auth.AuthError
import co.solventa.mobile.feature.evidence.EvidenceError
import co.solventa.mobile.feature.evidence.EvidenceHttpProblem
import co.solventa.mobile.feature.evidence.EvidenceProblem
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class IdentityError { NETWORK, SESSION, DENIED, NOT_FOUND, CONFLICT, UNAVAILABLE, PROTOCOL, STORAGE }
internal data class IdentityState(
    val actor: String? = null,
    val open: Boolean = false,
    val busy: Boolean = false,
    val verification: IdentityVerification? = null,
    val error: IdentityError? = null,
)

@HiltViewModel
internal class IdentityViewModel internal constructor(
    private val api: IdentityApi,
    private val attempts: IdentityAttempts,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    @Inject constructor(api: MobileIdentityApi, attempts: IdentityAttemptStore) : this(api, attempts, Dispatchers.IO)

    private val mutable = MutableStateFlow(IdentityState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var job: Job? = null

    fun bindActor(actor: String?) {
        if (actor != state.value.actor) reset(IdentityState(actor = actor))
    }

    fun show(actor: String) {
        reset(IdentityState(actor = actor, open = true))
        refresh()
    }

    fun close() = reset(IdentityState(actor = state.value.actor))

    fun pause() = reset(state.value.copy(busy = false, verification = null, error = null))

    fun refresh() = perform(submit = false)

    fun submit() {
        if (state.value.error == null && state.value.verification?.canSubmit == true) perform(submit = true)
    }

    private fun perform(submit: Boolean) {
        val current = state.value
        val actor = current.actor ?: return
        if (!current.open || current.busy) return
        val version = generation
        mutable.value = current.copy(busy = true, verification = null, error = null)
        job = viewModelScope.launch {
            try {
                val verification = withContext(dispatcher) {
                    val saved = attempts.read(actor)
                    val initial = current.verification ?: api.start(actor)
                    if (saved != null && saved.verificationId != initial.id) throw IdentityStorageProblem()
                    val pending = saved ?: if (submit) {
                        IdentityAttempt(initial.id, UUID.randomUUID().toString()).also { attempts.save(actor, it) }
                    } else null
                    if (pending != null) {
                        try {
                            api.submit(actor, pending.verificationId, pending.attemptId)
                        } catch (error: EvidenceHttpProblem) {
                            if (error.status == 409 && error.code == "transicion_invalida") {
                                api.current(actor, initial.id)
                                attempts.clear(actor, pending)
                            }
                            throw error
                        }
                    }
                    // A replay returns a historical snapshot, not necessarily the current state.
                    val latest = api.current(actor, initial.id)
                    if (pending != null) attempts.clear(actor, pending)
                    latest
                }
                update(version) { copy(verification = verification) }
            } catch (error: AuthProblem) {
                update(version) { copy(error = when (error.reason) {
                    AuthError.STORAGE -> IdentityError.STORAGE
                    AuthError.UNAVAILABLE -> IdentityError.UNAVAILABLE
                    else -> IdentityError.SESSION
                }) }
            } catch (error: AuthHttpError) {
                update(version) { copy(error = when (error.status) {
                    401 -> IdentityError.SESSION
                    403 -> IdentityError.DENIED
                    503 -> IdentityError.UNAVAILABLE
                    else -> IdentityError.NETWORK
                }) }
            } catch (error: EvidenceProblem) {
                update(version) { copy(error = identityError(error)) }
            } catch (error: IOException) {
                update(version) { copy(error = IdentityError.NETWORK) }
            } catch (error: IdentityStorageProblem) {
                update(version) { copy(error = IdentityError.STORAGE) }
            } finally {
                update(version) { copy(busy = false) }
            }
        }
    }

    private fun reset(next: IdentityState) {
        generation++
        job?.cancel()
        mutable.value = next
    }

    private fun update(version: Long, change: IdentityState.() -> IdentityState) {
        if (version == generation) mutable.value = state.value.change()
    }
}

internal fun identityError(error: EvidenceProblem): IdentityError {
    if (error is EvidenceHttpProblem) return when (error.status) {
        403 -> IdentityError.DENIED
        404 -> IdentityError.NOT_FOUND
        409 -> IdentityError.CONFLICT
        503 -> IdentityError.UNAVAILABLE
        400 -> IdentityError.PROTOCOL
        else -> IdentityError.NETWORK
    }
    return if (error.reason == EvidenceError.NETWORK) IdentityError.NETWORK else IdentityError.PROTOCOL
}
