package co.solventa.mobile.feature.policy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.solventa.mobile.feature.claim.ClaimError
import co.solventa.mobile.feature.claim.claimError
import co.solventa.mobile.feature.evidence.EvidenceHttpProblem
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class PolicyError { NETWORK, SESSION, UNAVAILABLE, PROTOCOL, STORAGE }
internal data class PolicyState(
    val actor: String? = null,
    val open: Boolean = false,
    val busy: Boolean = false,
    val loaded: Boolean = false,
    val policies: List<Policy> = emptyList(),
    val selectedId: String? = null,
    val detail: Policy? = null,
    val error: PolicyError? = null,
    val cachedAt: Long? = null,
)

@HiltViewModel
internal class PolicyViewModel(private val api: MobilePolicyApi) : ViewModel() {
    private var wallet: PolicyWallet? = null
    @Inject constructor(api: MobilePolicyApi, wallet: PolicyWallet) : this(api) { this.wallet = wallet }
    private val mutable = MutableStateFlow(PolicyState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var job: Job? = null

    fun bindActor(actor: String?) {
        if (actor != state.value.actor) {
            val previous = state.value.actor
            reset(PolicyState(actor = actor))
            if (previous != null) wallet?.clear()
        }
    }

    fun show(actor: String, selectedId: String? = null) {
        reset(PolicyState(actor = actor, open = true, selectedId = selectedId))
        refresh()
    }

    fun select(policy: Policy) {
        val current = state.value
        if (current.busy || policy !in current.policies) return
        reset(current.copy(selectedId = policy.id, detail = null, loaded = false))
        refresh()
    }

    fun close() = reset(PolicyState(actor = state.value.actor))

    fun back() {
        val current = state.value
        if (current.selectedId != null && current.actor != null) show(current.actor)
        else reset(PolicyState(actor = current.actor))
    }

    fun pause() {
        reset(state.value.copy(busy = false, loaded = false, policies = emptyList(), detail = null, error = null))
    }

    fun refresh() {
        val current = state.value
        val actor = current.actor ?: return
        if (!current.open || current.busy) return
        val version = generation
        mutable.value = current.copy(busy = true, error = null)
        job = viewModelScope.launch {
            try {
                if (current.selectedId == null) {
                    val policies = withContext(Dispatchers.IO) { api.list(actor) }
                    if (version == generation) wallet?.write(actor, policies)
                    update(version) { copy(policies = policies, loaded = true, cachedAt = null) }
                } else {
                    val detail = withContext(Dispatchers.IO) { api.detail(actor, current.selectedId) }
                    if (version == generation) wallet?.read(actor)?.let { cached ->
                        wallet?.write(actor, cached.policies.map { if (it.id == detail.id) detail else it })
                    }
                    update(version) { copy(detail = detail, loaded = true, cachedAt = null) }
                }
            } catch (error: Exception) {
                val reason = policyError(error)
                if (version != generation) return@launch
                when {
                    reason == PolicyError.SESSION || reason == PolicyError.UNAVAILABLE -> {
                        wallet?.clear()
                        update(version) { copy(error = reason, policies = emptyList(), detail = null, loaded = false, cachedAt = null) }
                    }
                    reason == PolicyError.NETWORK && error !is EvidenceHttpProblem &&
                        error !is co.solventa.mobile.feature.auth.AuthHttpError -> {
                        val saved = wallet?.read(actor)
                        val item = saved?.policies?.find { it.id == current.selectedId }
                        if (saved != null && (current.selectedId == null || item != null)) {
                            update(version) { copy(policies = saved.policies, detail = item, loaded = true, cachedAt = saved.updated) }
                        } else update(version) { copy(error = reason) }
                    }
                    else -> update(version) {
                        copy(error = reason, loaded = false, policies = emptyList(), detail = null, cachedAt = null)
                    }
                }
            } finally {
                update(version) { copy(busy = false) }
            }
        }
    }

    private fun reset(next: PolicyState) {
        generation++
        job?.cancel()
        mutable.value = next
    }

    private fun update(version: Long, change: PolicyState.() -> PolicyState) {
        if (version == generation) mutable.value = state.value.change()
    }
}

internal fun policyError(error: Exception): PolicyError {
    if (error is EvidenceHttpProblem && error.status == 404 && error.code == "poliza_no_encontrada") {
        return PolicyError.UNAVAILABLE
    }
    return when (claimError(error)) {
        ClaimError.NETWORK -> PolicyError.NETWORK
        ClaimError.SESSION -> PolicyError.SESSION
        ClaimError.STORAGE -> PolicyError.STORAGE
        else -> PolicyError.PROTOCOL
    }
}
