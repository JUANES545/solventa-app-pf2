package co.solventa.mobile.feature.claim

import androidx.compose.ui.platform.testTag

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.LineBreak
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import co.solventa.mobile.R
import co.solventa.mobile.BuildConfig
import co.solventa.mobile.core.designsystem.SolventaLayout
import co.solventa.mobile.core.ui.SolventaPage
import co.solventa.mobile.core.ui.SolventaSection
import co.solventa.mobile.core.ui.SolventaScreenHeader
import co.solventa.mobile.core.ui.SolventaSkeletonList
import co.solventa.mobile.core.ui.SolventaLoadError
import co.solventa.mobile.core.ui.SolventaSkeletonDetail
import co.solventa.mobile.core.ui.SolventaEmptyState
import co.solventa.mobile.core.ui.SolventaRefreshing
import co.solventa.mobile.feature.evidence.EvidenceClaim
import co.solventa.mobile.feature.evidence.EvidenceGallery
import co.solventa.mobile.feature.evidence.EvidenceScope
import co.solventa.mobile.feature.evidence.EvidenceViewModel
import co.solventa.mobile.feature.evidence.evidenceErrorMessage
import co.solventa.mobile.feature.evidence.evidenceQueryErrorMessage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun ClaimStatusHost(model: ClaimStatusViewModel, evidence: EvidenceViewModel? = null,
    manage: ((EvidenceClaim) -> Unit)? = null) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by model.state.collectAsState()
    LaunchedEffect(state.actor, state.selectedId) { model.loadCase() }
    DisposableEffect(model, lifecycle, context) {
        val window = (context as? Activity)?.window
        val wasSecure = window != null && window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        if (!BuildConfig.SCREEN_CAPTURE_ALLOWED) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> { model.refresh(); model.loadCase() }
                Lifecycle.Event.ON_STOP -> model.pause()
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
    ClaimStatusScreen(state, model::select, { model.refresh(); if (state.selectedId != null) { model.loadCase(); evidence?.refresh() } }, model::back,
        attachments = { scope, claim ->
            if (manage != null) androidx.compose.material3.Button(onClick = { manage(claim) }) {
                Text(stringResource(R.string.evidence_manage))
            }
            if (evidence != null) ClaimCaseAttachments(evidence, scope, claim)
            else Text(stringResource(R.string.claim_status_attachments_error), color = MaterialTheme.colorScheme.error)
        })
}

