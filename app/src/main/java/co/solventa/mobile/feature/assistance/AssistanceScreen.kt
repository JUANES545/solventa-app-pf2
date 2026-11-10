package co.solventa.mobile.feature.assistance

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import co.solventa.mobile.R
import co.solventa.mobile.core.designsystem.SolventaLayout
import co.solventa.mobile.core.location.LocationPermission
import co.solventa.mobile.core.ui.SolventaPage
import co.solventa.mobile.core.ui.SolventaScreenHeader
import co.solventa.mobile.core.ui.SolventaSkeletonList
import co.solventa.mobile.core.ui.SolventaLoadError
import co.solventa.mobile.core.ui.SolventaEmptyState
import co.solventa.mobile.core.ui.SolventaMetadataDetails
import co.solventa.mobile.core.ui.SolventaConfirmationMark
import co.solventa.mobile.feature.claim.operationalReference

@Composable
internal fun AssistanceScreen(
    model: AssistanceViewModel,
    onClose: () -> Unit = model::close,
) {
    val state by model.state.collectAsState()
    if (!state.open) return
    var permissionRequest by remember { mutableStateOf<AssistanceState?>(null) }
    AssistanceContent(
        state = state,
        onClose = onClose,
        onBack = model::back,
        onLocate = {
            permissionRequest = model.state.value.takeIf { it.step == AssistanceStep.LOCATION && !it.busy }
        },
        onManualOption = model::chooseManualLocation,
        onManualInput = model::manualInput,
        onSearch = model::useManualLocation,
        onSelect = model::select,
        onRetryProviders = model::retryProviders,
        onSubmit = model::submit,
    )
    permissionRequest?.takeIf { it === state }?.let { requested ->
        LocationPermission(
            onGranted = {
                permissionRequest = null
                if (model.state.value === requested) model.locate()
            },
            onCancel = {
                permissionRequest = null
                if (model.state.value === requested) model.permissionDenied()
            },
        )
    }
}

