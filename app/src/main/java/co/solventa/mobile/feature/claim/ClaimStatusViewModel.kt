package co.solventa.mobile.feature.claim

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.solventa.mobile.feature.evidence.EvidenceClaim
import co.solventa.mobile.feature.evidence.EvidenceHttpProblem
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class ClaimStatusError { NETWORK, SESSION, UNAVAILABLE, PROTOCOL, STORAGE }
internal data class ClaimStatusState(
    val actor: String? = null,
    val open: Boolean = false,
    val busy: Boolean = false,
    val claims: List<EvidenceClaim> = emptyList(),
    val selectedId: String? = null,
    val fromList: Boolean = false,
    val detail: ClaimStatus? = null,
    val error: ClaimStatusError? = null,
    val selectedClaim: EvidenceClaim? = null,
    val caseBusy: Boolean = false,
    val caseLoaded: Boolean = false,
    val caseError: ClaimStatusError? = null,
)

@HiltViewModel
internal class ClaimStatusViewModel @Inject constructor(private val api: MobileClaimApi) : ViewModel() {
    private val mutable = MutableStateFlow(ClaimStatusState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var job: Job? = null
    private var caseJob: Job? = null

    fun bindActor(actor: String?) {
        if (actor != state.value.actor) reset(ClaimStatusState(actor = actor))
    }

    fun show(actor: String, id: String? = null) {
        reset(ClaimStatusState(actor = actor, open = true, selectedId = id, fromList = id == null))
        refresh()
    }

    fun select(claim: EvidenceClaim) {
        val current = state.value
        if (current.busy || claim !in current.claims) return
        caseJob?.cancel()
        mutable.value = current.copy(selectedId = claim.id, selectedClaim = claim, detail = null,
            caseBusy = false, caseLoaded = false, caseError = null)
        refresh()
    }

    fun back() {
        val current = state.value
        if (current.fromList && current.selectedId != null && current.actor != null) show(current.actor)
        else reset(ClaimStatusState(actor = current.actor))
    }

    fun pause() {
        reset(state.value.copy(busy = false, claims = emptyList(), detail = null, error = null,
            selectedClaim = null, caseBusy = false, caseLoaded = false, caseError = null))
    }

    fun loadCase() {
        val current = state.value
        val actor = current.actor ?: return
        val id = current.selectedId ?: return
        if (!current.open || current.caseBusy) return
        val version = generation
        mutable.value = current.copy(caseBusy = true, caseError = null)
        caseJob = viewModelScope.launch {
            try {
                val claim = withContext(Dispatchers.IO) {
                    api.claims(actor).singleOrNull { it.id == id }
                        ?: throw EvidenceHttpProblem(404, "siniestro_no_encontrado", true,
                            co.solventa.mobile.feature.evidence.EvidenceError.PROTOCOL)
                }
                updateCase(version, actor, id) {
                    copy(selectedClaim = claim, caseLoaded = true)
                }
            } catch (error: Exception) {
                val reason = claimStatusError(error)
                updateCase(version, actor, id) {
                    copy(caseError = reason, caseLoaded = false,
                        selectedClaim = selectedClaim.takeIf { reason == ClaimStatusError.NETWORK })
                }
            } finally {
                updateCase(version, actor, id) { copy(caseBusy = false) }
            }
        }
    }

    fun refresh() {
        val current = state.value
        val actor = current.actor ?: return
        if (!current.open || current.busy) return
        val version = generation
        mutable.value = current.copy(busy = true,
            claims = current.claims.takeIf { current.selectedId == null }.orEmpty(), error = null)
        job = viewModelScope.launch {
            try {
                if (current.selectedId == null) {
                    val claims = withContext(Dispatchers.IO) { api.claims(actor) }
                    update(version) { copy(claims = claims) }
                } else {
                    val detail = withContext(Dispatchers.IO) { api.status(actor, current.selectedId) }
                    update(version) { copy(detail = detail) }
                }
            } catch (error: Exception) {
                val reason = claimStatusError(error)
                update(version) {
                    if (reason == ClaimStatusError.NETWORK) copy(error = reason)
                    else copy(error = reason, claims = emptyList(), detail = null,
                        selectedClaim = null, caseLoaded = false)
                }
            } finally {
                update(version) { copy(busy = false) }
            }
        }
    }

    private fun reset(next: ClaimStatusState) {
        generation++
        job?.cancel()
        caseJob?.cancel()
        mutable.value = next
    }

    private fun updateCase(version: Long, actor: String, id: String, change: ClaimStatusState.() -> ClaimStatusState) {
        if (state.value.actor == actor && state.value.selectedId == id) update(version, change)
    }

    private fun update(version: Long, change: ClaimStatusState.() -> ClaimStatusState) {
        if (version == generation) mutable.value = state.value.change()
    }
}

internal fun claimStatusError(error: Exception): ClaimStatusError {
    if (error is EvidenceHttpProblem && error.status == 404 && error.code == "siniestro_no_encontrado") {
        return ClaimStatusError.UNAVAILABLE
    }
    return when (claimError(error)) {
        ClaimError.NETWORK -> ClaimStatusError.NETWORK
        ClaimError.SESSION -> ClaimStatusError.SESSION
        ClaimError.STORAGE -> ClaimStatusError.STORAGE
        else -> ClaimStatusError.PROTOCOL
    }
}
