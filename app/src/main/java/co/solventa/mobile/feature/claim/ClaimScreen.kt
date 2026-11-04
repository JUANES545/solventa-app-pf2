package co.solventa.mobile.feature.claim

import android.net.ConnectivityManager
import android.net.Network
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import co.solventa.mobile.R
import co.solventa.mobile.core.designsystem.SolventaLayout
import co.solventa.mobile.core.ui.SolventaPage

@Composable
internal fun ClaimHost(model: ClaimViewModel, onStatus: ((ClaimReport) -> Unit)? = null, onEvidence: (ClaimReport) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by model.state.collectAsState()
    DisposableEffect(model, lifecycle) {
        val window = (context as? android.app.Activity)?.window
        val wasSecure = window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE) != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) model.retry() }
        lifecycle.addObserver(observer)
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val executor = ContextCompat.getMainExecutor(context)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                executor.execute { if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) model.retry() }
            }
        }
        manager.registerDefaultNetworkCallback(callback)
        onDispose {
            manager.unregisterNetworkCallback(callback)
            lifecycle.removeObserver(observer)
            if (!wasSecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
    BackHandler(onBack = model::close)
    ClaimScreen(state, model::edit, model::submit, model::retry, model::newReport, model::close, onEvidence, model::correct, onStatus)
}

@Composable
internal fun ClaimScreen(
    state: ClaimState, edit: (ClaimDraft) -> Unit, submit: () -> Unit, retry: () -> Unit,
    newReport: () -> Unit, back: () -> Unit, evidence: (ClaimReport) -> Unit,
    correct: (ClaimReport) -> Unit = {},
    status: ((ClaimReport) -> Unit)? = null,
) {
    SolventaPage {
        TextButton(onClick = back) { Text(stringResource(R.string.claim_back)) }
        Text(stringResource(R.string.claim_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
        if (state.busy) {
            CircularProgressIndicator()
            Text(stringResource(R.string.claim_processing), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        state.error?.let {
            Text(stringResource(claimErrorText(it)), Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.colorScheme.error)
        }
        if (state.editing) {
            Text(stringResource(R.string.claim_policy), style = MaterialTheme.typography.titleMedium)
            if (state.cachedPolicies) Text(stringResource(R.string.claim_cached_policies))
            if (state.policies.isEmpty()) Text(stringResource(R.string.claim_no_policies))
            state.policies.forEach { policy ->
                val selected = state.draft.policyId == policy.id
                Row(Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight).selectable(
                    selected = selected, enabled = !state.busy, role = Role.RadioButton,
                    onClick = { edit(state.draft.copy(policyId = policy.id)) }),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected, onClick = null, enabled = !state.busy)
                    Text(stringResource(R.string.claim_policy_option, policy.type, policy.id, policy.start.toString(), policy.end.toString()))
                }
            }
            ClaimField(R.string.claim_type, state.draft.type, state.busy) { edit(state.draft.copy(type = it)) }
            ClaimField(R.string.claim_date, state.draft.date, state.busy) { edit(state.draft.copy(date = it)) }
            ClaimField(R.string.claim_time, state.draft.time, state.busy) { edit(state.draft.copy(time = it)) }
            ClaimField(R.string.claim_place, state.draft.place, state.busy) { edit(state.draft.copy(place = it)) }
            ClaimField(R.string.claim_description, state.draft.description, state.busy, multiline = true) { edit(state.draft.copy(description = it)) }
            Button(onClick = submit, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.claim_submit))
            }
        } else {
            Button(onClick = newReport, enabled = !state.busy) { Text(stringResource(R.string.claim_new)) }
        }
        TextButton(onClick = retry, enabled = !state.busy) { Text(stringResource(R.string.claim_retry)) }
        state.reports.forEach { report ->
            HorizontalDivider()
            Text(report.payload.type, style = MaterialTheme.typography.titleMedium)
            Text(report.payload.description)
            val receipt = report.receipt
            if (receipt == null) {
                Text(stringResource(if (report.error == null || report.error == ClaimError.NETWORK)
                    R.string.claim_pending else R.string.claim_failed))
                report.error?.let { Text(stringResource(claimErrorText(it)), color = MaterialTheme.colorScheme.error) }
                if (report.correctable) {
                    Button(onClick = { correct(report) }, enabled = !state.busy) { Text(stringResource(R.string.claim_correct)) }
                }
            } else {
                Text(stringResource(R.string.claim_confirmed, receipt.id), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                status?.let {
                    Button(onClick = { it(report) }, enabled = !state.busy) { Text(stringResource(R.string.claim_status_view)) }
                }
                Button(onClick = { evidence(report) }, enabled = !state.busy) { Text(stringResource(R.string.evidence_entry)) }
            }
        }
    }
}

@Composable
private fun ClaimField(label: Int, value: String, busy: Boolean, multiline: Boolean = false, change: (String) -> Unit) {
    OutlinedTextField(value, change, Modifier.fillMaxWidth(), enabled = !busy,
        label = { Text(stringResource(label)) }, singleLine = !multiline, minLines = if (multiline) 3 else 1)
}

internal fun claimErrorText(error: ClaimError): Int = when (error) {
    ClaimError.REQUIRED -> R.string.claim_error_required
    ClaimError.DATE -> R.string.claim_error_date
    ClaimError.DESCRIPTION -> R.string.claim_error_description
    ClaimError.LENGTH -> R.string.claim_error_length
    ClaimError.POLICY -> R.string.claim_error_policy
    ClaimError.NETWORK -> R.string.claim_error_network
    ClaimError.SESSION -> R.string.claim_error_session
    ClaimError.PROTOCOL -> R.string.claim_error_protocol
    ClaimError.STORAGE -> R.string.claim_error_storage
}