@Composable
internal fun AssistanceContent(
    state: AssistanceState,
    onClose: () -> Unit,
    onBack: () -> Unit,
    onLocate: () -> Unit,
    onManualOption: () -> Unit,
    onManualInput: (String, String) -> Unit,
    onSearch: () -> Unit,
    onSelect: (AssistanceProvider) -> Unit,
    onRetryProviders: () -> Unit,
    onSubmit: () -> Unit,
) {
    BackHandler(enabled = !state.busy) {
        if (state.step in setOf(AssistanceStep.LOCATION, AssistanceStep.SUCCESS)) {
            onClose()
        } else {
            onBack()
        }
    }
    SolventaPage(
        onRefresh = if (state.step == AssistanceStep.PROVIDERS) onRetryProviders else null,
        isRefreshing = state.busy,
        headerAlignment = Alignment.Start,
        header = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = {
                        if (state.step in setOf(AssistanceStep.LOCATION, AssistanceStep.SUCCESS)) onClose() else onBack()
                    },
                    enabled = !state.busy,
                    modifier = Modifier.heightIn(min = SolventaLayout.controlHeight),
                ) {
                    Icon(painterResource(R.drawable.arrow_back), null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.assistance_back), textAlign = TextAlign.Center)
                }
                SolventaScreenHeader(stringResource(R.string.assistance_title))
            }
        },
    ) {
        val initialLoadError = state.step == AssistanceStep.PROVIDERS && state.providers.isEmpty() &&
            state.error != null && state.error != AssistanceError.NO_PROVIDER
        state.error?.takeUnless { it == AssistanceError.NO_PROVIDER || initialLoadError }?.let {
            Text(
                stringResource(assistanceErrorMessage(it)),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .testTag("assistance-error")
                    .semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
        if (state.busy && state.step != AssistanceStep.PROVIDERS) {
            CircularProgressIndicator(Modifier.testTag("assistance-loading"))
            Text(stringResource(when (state.step) {
                AssistanceStep.PROVIDERS -> R.string.operational_refreshing
                AssistanceStep.CONFIRMATION -> R.string.operational_assistance_submitting
                AssistanceStep.LOCATION -> R.string.operational_location_loading
                else -> R.string.assistance_loading
            }), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        when (state.step) {
            AssistanceStep.LOCATION -> {
                Text(stringResource(R.string.assistance_location_info))
                Button(
                    onClick = onLocate,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight).testTag("assistance-use-gps"),
                ) {
                    Text(stringResource(R.string.assistance_use_location), textAlign = TextAlign.Center)
                }
                TextButton(
                    onClick = onManualOption,
                    enabled = !state.busy,
                    modifier = Modifier.heightIn(min = SolventaLayout.controlHeight).testTag("assistance-manual-option"),
                ) {
                    Text(stringResource(R.string.assistance_manual), textAlign = TextAlign.Center)
                }
            }
            AssistanceStep.MANUAL -> {
                Text(stringResource(R.string.assistance_manual_info))
                OutlinedTextField(
                    value = state.manualLatitude,
                    onValueChange = { onManualInput(it, state.manualLongitude) },
                    label = { Text(stringResource(R.string.assistance_latitude)) },
                    enabled = !state.busy,
                    singleLine = true,
                    isError = state.error == AssistanceError.INVALID_LOCATION,
                    modifier = Modifier.fillMaxWidth().testTag("assistance-latitude"),
                )
                OutlinedTextField(
                    value = state.manualLongitude,
                    onValueChange = { onManualInput(state.manualLatitude, it) },
                    label = { Text(stringResource(R.string.assistance_longitude)) },
                    enabled = !state.busy,
                    singleLine = true,
                    isError = state.error == AssistanceError.INVALID_LOCATION,
                    modifier = Modifier.fillMaxWidth().testTag("assistance-longitude"),
                )
                Button(
                    onClick = onSearch,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight).testTag("assistance-search-manual"),
                ) {
                    Text(stringResource(R.string.assistance_search), textAlign = TextAlign.Center)
                }
            }
            AssistanceStep.PROVIDERS -> {
                Text(
                    stringResource(R.string.assistance_providers),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                )
                if (state.busy && state.providers.isEmpty()) {
                    SolventaSkeletonList(stringResource(R.string.operational_loading_providers))
                } else if (initialLoadError) {
                    SolventaLoadError(stringResource(R.string.ui_load_providers_error),
                        stringResource(assistanceErrorMessage(checkNotNull(state.error))),
                        modifier = Modifier.testTag("assistance-error"))
                }
                if (!state.busy && state.providers.isEmpty() &&
                    (state.error == null || state.error == AssistanceError.NO_PROVIDER)) {
                    Box(Modifier.testTag("assistance-empty")) {
                        SolventaEmptyState(stringResource(R.string.assistance_empty))
                    }
                }
                state.providers.forEach { provider ->
                    co.solventa.mobile.core.ui.SolventaRefreshing(state.busy) {
                        Card(
                            onClick = { onSelect(provider) },
                            enabled = !state.busy,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                            modifier = Modifier.fillMaxWidth().testTag("assistance-provider-${provider.id}"),
                        ) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(provider.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                                Text(provider.serviceType.replace('_', ' '), style = MaterialTheme.typography.bodyMedium)
                                Text(stringResource(R.string.assistance_distance, provider.approximateDistanceKm),
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            AssistanceStep.CONFIRMATION -> {
                val provider = checkNotNull(state.selected)
                val claim = checkNotNull(state.claimId)
                val location = checkNotNull(state.location)
                Text(
                    stringResource(R.string.assistance_confirm),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(provider.name, style = MaterialTheme.typography.titleMedium)
                Text(provider.serviceType.replace('_', ' '))
                Text(
                    stringResource(
                        R.string.assistance_distance,
                        provider.approximateDistanceKm,
                    ),
                )
                Text(stringResource(R.string.assistance_claim, operationalReference(claim)),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    stringResource(
                        if (location.origin == AssistanceLocationOrigin.PERMISSION) {
                            R.string.assistance_location_gps
                        } else {
                            R.string.assistance_location_manual
                        },
                    ),
                )
                SolventaMetadataDetails(reference = claim)
                Button(
                    onClick = onSubmit,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight).testTag("assistance-submit"),
                ) {
                    Text(stringResource(R.string.assistance_request), textAlign = TextAlign.Center)
                }
                if (state.error != null) {
                    TextButton(
                        onClick = onSubmit,
                        enabled = !state.busy,
                        modifier = Modifier.heightIn(min = SolventaLayout.controlHeight).testTag("assistance-retry-submit"),
                    ) {
                        Text(stringResource(R.string.assistance_retry_same), textAlign = TextAlign.Center)
                    }
                }
            }
            AssistanceStep.SUCCESS -> {
                val receipt = checkNotNull(state.receipt)
                SolventaConfirmationMark(receipt.id)
                Text(
                    stringResource(R.string.assistance_confirmed),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    stringResource(R.string.assistance_status, receipt.status),
                    modifier = Modifier.testTag("assistance-receipt-status"),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.assistance_identifier, operationalReference(receipt.id)),
                    modifier = Modifier.testTag("assistance-receipt-id"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SolventaMetadataDetails(reference = receipt.id, entries = buildList {
                    add(stringResource(R.string.operational_server_instant) to receipt.createdAt.toString())
                    add(stringResource(R.string.operational_display_zone) to java.time.ZoneId.systemDefault().id)
                    state.claimId?.let { add(stringResource(R.string.assistance_claim_label) to it) }
                })
                Button(
                    onClick = onClose,
                    modifier = Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight),
                ) {
                    Text(stringResource(R.string.assistance_finish), textAlign = TextAlign.Center)
                }
            }
        }
    }
}

internal fun assistanceErrorMessage(error: AssistanceError): Int = when (error) {
    AssistanceError.PERMISSION -> R.string.assistance_error_permission
    AssistanceError.LOCATION_UNAVAILABLE -> R.string.assistance_error_location
    AssistanceError.INVALID_LOCATION -> R.string.assistance_error_invalid_location
    AssistanceError.NO_PROVIDER -> R.string.assistance_empty
    AssistanceError.PROVIDER_UNAVAILABLE -> R.string.assistance_error_provider
    AssistanceError.RESOURCE_UNAUTHORIZED -> R.string.assistance_error_resource
    AssistanceError.NETWORK -> R.string.assistance_error_network
    AssistanceError.SESSION -> R.string.assistance_error_session
    AssistanceError.PROTOCOL -> R.string.assistance_error_protocol
    AssistanceError.CONFLICT -> R.string.assistance_error_conflict
}
