package co.solventa.mobile.feature.policy

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import co.solventa.mobile.R
import co.solventa.mobile.core.designsystem.SolventaLayout
import co.solventa.mobile.core.ui.SolventaBrandHeader
import co.solventa.mobile.core.ui.SolventaPage
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
internal fun PolicyHost(
    model: PolicyViewModel, report: ((Policy) -> Boolean)?, continueDraft: () -> Unit, reportBusy: Boolean = false,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by model.state.collectAsState()
    var draftConflict by remember(state.actor, state.selectedId) { mutableStateOf(false) }
    DisposableEffect(model, lifecycle, context) {
        val window = (context as? Activity)?.window
        val wasSecure = window != null && window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> model.refresh()
                Lifecycle.Event.ON_STOP -> { draftConflict = false; model.pause() }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            model.pause()
            if (!wasSecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
    BackHandler(onBack = model::back)
    PolicyScreen(state, model::select, model::refresh, model::back,
        report?.let { action -> { policy -> draftConflict = !action(policy) } }, reportBusy)
    if (draftConflict) {
        AlertDialog(onDismissRequest = { draftConflict = false },
            title = { Text(stringResource(R.string.policy_draft_title)) },
            text = { Text(stringResource(R.string.policy_draft_body)) },
            confirmButton = {
                TextButton(onClick = { draftConflict = false; continueDraft() },
                    modifier = Modifier.heightIn(min = SolventaLayout.controlHeight)) {
                    Text(stringResource(R.string.policy_continue_draft))
                }
            },
            dismissButton = {
                TextButton(onClick = { draftConflict = false },
                    modifier = Modifier.heightIn(min = SolventaLayout.controlHeight)) {
                    Text(stringResource(R.string.policy_keep_detail))
                }
            })
    }
}

@Composable
internal fun PolicyScreen(
    state: PolicyState, select: (Policy) -> Unit, refresh: () -> Unit, back: () -> Unit,
    report: ((Policy) -> Unit)? = null, reportBusy: Boolean = false,
) {
    SolventaPage {
        SolventaBrandHeader()
        TextButton(onClick = back, modifier = Modifier.heightIn(min = SolventaLayout.controlHeight)) {
            Text(stringResource(if (state.selectedId == null) R.string.policy_back_home else R.string.policy_back_list))
        }
        Text(stringResource(if (state.selectedId == null) R.string.policy_title else R.string.policy_detail_title),
            Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
        when {
            state.busy || (!state.loaded && state.error == null) -> {
                CircularProgressIndicator()
                Text(stringResource(R.string.policy_loading), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            state.error != null -> Text(stringResource(policyErrorText(state.error)),
                Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error)
            state.detail != null -> {
                PolicyContent(state.detail)
                if (state.detail.status == PolicyStatus.ACTIVE && report != null) {
                    if (reportBusy) Text(stringResource(R.string.policy_report_busy),
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    Button(onClick = { report(state.detail) }, enabled = !reportBusy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight),
                        shape = SolventaLayout.controlShape) {
                        Text(stringResource(R.string.claim_title))
                    }
                } else if (state.detail.status != PolicyStatus.ACTIVE) {
                    Text(stringResource(R.string.policy_report_unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            state.selectedId == null && state.policies.isEmpty() -> Text(stringResource(R.string.policy_empty))
            state.selectedId == null -> state.policies.forEach { policy ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        PolicyContent(policy)
                        OutlinedButton(onClick = { select(policy) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight)) {
                            Text(stringResource(R.string.policy_view_detail))
                        }
                    }
                }
            }
        }
        OutlinedButton(onClick = refresh, enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight)) {
            Text(stringResource(if (state.error == null) R.string.policy_refresh else R.string.policy_retry))
        }
    }
}

@Composable
private fun PolicyContent(policy: Policy) {
    Text(stringResource(policyProductText(policy.product)), Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleLarge)
    Text(stringResource(R.string.policy_number, policy.number ?: stringResource(R.string.policy_not_available)))
    Text(stringResource(R.string.policy_status, stringResource(policyStatusText(policy.status))))
    val dates = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.forLanguageTag("es-CO"))
    Text(stringResource(R.string.policy_validity, dates.format(policy.start), dates.format(policy.end)))
    Text(stringResource(R.string.policy_coverage, policy.mainCoverage ?: stringResource(R.string.policy_not_available)))
    if (policy.simulated) Text(stringResource(R.string.policy_simulated), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

internal fun policyStatusText(status: PolicyStatus): Int = when (status) {
    PolicyStatus.SCHEDULED -> R.string.policy_scheduled
    PolicyStatus.ACTIVE -> R.string.policy_active
    PolicyStatus.EXPIRED -> R.string.policy_expired
}

internal fun policyProductText(product: PolicyProduct): Int = when (product) {
    PolicyProduct.AUTO -> R.string.policy_auto
    PolicyProduct.TRAVEL -> R.string.policy_travel
    PolicyProduct.LIFE -> R.string.policy_life
    PolicyProduct.DEVICE -> R.string.policy_device
    PolicyProduct.PARAMETRIC -> R.string.policy_parametric
    PolicyProduct.WEATHER -> R.string.policy_weather
    PolicyProduct.PAYMENT -> R.string.policy_payment
    PolicyProduct.MORTGAGE -> R.string.policy_mortgage
}

internal fun policyErrorText(error: PolicyError): Int = when (error) {
    PolicyError.NETWORK -> R.string.policy_network
    PolicyError.SESSION -> R.string.policy_session
    PolicyError.UNAVAILABLE -> R.string.policy_unavailable
    PolicyError.PROTOCOL -> R.string.policy_protocol
    PolicyError.STORAGE -> R.string.policy_storage
}
