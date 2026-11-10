package co.solventa.mobile.feature.claim

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
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
import co.solventa.mobile.core.ui.RetryOnReconnect
import co.solventa.mobile.core.ui.SolventaScreenHeader
import co.solventa.mobile.core.ui.SolventaSkeletonList
import co.solventa.mobile.core.ui.SolventaLoadError
import co.solventa.mobile.core.ui.SolventaEmptyState
import co.solventa.mobile.core.ui.SolventaMetadataDetails
import co.solventa.mobile.core.ui.SolventaConfirmationMark
import co.solventa.mobile.core.ui.SolventaDateField
import co.solventa.mobile.core.ui.SolventaTimeField
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun ClaimHost(model: ClaimViewModel, onStatus: ((ClaimReport) -> Unit)? = null, onEvidence: (ClaimReport) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by model.state.collectAsState()
    var locating by remember { mutableStateOf(false) }
    RetryOnReconnect(state.actor, state.open && !state.busy && !locating, model::retry)
    DisposableEffect(model, lifecycle) {
        val window = (context as? android.app.Activity)?.window
        val wasSecure = window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE) != 0
        if (!BuildConfig.SCREEN_CAPTURE_ALLOWED) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            if (!wasSecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
    BackHandler(onBack = model::close)
    ClaimScreen(state, model::edit, model::submit, model::retry, model::newReport, model::close, onEvidence,
        model::correct, onStatus, readState = { model.state.value }, refresh = model::refreshPolicies,
        onLocationActiveChange = { locating = it })
}

@Composable
internal fun ClaimScreen(
    state: ClaimState, edit: (ClaimDraft) -> Unit, submit: () -> Unit, retry: () -> Unit,
    newReport: () -> Unit, back: () -> Unit, evidence: (ClaimReport) -> Unit,
    correct: (ClaimReport) -> Unit = {},
    status: ((ClaimReport) -> Unit)? = null,
    readState: () -> ClaimState = { state },
    refresh: () -> Unit = retry,
    onLocationActiveChange: (Boolean) -> Unit = {},
) {
    val feedback = remember { SnackbarHostState() }
    SolventaPage(headerAlignment = Alignment.Start, feedback = { SnackbarHost(feedback) },
        onRefresh = refresh, isRefreshing = state.loadingPolicies, refreshEnabled = !state.busy, header = {
        SolventaScreenHeader(stringResource(R.string.claim_title), stringResource(R.string.claim_back), back)
    }) {
        val open = state.reports.filter { it.receipt == null && it.supersededBy == null }
        val pending = open.filter { it.retryable }
        val review = open.filter { !it.retryable }
        val initialLoadError = state.editing && state.policies.isEmpty() &&
            state.error in setOf(ClaimError.NETWORK, ClaimError.SESSION, ClaimError.PROTOCOL, ClaimError.STORAGE)
        state.error?.takeUnless { initialLoadError || open.any { report -> report.error == it } }?.let {
            Text(stringResource(claimErrorText(it)), Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.colorScheme.error)
        }
        if (pending.isNotEmpty()) ClaimPendingCard(pending, state.busy, retry)
        val sent = state.reports.lastOrNull()?.takeIf { !state.editing && it.receipt != null }
        if (sent != null) ClaimSentCard(sent, state.busy, evidence, status)
        if (review.isNotEmpty()) ClaimReviewCard(review, state.busy, !state.editing, correct)
        if (state.editing) {
            Text(stringResource(R.string.claim_policy), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            if (state.cachedPolicies && state.policies.isNotEmpty() && !state.loadingPolicies) {
                Text(stringResource(R.string.claim_cached_policies), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
                    co.solventa.mobile.core.ui.SolventaRefreshing(state.loadingPolicies) {
                        Card(modifier = Modifier.fillMaxWidth().testTag("claim-policy-${policy.id}").selectable(
                            selected = selected, enabled = !state.busy, role = Role.RadioButton,
                            onClick = { edit(state.draft.copy(policyId = policy.id)) }),
                            colors = CardDefaults.cardColors(containerColor = if (selected)
                            MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow),
                            shape = MaterialTheme.shapes.large) {
                            if (policy.type.lowercase() in setOf("vehículo", "vehiculo", "automotive", "auto")) {
                                Image(painterResource(R.drawable.policy_vehicle), null,
                                    Modifier.fillMaxWidth().aspectRatio(320f / 104f), contentScale = ContentScale.Fit)
                            }
                            Row(Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight).padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected, onClick = null, enabled = !state.busy)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(policy.type, style = MaterialTheme.typography.titleMedium)
                                    val twins = state.policies.filter { it.type == policy.type }
                                    if (twins.size > 1) Text(stringResource(R.string.claim_policy_reference,
                                        operationalReference(policy.id, twins.map { it.id })),
                                        style = MaterialTheme.typography.bodyMedium)
                                    val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                                    Text(stringResource(R.string.claim_policy_validity, dateFormat.format(policy.start), dateFormat.format(policy.end)),
                                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
            Text(stringResource(R.string.claim_event_section), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            ClaimField(R.string.claim_type, state.draft.type, state.busy) { edit(state.draft.copy(type = it)) }
            key(state.actor, state.correction) {
                SolventaDateField(stringResource(R.string.claim_date), state.draft.date, !state.busy) {
                    edit(state.draft.copy(date = it))
                }
                SolventaTimeField(stringResource(R.string.claim_time), state.draft.time, !state.busy) {
                    edit(state.draft.copy(time = it))
                }
                ClaimLocationField(state, edit, { feedback.showSnackbar(it) }, readState = readState,
                    onActiveChange = onLocationActiveChange)
            }
            ClaimField(R.string.claim_description, state.draft.description, state.busy, multiline = true) { edit(state.draft.copy(description = it)) }
            Button(onClick = submit, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight)) {
                Text(stringResource(R.string.claim_submit), textAlign = TextAlign.Center)
            }
        } else {
            OutlinedButton(onClick = newReport, enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight)) {
                Text(stringResource(R.string.claim_new), textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun ClaimPanel(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun ClaimPendingCard(pending: List<ClaimReport>, busy: Boolean, send: () -> Unit) {
    ClaimPanel {
        Text(pluralStringResource(R.plurals.claim_pending_count, pending.size, pending.size),
            Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        Text(pending.joinToString(" · ") { it.payload.type }, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val failure = pending.firstNotNullOfOrNull { it.error }
        Column(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
            when {
                busy -> Text(stringResource(R.string.claim_sending), style = MaterialTheme.typography.labelLarge)
                failure != null -> {
                    Text(stringResource(R.string.claim_failed), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error)
                    Text(stringResource(claimErrorText(failure)), style = MaterialTheme.typography.bodyMedium)
                }
                else -> Text(stringResource(R.string.claim_pending), style = MaterialTheme.typography.labelLarge)
            }
        }
        Button(onClick = send, enabled = !busy, modifier = Modifier.heightIn(min = SolventaLayout.controlHeight)) {
            Text(stringResource(R.string.claim_send_pending), textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ClaimSentCard(report: ClaimReport, busy: Boolean, evidence: (ClaimReport) -> Unit, status: ((ClaimReport) -> Unit)?) {
    val receipt = checkNotNull(report.receipt)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SolventaConfirmationMark(receipt.id)
        Text(stringResource(R.string.operational_report_sent), Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.titleLarge)
    }
    ClaimPanel {
        Text(report.payload.type, style = MaterialTheme.typography.titleMedium)
        Text(operationalDate(report.payload.date), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(report.payload.description, maxLines = 2, overflow = TextOverflow.Ellipsis)
        SolventaMetadataDetails(reference = receipt.id)
        Button(onClick = { evidence(report) }, enabled = !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight)) {
            Text(stringResource(R.string.claim_add_evidence), textAlign = TextAlign.Center)
        }
        status?.let {
            OutlinedButton(onClick = { it(report) }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight)) {
                Text(stringResource(R.string.claim_status_view), textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun ClaimReviewCard(review: List<ClaimReport>, busy: Boolean, open: Boolean, correct: (ClaimReport) -> Unit) {
    var expanded by rememberSaveable(open) { mutableStateOf(open) }
    ClaimPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(pluralStringResource(R.plurals.claim_review_count, review.size, review.size),
                Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = SolventaLayout.controlHeight)) {
                Text(stringResource(if (expanded) R.string.claim_review_hide else R.string.claim_review_show))
            }
        }
        if (expanded) review.forEach { report ->
            HorizontalDivider()
            Text(report.payload.type, style = MaterialTheme.typography.titleSmall)
            Text(stringResource(if (report.error == ClaimError.CONFLICT) R.string.sync_conflict else R.string.evidence_rejected),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
            report.error?.let { Text(stringResource(claimErrorText(it)), style = MaterialTheme.typography.bodyMedium) }
            Text(operationalDate(report.payload.date), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(report.payload.description, maxLines = 2, overflow = TextOverflow.Ellipsis)
            SolventaMetadataDetails(reference = report.operation, entries = buildList {
                report.queuedAt?.let { add(stringResource(R.string.operational_queued_instant) to it.toString()) }
                report.lastResultAt?.let { add(stringResource(R.string.operational_observed_instant) to it.toString()) }
                report.serverResult?.let { add(stringResource(R.string.operational_server_instant) to it.at.toString()) }
                report.errorCode?.let { add(stringResource(R.string.operational_error_code) to it) }
            })
            if (report.correctable) {
                OutlinedButton(onClick = { correct(report) }, enabled = !busy, modifier = Modifier.heightIn(min = SolventaLayout.controlHeight)) {
                    Text(stringResource(R.string.claim_correct), textAlign = TextAlign.Center)
                }
            }
        }
    }
}
@Composable
private fun ClaimField(label: Int, value: String, busy: Boolean, multiline: Boolean = false, change: (String) -> Unit) {
    val hint = when (label) {
        R.string.claim_description -> R.string.operational_description_hint
        else -> null
    }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val sample = stringResource(R.string.claim_sample_description)
    val latestValue by rememberUpdatedState(value)
    val latestChange by rememberUpdatedState(change)
    LaunchedEffect(focused) {
        if (BuildConfig.CLAIM_SAMPLE_ENABLED && label == R.string.claim_description && focused && latestValue.isBlank() && !busy) {
            latestChange(sample)
        }
    }
    OutlinedTextField(value, change, Modifier.fillMaxWidth(), enabled = !busy,
        interactionSource = interaction,
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
