package co.solventa.mobile.feature.assistance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class AssistanceStep {
    LOCATION,
    MANUAL,
    PROVIDERS,
    CONFIRMATION,
    SUCCESS,
}

internal data class AssistanceState(
    val actor: String? = null,
    val claimId: String? = null,
    val open: Boolean = false,
    val step: AssistanceStep = AssistanceStep.LOCATION,
    val location: AssistanceLocation? = null,
    val manualLatitude: String = "",
    val manualLongitude: String = "",
    val providers: List<AssistanceProvider> = emptyList(),
    val selected: AssistanceProvider? = null,
    val operationId: String? = null,
    val receipt: AssistanceReceipt? = null,
    val busy: Boolean = false,
    val error: AssistanceError? = null,
)

@HiltViewModel
internal class AssistanceViewModel @Inject constructor(
    private val api: AssistanceGateway,
    private val locator: AssistanceLocator,
) : ViewModel() {
    private val mutable = MutableStateFlow(AssistanceState())
    val state = mutable.asStateFlow()
    private var job: Job? = null

    fun bindActor(actor: String?) {
        if (state.value.actor != actor) close()
    }

    fun show(actor: String, claimId: String) {
        close()
        mutable.value = AssistanceState(
            actor = actor,
            claimId = UUID.fromString(claimId).toString(),
            open = true,
        )
    }

    fun close() {
        job?.cancel()
        mutable.value = AssistanceState()
    }

    fun permissionDenied() {
        val current = state.value
        if (!current.open || current.busy) return
        mutable.value = current.copy(
            step = AssistanceStep.MANUAL,
            error = AssistanceError.PERMISSION,
        )
    }

    fun locate() {
        val current = state.value
        if (!current.open || current.busy) return
        operation {
            val location = locator.current()
            if (location == null) {
                update {
                    copy(
                        step = AssistanceStep.MANUAL,
                        error = AssistanceError.LOCATION_UNAVAILABLE,
                    )
                }
            } else {
                loadProviders(location)
            }
        }
    }

    fun manualInput(latitude: String, longitude: String) {
        val current = state.value
        if (!current.open || current.busy) return
        mutable.value = current.copy(
            manualLatitude = latitude,
            manualLongitude = longitude,
            error = null,
        )
    }

    fun useManualLocation() {
        val current = state.value
        if (!current.open || current.busy) return
        val location = try {
            manualAssistanceLocation(current.manualLatitude, current.manualLongitude)
        } catch (error: AssistanceProblem) {
            mutable.value = current.copy(error = error.reason)
            return
        }
        operation { loadProviders(location) }
    }

    fun retryProviders() {
        val location = state.value.location ?: return
        if (state.value.busy) return
        operation { loadProviders(location) }
    }

    fun select(provider: AssistanceProvider) {
        val current = state.value
        if (current.busy || provider !in current.providers) return
        mutable.value = current.copy(
            step = AssistanceStep.CONFIRMATION,
            selected = provider,
            operationId = null,
            receipt = null,
            error = null,
        )
    }

    fun submit() {
        val current = state.value
        val actor = current.actor ?: return
        val claim = current.claimId ?: return
        val location = current.location ?: return
        val provider = current.selected ?: return
        if (current.busy || current.receipt != null) return
        val operationId = current.operationId ?: UUID.randomUUID().toString()
        mutable.value = current.copy(operationId = operationId, error = null)
        operation {
            val receipt = api.request(actor, claim, provider, location, operationId)
            update {
                copy(
                    step = AssistanceStep.SUCCESS,
                    receipt = receipt,
                    busy = false,
                    error = null,
                )
            }
        }
    }

    fun back() {
        val current = state.value
        if (current.busy) return
        mutable.value = when (current.step) {
            AssistanceStep.LOCATION, AssistanceStep.SUCCESS -> current
            AssistanceStep.MANUAL -> current.copy(
                step = AssistanceStep.LOCATION,
                error = null,
            )
            AssistanceStep.PROVIDERS -> current.copy(
                step = AssistanceStep.LOCATION,
                providers = emptyList(),
                location = null,
                error = null,
            )
            AssistanceStep.CONFIRMATION -> current.copy(
                step = AssistanceStep.PROVIDERS,
                selected = null,
                operationId = null,
                error = null,
            )
        }
    }

    private suspend fun loadProviders(location: AssistanceLocation) {
        val actor = state.value.actor ?: return
        val providers = api.providers(actor, location)
        update {
            copy(
                step = AssistanceStep.PROVIDERS,
                location = location,
                providers = providers,
                selected = null,
                operationId = null,
                error = if (providers.isEmpty()) AssistanceError.NO_PROVIDER else null,
            )
        }
    }

    private fun operation(action: suspend () -> Unit) {
        job?.cancel()
        mutable.value = state.value.copy(busy = true, error = null)
        job = viewModelScope.launch {
            try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (error: AssistanceProblem) {
                update { copy(error = error.reason) }
            } catch (error: Exception) {
                update { copy(error = AssistanceError.PROTOCOL) }
            } finally {
                update { copy(busy = false) }
            }
        }
    }

    private fun update(change: AssistanceState.() -> AssistanceState) {
        mutable.value = mutable.value.change()
    }
}
