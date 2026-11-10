package co.solventa.mobile.feature.evidence

import android.Manifest
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import co.solventa.mobile.R
import co.solventa.mobile.core.designsystem.SolventaLayout
import co.solventa.mobile.core.ui.SolventaPage
import co.solventa.mobile.core.ui.SolventaSection
import co.solventa.mobile.core.ui.SolventaActionRow
import co.solventa.mobile.feature.claim.operationalDate
import co.solventa.mobile.feature.claim.operationalReference
import co.solventa.mobile.core.ui.SolventaScreenHeader
import co.solventa.mobile.core.ui.SolventaSkeletonList
import co.solventa.mobile.core.ui.SolventaLoadError
import co.solventa.mobile.core.ui.SolventaEmptyState
import co.solventa.mobile.core.ui.SolventaMetadataDetails
import co.solventa.mobile.core.ui.SolventaConfirmationMark
import java.time.Instant
import kotlinx.coroutines.launch

@Composable
internal fun EvidenceScreen(
    model: EvidenceViewModel,
    onClose: () -> Unit,
    onRetry: (() -> Unit)? = null,
    onAssistance: (() -> Unit)? = null,
    camera: @Composable (EvidenceViewModel, EvidenceState) -> Unit = { vm, state -> EvidenceCamera(vm, state) },
) {
    val state by model.state.collectAsState()
    val context = LocalContext.current
    EvidenceContent(
        state = state,
        onClose = onClose,
        onBack = model::back,
        onSelect = model::select,
        onReload = { state.actorId?.let(model::showClaims) },
        onCamera = model::camera,
        onRemove = model::removeRejected,
        onCrop = model::crop,
        onAccept = { model.accept(hasEvidenceLocationPermission(context)) },
        onError = model::fail,
        onRetry = onRetry,
        onAssistance = onAssistance,
        camera = { camera(model, state) },
        locationChoice = { LocationChoice(model, state) },
    )
}

