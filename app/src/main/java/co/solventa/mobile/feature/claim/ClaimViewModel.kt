package co.solventa.mobile.feature.claim

import android.app.Application
import android.database.sqlite.SQLiteException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import co.solventa.mobile.feature.auth.AuthError
import co.solventa.mobile.feature.auth.AuthHttpError
import co.solventa.mobile.feature.auth.AuthProblem
import co.solventa.mobile.feature.evidence.EvidenceError
import co.solventa.mobile.feature.evidence.EvidenceHttpProblem
import co.solventa.mobile.feature.evidence.EvidenceProblem
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import java.time.format.DateTimeParseException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class ClaimState(
    val actor: String? = null,
    val open: Boolean = false,
    val busy: Boolean = false,
    val loadingPolicies: Boolean = false,
    val editing: Boolean = true,
    val draft: ClaimDraft = ClaimDraft(),
    val policies: List<ClaimPolicy> = emptyList(),
    val cachedPolicies: Boolean = true,
    val reports: List<ClaimReport> = emptyList(),
    val error: ClaimError? = null,
    val correction: String? = null,
)

@HiltViewModel
internal class ClaimViewModel @Inject constructor(
    application: Application,
    private val api: MobileClaimApi,
) : AndroidViewModel(application) {
    private val storage = lazy { ClaimStore(application.noBackupFilesDir) }
    private val store by storage
    private val mutable = MutableStateFlow(ClaimState())
    val state = mutable.asStateFlow()
    @Volatile private var generation = 0L
    private var job: Job? = null

    fun bindActor(actor: String?) {
        if (actor == state.value.actor) return
        generation++
        job?.cancel()
        mutable.value = ClaimState(actor = actor)
    }

    fun show(actor: String) {
        bindActor(actor)
        mutable.value = state.value.copy(open = true)
        retry()
    }

    fun close() {
        mutable.value = state.value.copy(open = false)
    }

    fun edit(draft: ClaimDraft) {
        if (!state.value.busy && state.value.editing) mutable.value = state.value.copy(draft = draft, error = null)
    }

    fun newReport() {
        if (!state.value.busy) mutable.value = state.value.copy(editing = true, draft = ClaimDraft(), error = null, correction = null)
    }

    fun correct(report: ClaimReport) {
        if (state.value.busy || report !in state.value.reports || !report.correctable) return
        mutable.value = state.value.copy(editing = true, draft = report.payload.draft(), correction = report.operation, error = null)
    }

    fun submit() {
        val current = state.value
        if (current.busy || !current.editing || current.actor == null) return
        val payload = try { current.draft.payload(current.policies) }
        catch (error: ClaimProblem) { mutable.value = current.copy(error = error.reason); return }
        operation { actor ->
            store.enqueue(actor, payload, current.correction)
            update { copy(editing = false, draft = ClaimDraft(), correction = null, reports = store.list(actor)) }
            sendPending(actor)
        }
    }

    fun retry() {
        if (!state.value.open) return
        operation { actor ->
            update { copy(reports = store.list(actor), policies = store.policies(actor), cachedPolicies = true) }
            if (!sendPending(actor)) return@operation
            update { copy(loadingPolicies = true) }
            try {
                val policies = api.policies(actor)
                store.savePolicies(actor, policies)
                update { copy(policies = policies, cachedPolicies = false) }
            } catch (error: Exception) {
                val reason = claimError(error)
                update { copy(error = reason, cachedPolicies = true) }
            }
        }
    }

    private fun operation(block: suspend Operation.(String) -> Unit) {
        val current = state.value
        val actor = current.actor ?: return
        if (current.busy) return
        val operation = Operation(generation)
        mutable.value = current.copy(busy = true, error = null)
        job = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { operation.block(actor) }
            } catch (error: Exception) {
                val reason = claimError(error)
                operation.update { copy(error = reason) }
            } finally { operation.update { copy(busy = false, loadingPolicies = false) } }
        }
    }

    private inner class Operation(private val version: Long) {
        suspend fun sendPending(actor: String): Boolean {
            for (report in store.list(actor).filter(ClaimReport::retryable)) {
                val error = send(report)
                if (error != null && error !in setOf(ClaimError.POLICY, ClaimError.DATE, ClaimError.CONFLICT)) return false
            }
            return true
        }

        fun update(change: ClaimState.() -> ClaimState) {
            while (true) {
                val current = mutable.value
                if (version != generation) return
                if (mutable.compareAndSet(current, current.change())) return
            }
        }

        suspend fun send(report: ClaimReport): ClaimError? {
            currentCoroutineContext().ensureActive()
            try {
                val receipt = api.send(report)
                store.confirm(report, receipt)
                return null
            } catch (error: Exception) {
                val reason = claimError(error)
                store.fail(report, reason, (error as? EvidenceHttpProblem)?.code, (error as? EvidenceHttpProblem)?.serverResult)
                update { copy(error = reason) }
                return reason
            } finally {
                update { copy(reports = store.list(report.actor)) }
            }
        }
    }

    override fun onCleared() {
        generation++
        job?.cancel()
        viewModelScope.coroutineContext[Job]?.invokeOnCompletion { if (storage.isInitialized()) store.close() }
        super.onCleared()
    }
}

internal fun claimError(error: Exception): ClaimError = when (error) {
    is CancellationException -> throw error
    is ClaimProblem -> error.reason
    is EvidenceHttpProblem -> when {
        error.status == 409 && error.code == "idempotencia_conflictiva" -> ClaimError.CONFLICT
        error.status == 404 && error.code == "poliza_no_encontrada" -> ClaimError.POLICY
        error.status == 422 && error.code == "fecha_evento_invalida" -> ClaimError.DATE
        else -> when (error.status) {
            401, 403 -> ClaimError.SESSION
            400, 409, 422 -> ClaimError.PROTOCOL
            else -> ClaimError.NETWORK
        }
    }
    is EvidenceProblem -> when (error.reason) {
        EvidenceError.NETWORK -> ClaimError.NETWORK
        EvidenceError.SESSION -> ClaimError.SESSION
        else -> ClaimError.PROTOCOL
    }
    is AuthProblem -> when (error.reason) {
        AuthError.UNAVAILABLE -> ClaimError.NETWORK
        AuthError.STORAGE -> ClaimError.STORAGE
        else -> ClaimError.SESSION
    }
    is AuthHttpError -> if (error.status in setOf(401, 403)) ClaimError.SESSION else ClaimError.NETWORK
    is SQLiteException, is SecurityException -> ClaimError.STORAGE
    is DateTimeParseException, is IllegalArgumentException, is org.json.JSONException -> ClaimError.PROTOCOL
    is IOException -> ClaimError.NETWORK
    else -> throw error
}
