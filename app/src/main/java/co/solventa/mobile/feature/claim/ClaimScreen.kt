package co.solventa.mobile.feature.claim

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.LocalLifecycleOwner
import co.solventa.mobile.R
import co.solventa.mobile.BuildConfig
import co.solventa.mobile.core.designsystem.SolventaLayout
import co.solventa.mobile.core.ui.SolventaPage
import co.solventa.mobile.core.ui.SolventaSection
import co.solventa.mobile.core.ui.RetryOnReconnect
import co.solventa.mobile.core.ui.SolventaScreenHeader
import co.solventa.mobile.core.ui.SolventaSkeletonList
import co.solventa.mobile.core.ui.SolventaLoadError
import co.solventa.mobile.core.ui.SolventaEmptyState
import co.solventa.mobile.core.ui.SolventaMetadataDetails
import co.solventa.mobile.core.ui.SolventaConfirmationMark
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun ClaimHost(model: ClaimViewModel, onStatus: ((ClaimReport) -> Unit)? = null, onEvidence: (ClaimReport) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by model.state.collectAsState()
    RetryOnReconnect(state.actor, state.open && !state.busy, model::retry)
    DisposableEffect(model, lifecycle) {
        val window = (context as? android.app.Activity)?.window
        val wasSecure = window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE) != 0
        if (!BuildConfig.SCREEN_CAPTURE_ALLOWED) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
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
    SolventaPage(headerAlignment = Alignment.Start, header = {
        SolventaScreenHeader(stringResource(R.string.claim_title), stringResource(R.string.claim_back), back)
    }) {
        if (state.busy && !state.loadingPolicies) {
            CircularProgressIndicator()
            Text(stringResource(R.string.claim_processing), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (state.loadingPolicies && state.policies.isNotEmpty()) {
            CircularProgressIndicator()
            Text(stringResource(R.string.operational_refreshing), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        val initialLoadError = state.editing && state.policies.isEmpty() &&
            state.error in setOf(ClaimError.NETWORK, ClaimError.SESSION, ClaimError.PROTOCOL, ClaimError.STORAGE)
        state.error?.takeUnless { initialLoadError }?.let {
            Text(stringResource(claimErrorText(it)), Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.colorScheme.error)
        }
        if (state.editing) {
            Text(stringResource(R.string.claim_policy), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            if (state.cachedPolicies && state.policies.isNotEmpty()) Text(stringResource(R.string.claim_cached_policies))
            if (state.loadingPolicies && state.policies.isEmpty()) {
                SolventaSkeletonList(stringResource(R.string.operational_loading_policies))
            } else if (initialLoadError) {
                SolventaLoadError(stringResource(R.string.ui_load_policies_error),
                    stringResource(claimErrorText(checkNotNull(state.error))))
            }
            if (!state.busy && state.policies.isEmpty() && state.error == null) SolventaEmptyState(stringResource(R.string.claim_no_policies))
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.policies.forEach { policy ->
                    val selected = state.draft.policyId == policy.id
                    Card(colors = CardDefaults.cardColors(containerColor = if (selected)
                        MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Row(Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight).selectable(
                            selected = selected, enabled = !state.busy, role = Role.RadioButton,
                            onClick = { edit(state.draft.copy(policyId = policy.id)) }).padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected, onClick = null, enabled = !state.busy)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(policy.type, style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(R.string.claim_policy_reference, policy.id), style = MaterialTheme.typography.bodyMedium)
                                val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                                Text(stringResource(R.string.claim_policy_validity, dateFormat.format(policy.start), dateFormat.format(policy.end)),
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
            Text(stringResource(R.string.claim_event_section), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            ClaimField(R.string.claim_type, state.draft.type, state.busy) { edit(state.draft.copy(type = it)) }
            ClaimField(R.string.claim_date, state.draft.date, state.busy) { edit(state.draft.copy(date = it)) }
            ClaimField(R.string.claim_time, state.draft.time, state.busy) { edit(state.draft.copy(time = it)) }
            ClaimField(R.string.claim_place, state.draft.place, state.busy) { edit(state.draft.copy(place = it)) }
            ClaimField(R.string.claim_description, state.draft.description, state.busy, multiline = true) { edit(state.draft.copy(description = it)) }
            Button(onClick = submit, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight)) {
                Text(stringResource(R.string.claim_submit), textAlign = TextAlign.Center)
            }
        } else {
            Button(onClick = newReport, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight)) { Text(stringResource(R.string.claim_new), textAlign = TextAlign.Center) }
        }
        TextButton(onClick = retry, enabled = !state.busy, modifier = Modifier.heightIn(min = SolventaLayout.controlHeight)) { Text(stringResource(R.string.claim_retry), textAlign = TextAlign.Center) }
        if (state.reports.isNotEmpty()) Text(stringResource(R.string.claim_history_title),
            Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        state.reports.forEach { report ->
            SolventaSection(title = report.payload.type) {
                val receipt = report.receipt
                if (receipt == null) {
                    Text(stringResource(when (report.error) {
                        null -> R.string.claim_pending
                        ClaimError.CONFLICT -> R.string.sync_conflict
                        ClaimError.POLICY, ClaimError.DATE -> R.string.evidence_rejected
                        else -> R.string.claim_failed
                    }))
                    report.error?.let { Text(stringResource(claimErrorText(it)), color = MaterialTheme.colorScheme.error) }
                } else {
                    SolventaConfirmationMark(receipt.id)
                    Text(stringResource(R.string.operational_report_sent), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                Text(report.payload.description)
                Text(stringResource(R.string.operational_event_date, operationalDate(report.payload.date)))
                Text(stringResource(R.string.evidence_claim_reference,
                    operationalReference(receipt?.id ?: report.operation, state.reports.map { it.receipt?.id ?: it.operation })),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                report.supersededBy?.let { Text(stringResource(R.string.claim_corrected)) }
                SolventaMetadataDetails(reference = receipt?.id ?: report.operation, entries = buildList {
                    add(stringResource(R.string.operational_event_instant) to report.payload.date.toString())
                    add(stringResource(R.string.operational_display_zone) to java.time.ZoneId.systemDefault().id)
                    report.queuedAt?.let { add(stringResource(R.string.operational_queued_instant) to it.toString()) }
                    report.lastResultAt?.let { add(stringResource(R.string.operational_observed_instant) to it.toString()) }
                    report.serverResult?.let { add(stringResource(R.string.operational_server_instant) to it.at.toString()) }
                    report.errorCode?.let { add(stringResource(R.string.operational_error_code) to it) }
                })
                if (report.correctable) {
                    OutlinedButton(onClick = { correct(report) }, enabled = !state.busy, modifier = Modifier.heightIn(min = SolventaLayout.controlHeight)) {
                        Text(stringResource(R.string.claim_correct), textAlign = TextAlign.Center)
                    }
                }
                if (receipt != null) {
                    status?.let {
                        OutlinedButton(onClick = { it(report) }, enabled = !state.busy, modifier = Modifier.heightIn(min = SolventaLayout.controlHeight)) {
                            Text(stringResource(R.string.claim_status_view), textAlign = TextAlign.Center)
                        }
                    }
                    TextButton(onClick = { evidence(report) }, enabled = !state.busy, modifier = Modifier.heightIn(min = SolventaLayout.controlHeight)) {
                        Text(stringResource(R.string.evidence_entry), textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

@Composable
private fun ClaimField(label: Int, value: String, busy: Boolean, multiline: Boolean = false, change: (String) -> Unit) {
    val hint = when (label) {
        R.string.claim_date -> R.string.operational_date_hint
        R.string.claim_time -> R.string.operational_time_hint
        R.string.claim_description -> R.string.operational_description_hint
        else -> null
    }
    OutlinedTextField(value, change, Modifier.fillMaxWidth(), enabled = !busy,
        label = { Text(stringResource(label)) },
        supportingText = hint?.let { { Text(stringResource(it)) } },
        singleLine = !multiline, minLines = if (multiline) 3 else 1)
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
    ClaimError.CONFLICT -> R.string.sync_error_conflict
}