@Composable
internal fun ClaimStatusScreen(
    state: ClaimStatusState, select: (EvidenceClaim) -> Unit, refresh: () -> Unit, back: () -> Unit,
    attachments: @Composable (EvidenceScope, EvidenceClaim) -> Unit = { _, _ ->
        Text(stringResource(R.string.claim_status_attachments_error), color = MaterialTheme.colorScheme.error)
    },
) {
    var expanded by rememberSaveable(state.actor, state.selectedId) { mutableStateOf(false) }
    SolventaPage(headerAlignment = Alignment.Start, onRefresh = refresh, isRefreshing = state.busy, refreshEnabled = !state.busy, header = {
        val title = if (state.selectedId == null) stringResource(R.string.claim_status_title)
            else state.selectedClaim?.takeIf { it.id == state.selectedId }?.let { claimDisplayType(it.type) }
                ?: stringResource(R.string.claim_status_detail_title)
        SolventaScreenHeader(title, stringResource(R.string.claim_status_back), back)
    }) {
        val hasContent = state.detail != null || (state.selectedId == null && state.claims.isNotEmpty())
        state.error?.takeIf { hasContent }?.let {
            Text(stringResource(claimStatusErrorText(it)),
                Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error)
        }
        when {
            state.busy && !hasContent -> {
                co.solventa.mobile.core.ui.CollectionLoading(stringResource(R.string.claim_status_loading), state.selectedId != null)
            }
            state.error != null && !hasContent -> SolventaLoadError(
                stringResource(R.string.ui_load_claim_status_error),
                stringResource(claimStatusErrorText(state.error)),
                detail = state.selectedId != null,
            )
            state.detail != null -> {
                SolventaRefreshing(state.busy) {
                    SolventaSection {
                        Text(stringResource(claimProgressText(state.detail.progress)),
                            Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite },
                            style = MaterialTheme.typography.titleLarge)
                        Text(state.detail.description)
                    }
                }
                val actor = state.actor
                val claim = state.selectedClaim?.takeIf { state.caseLoaded && state.caseError == null && it.id == state.selectedId }
                    SolventaSection {
                        when {
                            state.caseBusy -> SolventaSkeletonDetail(stringResource(R.string.claim_status_loading))
                            state.caseError != null -> SolventaLoadError(
                                stringResource(R.string.claim_status_details_error),
                                stringResource(claimStatusErrorText(state.caseError)), detail = true)
                            claim != null -> {
                                Text(stringResource(R.string.claim_status_event, operationalDate(Instant.parse(claim.date))), style = MaterialTheme.typography.labelLarge)
                                claim.place?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                                Text(claim.description)
                                if (claim.policyId == null || claim.place == null) {
                                    Text(stringResource(R.string.claim_status_partial_details),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            else -> Text(stringResource(R.string.claim_status_partial_details))
                        }
                    }
                if (claim != null && actor != null) attachments(EvidenceScope(actor, claim.id), claim)
                val expandedLabel = stringResource(if (expanded) R.string.ui_details_expanded else R.string.ui_details_collapsed)
                TextButton(onClick = { expanded = !expanded },
                    modifier = Modifier.heightIn(min = 48.dp).semantics { stateDescription = expandedLabel }) {
                    Text(stringResource(if (expanded) R.string.ui_hide_details else R.string.claim_history_references))
                }
                if (expanded) {
                    SolventaSection {
                        claim?.registeredAt?.let { ClaimCaseField(stringResource(R.string.claim_status_registered), operationalDate(it)) }
                        Text(stringResource(R.string.claim_status_updated, operationalDate(state.detail.updatedAt)))
                        ClaimCaseField(stringResource(R.string.claim_status_reference), state.detail.id, reference = true)
                        claim?.policyId?.let { ClaimCaseField(stringResource(R.string.claim_status_policy), it, reference = true) }
                        ClaimCaseField(stringResource(R.string.operational_updated_instant), state.detail.updatedAt.toString())
                        ClaimCaseField(stringResource(R.string.operational_display_zone), ZoneId.systemDefault().id)
                    }
                }
            }
            state.selectedId == null && state.claims.isEmpty() && state.error == null ->
                SolventaEmptyState(stringResource(R.string.claim_status_empty))
            state.selectedId == null -> {
                co.solventa.mobile.feature.policy.VisualCollection(state.claims, "claim-appearance",
                    R.plurals.claim_collection_count, select,
                    { claim, size -> co.solventa.mobile.feature.evidence.EvidenceThumbnail(
                        state.actor?.let { EvidenceScope(it, claim.id) }, modifier = size, illustration = claimIllustration(claim.type)) },
                    { claim, size ->
                        Column(size, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (claim.registrationStatus == "registrado") Text(stringResource(R.string.claim_registration_received),
                                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
                            Text(claimDisplayType(claim.type), style = MaterialTheme.typography.titleMedium)
                            Text(operationalDate(java.time.Instant.parse(claim.date)), style = MaterialTheme.typography.bodySmall)
                            val peers = state.claims.filter { it.type == claim.type && it.date == claim.date }
                            if (peers.size > 1) Text(stringResource(R.string.evidence_claim_reference,
                                operationalReference(claim.id, peers.map { it.id })))
                        }
                    }, { Modifier.testTag("claim-${it.id}") })
            }
        }
    }
}

@Composable
private fun ClaimCaseField(label: String, value: String, reference: Boolean = false) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer {
            Text(value, style = if (reference) MaterialTheme.typography.bodyMedium.copy(lineBreak = LineBreak.Heading)
                else MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
internal fun ClaimCaseAttachments(model: EvidenceViewModel, scope: EvidenceScope, claim: EvidenceClaim) {
    val state by model.state.collectAsState()
    LaunchedEffect(model, scope) { model.showClaim(scope.actorId, claim) }
    DisposableEffect(model, scope) { onDispose { model.close() } }
    SolventaSection {
        val sameActor = state.actorId == scope.actorId
        val ready = sameActor && state.scope == scope
        if (sameActor && state.error != null) {
            Text(stringResource(if (ready) evidenceErrorMessage(checkNotNull(state.error))
                else evidenceQueryErrorMessage(checkNotNull(state.error), state.errorHttpStatus)),
                Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { if (ready) model.refresh() else model.showClaim(scope.actorId, claim) },
                enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.claim_status_retry))
            }
        }
        if (ready) {
            if (state.busy) Text(stringResource(R.string.claim_status_attachments_loading),
                Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (state.items.isEmpty() && state.confirmedRemoteIds.isEmpty()) {
                Text(stringResource(R.string.evidence_gallery_title), Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.evidence_gallery_no_reported_files))
            } else EvidenceGallery(state, model::remove, original = model::original)
        } else if (!sameActor || state.error == null) {
            SolventaSkeletonDetail(stringResource(R.string.claim_status_attachments_loading))
        }
    }
}

internal fun claimStatusDate(date: Instant): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault()).format(date)

@Composable
internal fun operationalDate(date: Instant): String =
    stringResource(R.string.operational_date_device_zone, claimStatusDate(date))

internal fun claimProgressText(progress: ClaimProgress): Int = when (progress) {
    ClaimProgress.REPORTED -> R.string.claim_status_reported
    ClaimProgress.EVALUATING -> R.string.claim_status_evaluating
    ClaimProgress.REVIEWING -> R.string.claim_status_reviewing
    ClaimProgress.INSPECTING -> R.string.claim_status_inspecting
    ClaimProgress.APPROVED -> R.string.claim_status_approved
    ClaimProgress.REJECTED -> R.string.claim_status_rejected
    ClaimProgress.PAID -> R.string.claim_status_paid
    ClaimProgress.CLOSED -> R.string.claim_status_closed
}

internal fun claimStatusErrorText(error: ClaimStatusError): Int = when (error) {
    ClaimStatusError.NETWORK -> R.string.claim_status_network
    ClaimStatusError.SESSION -> R.string.claim_status_session
    ClaimStatusError.UNAVAILABLE -> R.string.claim_status_unavailable
    ClaimStatusError.PROTOCOL -> R.string.claim_status_protocol
    ClaimStatusError.STORAGE -> R.string.claim_status_storage
}
