package co.solventa.mobile.feature.assistance

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import co.solventa.mobile.R
import co.solventa.mobile.core.location.hasLocationPermission
import co.solventa.mobile.core.ui.SolventaPage

@Composable
internal fun AssistanceScreen(
    model: AssistanceViewModel,
    onClose: () -> Unit = model::close,
) {
    val state by model.state.collectAsState()
    if (!state.open) return
    val context = LocalContext.current
    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (hasLocationPermission(context)) model.locate() else model.permissionDenied()
    }
    BackHandler(enabled = !state.busy) {
        if (state.step in setOf(AssistanceStep.LOCATION, AssistanceStep.SUCCESS)) {
            onClose()
        } else {
            model.back()
        }
    }
    SolventaPage(
        header = {
            Text(
                stringResource(R.string.assistance_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
        },
        actions = {
            TextButton(
                onClick = {
                    if (state.step == AssistanceStep.LOCATION) onClose() else model.back()
                },
                enabled = !state.busy,
            ) {
                Text(stringResource(R.string.assistance_back))
            }
        },
    ) {
        state.error?.let {
            Text(
                stringResource(assistanceErrorMessage(it)),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .testTag("assistance-error")
                    .semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
        if (state.busy) {
            CircularProgressIndicator(Modifier.testTag("assistance-loading"))
            Text(stringResource(R.string.assistance_loading))
        }
        when (state.step) {
            AssistanceStep.LOCATION -> {
                Text(stringResource(R.string.assistance_location_info))
                Button(
                    onClick = {
                        if (hasLocationPermission(context)) {
                            model.locate()
                        } else {
                            permissions.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                ),
                            )
                        }
                    },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().testTag("assistance-use-gps"),
                ) {
                    Text(stringResource(R.string.assistance_use_location))
                }
                TextButton(
                    onClick = model::permissionDenied,
                    enabled = !state.busy,
                    modifier = Modifier.testTag("assistance-manual-option"),
                ) {
                    Text(stringResource(R.string.assistance_manual))
                }
            }
            AssistanceStep.MANUAL -> {
                Text(stringResource(R.string.assistance_manual_info))
                OutlinedTextField(
                    value = state.manualLatitude,
                    onValueChange = { model.manualInput(it, state.manualLongitude) },
                    label = { Text(stringResource(R.string.assistance_latitude)) },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().testTag("assistance-latitude"),
                )
                OutlinedTextField(
                    value = state.manualLongitude,
                    onValueChange = { model.manualInput(state.manualLatitude, it) },
                    label = { Text(stringResource(R.string.assistance_longitude)) },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().testTag("assistance-longitude"),
                )
                Button(
                    onClick = model::useManualLocation,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().testTag("assistance-search-manual"),
                ) {
                    Text(stringResource(R.string.assistance_search))
                }
            }
            AssistanceStep.PROVIDERS -> {
                Text(
                    stringResource(R.string.assistance_providers),
                    modifier = Modifier.semantics { heading() },
                )
                if (!state.busy && state.providers.isEmpty()) {
                    Text(
                        stringResource(R.string.assistance_empty),
                        modifier = Modifier.testTag("assistance-empty"),
                    )
                }
                state.providers.forEach { provider ->
                    Button(
                        onClick = { model.select(provider) },
                        enabled = !state.busy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("assistance-provider-${provider.id}"),
                    ) {
                        Text(
                            stringResource(
                                R.string.assistance_provider,
                                provider.name,
                                provider.serviceType.replace('_', ' '),
                                provider.approximateDistanceKm,
                            ),
                        )
                    }
                }
                TextButton(
                    onClick = model::retryProviders,
                    enabled = !state.busy,
                    modifier = Modifier.testTag("assistance-retry-providers"),
                ) {
                    Text(stringResource(R.string.assistance_retry))
                }
            }
            AssistanceStep.CONFIRMATION -> {
                val provider = checkNotNull(state.selected)
                val claim = checkNotNull(state.claimId)
                val location = checkNotNull(state.location)
                Text(
                    stringResource(R.string.assistance_confirm),
                    modifier = Modifier.semantics { heading() },
                )
                Text(provider.name)
                Text(provider.serviceType.replace('_', ' '))
                Text(
                    stringResource(
                        R.string.assistance_distance,
                        provider.approximateDistanceKm,
                    ),
                )
                Text(stringResource(R.string.assistance_claim, claim))
                Text(
                    stringResource(
                        if (location.origin == AssistanceLocationOrigin.PERMISSION) {
                            R.string.assistance_location_gps
                        } else {
                            R.string.assistance_location_manual
                        },
                    ),
                )
                Button(
                    onClick = model::submit,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().testTag("assistance-submit"),
                ) {
                    Text(stringResource(R.string.assistance_request))
                }
                if (state.error != null) {
                    TextButton(
                        onClick = model::submit,
                        enabled = !state.busy,
                        modifier = Modifier.testTag("assistance-retry-submit"),
                    ) {
                        Text(stringResource(R.string.assistance_retry_same))
                    }
                }
            }
            AssistanceStep.SUCCESS -> {
                val receipt = checkNotNull(state.receipt)
                Text(
                    stringResource(R.string.assistance_confirmed),
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    stringResource(R.string.assistance_identifier, receipt.id),
                    modifier = Modifier.testTag("assistance-receipt-id"),
                )
                Text(
                    stringResource(R.string.assistance_status, receipt.status),
                    modifier = Modifier.testTag("assistance-receipt-status"),
                )
                Button(
                    onClick = onClose,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.assistance_finish))
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