@Composable
internal fun EvidenceContent(
    state: EvidenceState,
    onClose: () -> Unit,
    onBack: () -> Unit,
    onSelect: (EvidenceClaim) -> Unit,
    onReload: () -> Unit,
    onCamera: (EvidenceKind) -> Unit,
    onRemove: (EvidenceItem) -> Unit,
    onCrop: (EvidenceCrop) -> Unit,
    onAccept: () -> Unit,
    onError: (EvidenceError) -> Unit,
    onRetry: (() -> Unit)?,
    onAssistance: (() -> Unit)?,
    camera: @Composable () -> Unit,
    locationChoice: @Composable () -> Unit,
) {
    val context = LocalContext.current
    var discard by remember(state.scope) { mutableStateOf<EvidenceItem?>(null) }
    if (!state.open) return
    discard?.let { item ->
        AlertDialog(onDismissRequest = { discard = null },
            title = { Text(stringResource(R.string.evidence_remove)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(stringResource(R.string.evidence_remove_info)) } },
            confirmButton = { TextButton(onClick = { discard = null; onRemove(item) }) { Text(stringResource(R.string.evidence_remove), textAlign = TextAlign.Center) } },
            dismissButton = { TextButton(onClick = { discard = null }) { Text(stringResource(R.string.evidence_back), textAlign = TextAlign.Center) } })
    }
    BackHandler { if (!state.busy) { if (state.step == EvidenceStep.LIST) onClose() else onBack() } }
    SolventaPage(headerAlignment = Alignment.Start, header = {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { if (state.step == EvidenceStep.LIST) onClose() else onBack() }, enabled = !state.busy,
                modifier = Modifier.heightIn(min = SolventaLayout.controlHeight)) {
                Icon(painterResource(R.drawable.arrow_back), null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.evidence_back), textAlign = TextAlign.Center)
            }
            SolventaScreenHeader(stringResource(R.string.evidence_title))
        }
    }) {
        val initialLoadError = state.scope == null && state.claims.isEmpty() && state.error != null
        state.error?.takeUnless { initialLoadError }?.let { error ->
            val message = if (state.scope == null) evidenceQueryErrorMessage(error, state.errorHttpStatus)
                else evidenceErrorMessage(error)
            Text(stringResource(message), color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
        }
        if (state.busy && !state.loadingClaims) {
            CircularProgressIndicator()
            Text(stringResource(R.string.evidence_loading), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (state.loadingClaims && state.claims.isNotEmpty()) {
            CircularProgressIndicator()
            Text(stringResource(R.string.operational_refreshing), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (state.scope == null) {
            if (state.loadingClaims && state.claims.isEmpty()) SolventaSkeletonList(stringResource(R.string.operational_loading_claims))
            else if (initialLoadError) SolventaLoadError(stringResource(R.string.ui_load_claims_error),
                stringResource(evidenceQueryErrorMessage(checkNotNull(state.error), state.errorHttpStatus)))
            if (!state.busy && state.claims.isEmpty() && state.error == null) SolventaEmptyState(stringResource(R.string.evidence_no_claims))
            for (claim in state.claims) {
                Card(
                    onClick = { onSelect(claim) }, enabled = !state.busy,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth().testTag("claim-${claim.id}")) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(claim.type, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.operational_event_date, operationalDate(Instant.parse(claim.date))),
                            style = MaterialTheme.typography.bodyMedium)
                        Text(claim.description)
                        Text(stringResource(R.string.evidence_claim_reference,
                            operationalReference(claim.id, state.claims.map { it.id })),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SolventaMetadataDetails(reference = claim.id, entries = listOf(
                            stringResource(R.string.operational_event_instant) to claim.date,
                            stringResource(R.string.operational_display_zone) to java.time.ZoneId.systemDefault().id,
                        ))
                    }
                }
            }
            if (state.error != null) TextButton(onClick = onReload, enabled = !state.busy) {
                Text(stringResource(R.string.evidence_reload), textAlign = TextAlign.Center)
            }
            return@SolventaPage
        }
        when (state.step) {
            EvidenceStep.LIST -> {
                val policy = checkNotNull(state.policy)
                Text(stringResource(R.string.evidence_capture_section), Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.evidence_limit, policy.maxCount))
                for ((kind, bytes) in policy.maxBytes) {
                    SolventaActionRow(
                        title = stringResource(when (kind) {
                            EvidenceKind.PHOTO -> R.string.evidence_photo
                            EvidenceKind.DOCUMENT -> R.string.evidence_document
                            EvidenceKind.VIDEO -> R.string.evidence_video
                        }),
                        supportingText = stringResource(R.string.evidence_format_limit, stringResource(evidenceKindMessage(kind)), bytes) +
                            if (kind == EvidenceKind.VIDEO) "\n" + stringResource(R.string.evidence_video_limit, policy.maxVideoDurationMillis / 1000) else "",
                        onClick = { onCamera(kind) }, enabled = !state.busy,
                    )
                }
                Text(stringResource(R.string.evidence_location_section), Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleLarge)
                locationChoice()
                Text(stringResource(R.string.evidence_history_section), Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleLarge)
                if (!state.busy && state.error == null && state.items.isEmpty()) {
                    if (state.confirmedRemoteIds.isEmpty()) SolventaEmptyState(stringResource(R.string.evidence_empty))
                    else Text(stringResource(R.string.evidence_remote_registered, state.confirmedRemoteIds.size))
                }
                for (item in state.items) {
                    SolventaSection {
                        Text(stringResource(R.string.evidence_item, stringResource(evidenceKindMessage(item.kind)),
                            stringResource(when (item.status) {
                                EvidenceStatus.CONFIRMED -> R.string.evidence_confirmed
                                EvidenceStatus.REJECTED -> R.string.evidence_rejected
                                EvidenceStatus.CONFLICT -> R.string.sync_conflict
                                EvidenceStatus.PENDING -> if (item.lastError == null) R.string.evidence_pending else R.string.evidence_failed
                            })), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.titleMedium)
                        if (item.status == EvidenceStatus.CONFIRMED) SolventaConfirmationMark(item.id)
                        Text(stringResource(R.string.operational_captured_date, operationalDate(item.capturedAt)),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.evidence_claim_reference,
                            operationalReference(item.remoteId ?: item.id, state.items.map { it.remoteId ?: it.id })),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SolventaMetadataDetails(reference = item.remoteId ?: item.id, entries = buildList {
                            add(stringResource(R.string.operational_capture_instant) to item.capturedAt.toString())
                            add(stringResource(R.string.operational_display_zone) to java.time.ZoneId.systemDefault().id)
                            item.queuedAt?.let { add(stringResource(R.string.operational_queued_instant) to it.toString()) }
                            item.lastResultAt?.let { add(stringResource(R.string.operational_observed_instant) to it.toString()) }
                            item.serverResult?.let { add(stringResource(R.string.operational_server_instant) to it.at.toString()) }
                            item.errorCode?.let { add(stringResource(R.string.operational_error_code) to it) }
                        })
                        (item.rejection ?: item.lastError)?.let {
                            Text(stringResource(evidenceErrorMessage(it)), color = MaterialTheme.colorScheme.error)
                        }
                        if (item.status == EvidenceStatus.REJECTED) {
                            TextButton(onClick = { discard = item }, enabled = !state.busy) { Text(stringResource(R.string.evidence_remove), textAlign = TextAlign.Center) }
                        }
                    }
                }
                state.progress?.let { (bytes, total) ->
                    val progress = bytes.toFloat() / total
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.evidence_sending, (progress * 100).toInt()),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                if (onRetry == null) Text(stringResource(R.string.evidence_integration_pending))
                else Button(onClick = onRetry, enabled = !state.busy && state.items.any { it.status == EvidenceStatus.PENDING },
                    modifier = Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight)) {
                    Text(stringResource(R.string.evidence_retry), textAlign = TextAlign.Center)
                }
                onAssistance?.let { continueToAssistance ->
                    OutlinedButton(
                        onClick = continueToAssistance,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight).testTag("continue-with-location"),
                    ) {
                        Text(stringResource(R.string.evidence_continue_location), textAlign = TextAlign.Center)
                    }
                }
            }
            EvidenceStep.CAMERA, EvidenceStep.CAPTURING -> camera()
            EvidenceStep.REVIEW -> {
                Text(stringResource(R.string.evidence_review), modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleLarge)
                val draft = checkNotNull(state.draft)
                if (draft.kind == EvidenceKind.VIDEO) {
                    val video = remember(draft) { VideoView(context).apply {
                        setVideoURI(Uri.fromFile(draft.file))
                        setMediaController(MediaController(context).also { it.setAnchorView(this) })
                        setOnPreparedListener { seekTo(1) }
                        setOnErrorListener { _, _, _ -> onError(EvidenceError.FORMAT); true }
                    } }
                    DisposableEffect(video) { onDispose { video.stopPlayback() } }
                    AndroidView(factory = { video }, modifier = Modifier.fillMaxWidth().height(260.dp))
                    Text(stringResource(R.string.evidence_video_silent))
                } else {
                    CropReview(state, onCrop)
                }
                Button(onClick = onAccept, enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight)) {
                    Text(stringResource(R.string.evidence_attach), textAlign = TextAlign.Center)
                }
                TextButton(onClick = { onCamera(state.kind) }, enabled = !state.busy) {
                    Text(stringResource(R.string.evidence_repeat), textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun LocationChoice(model: EvidenceViewModel, state: EvidenceState) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var loading by remember(state.scope) { mutableStateOf(false) }
    fun locate() {
        coroutineScope.launch {
            loading = true
            val location = evidenceLocation(context)
            if (model.state.value.locationEnabled && state.scope == model.state.value.scope) {
                model.location(location)
                if (location == null) model.fail(EvidenceError.LOCATION)
            }
            loading = false
        }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasEvidenceLocationPermission(context)) locate()
        else { model.location(null); model.fail(EvidenceError.LOCATION) }
    }
    EvidenceLocationChoice(state, loading) {
        model.enableLocation(it)
        if (!it) model.location(null)
        else if (hasEvidenceLocationPermission(context)) locate()
        else permissions.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
    }
}

@Composable
internal fun EvidenceLocationChoice(state: EvidenceState, loading: Boolean, onToggle: (Boolean) -> Unit) {
    val label = stringResource(R.string.evidence_location)
    Row(Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight).toggleable(
        value = state.locationEnabled, enabled = !state.busy && !loading, role = Role.Checkbox,
        onValueChange = onToggle).semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(state.locationEnabled, onCheckedChange = null, enabled = !state.busy && !loading)
        Text(label, Modifier.weight(1f).padding(start = 8.dp).clearAndSetSemantics { })
    }
    Text(stringResource(when {
        loading -> R.string.evidence_location_wait
        state.location != null -> R.string.evidence_location_ready
        else -> R.string.evidence_location_none
    }), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun CropReview(state: EvidenceState, apply: (EvidenceCrop) -> Unit) {
    val bitmap = state.preview?.bitmap ?: return
    var crop by remember(bitmap) { mutableStateOf(EvidenceCrop()) }
    Box(Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height)) {
        Image(bitmap.asImageBitmap(), stringResource(R.string.evidence_review), Modifier.matchParentSize())
        Canvas(Modifier.matchParentSize()) {
            val origin = Offset(crop.left * size.width, crop.top * size.height)
            val rect = Size((crop.right - crop.left) * size.width, (crop.bottom - crop.top) * size.height)
            drawRect(Color.Black, origin, rect, style = Stroke(5.dp.toPx()))
            drawRect(Color.White, origin, rect, style = Stroke(2.dp.toPx()))
        }
    }
    CropSlider(R.string.evidence_crop_left, crop.left, 0f..(crop.right - .01f), !state.busy) { crop = crop.copy(left = it) }
    CropSlider(R.string.evidence_crop_top, crop.top, 0f..(crop.bottom - .01f), !state.busy) { crop = crop.copy(top = it) }
    CropSlider(R.string.evidence_crop_right, crop.right, (crop.left + .01f)..1f, !state.busy) { crop = crop.copy(right = it) }
    CropSlider(R.string.evidence_crop_bottom, crop.bottom, (crop.top + .01f)..1f, !state.busy) { crop = crop.copy(bottom = it) }
    OutlinedButton(onClick = { apply(crop) }, enabled = !state.busy && crop != EvidenceCrop(),
        modifier = Modifier.heightIn(min = SolventaLayout.controlHeight)) { Text(stringResource(R.string.evidence_crop), textAlign = TextAlign.Center) }
}

@Composable
private fun CropSlider(label: Int, value: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean, change: (Float) -> Unit) {
    val name = stringResource(label)
    Text(name)
    Slider(value, change, valueRange = range, enabled = enabled, modifier = Modifier.semantics { contentDescription = name })
}

internal fun evidenceKindMessage(kind: EvidenceKind): Int = when (kind) {
    EvidenceKind.PHOTO -> R.string.evidence_kind_photo
    EvidenceKind.DOCUMENT -> R.string.evidence_kind_document
    EvidenceKind.VIDEO -> R.string.evidence_kind_video
}

internal fun evidenceErrorMessage(error: EvidenceError): Int = when (error) {
    EvidenceError.UNAVAILABLE -> R.string.evidence_resource_unavailable
    EvidenceError.INVALID_REQUEST -> R.string.evidence_error_invalid_request
    EvidenceError.CONFLICT -> R.string.sync_error_conflict
    EvidenceError.CAMERA -> R.string.evidence_error_camera
    EvidenceError.PERMISSION -> R.string.evidence_error_permission
    EvidenceError.FORMAT -> R.string.evidence_error_format
    EvidenceError.SIZE -> R.string.evidence_error_size
    EvidenceError.DURATION -> R.string.evidence_error_duration
    EvidenceError.COUNT -> R.string.evidence_error_count
    EvidenceError.STORAGE -> R.string.evidence_error_storage
    EvidenceError.NETWORK -> R.string.evidence_error_network
    EvidenceError.SESSION -> R.string.evidence_error_session
    EvidenceError.LOCATION -> R.string.evidence_error_location
    EvidenceError.PROTOCOL -> R.string.evidence_error_protocol
}

internal fun evidenceQueryErrorMessage(error: EvidenceError, status: Int?): Int = when {
    status != null && status in 500..599 -> R.string.evidence_query_service
    error == EvidenceError.NETWORK -> R.string.evidence_query_network
    error == EvidenceError.PROTOCOL -> R.string.evidence_query_protocol
    else -> evidenceErrorMessage(error)
}
