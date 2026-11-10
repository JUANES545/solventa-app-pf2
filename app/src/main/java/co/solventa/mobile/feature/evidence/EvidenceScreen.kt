package co.solventa.mobile.feature.evidence

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import co.solventa.mobile.R
import co.solventa.mobile.core.designsystem.SolventaLayout
import co.solventa.mobile.core.location.LocationPermission
import co.solventa.mobile.core.ui.SolventaPage
import co.solventa.mobile.core.ui.SolventaActionRow
import co.solventa.mobile.feature.claim.ClaimCard
import co.solventa.mobile.core.ui.SolventaRefreshing
import co.solventa.mobile.core.ui.SolventaScreenHeader
import co.solventa.mobile.core.ui.SolventaSkeletonList
import co.solventa.mobile.core.ui.SolventaLoadError
import co.solventa.mobile.core.ui.SolventaEmptyState
import kotlinx.coroutines.Job
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
    var importScope by remember { mutableStateOf<Triple<EvidenceScope, Long, EvidenceKind>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val expected = importScope
        importScope = null
        if (uri != null && expected != null) model.importFile(uri, expected.third, expected.first, expected.second)
    }
    EvidenceContent(
        state = state,
        onClose = onClose,
        onBack = model::back,
        onSelect = model::select,
        onReload = model::refresh,
        onCamera = model::camera,
        onRemove = model::remove,
        onCrop = model::crop,
        onAccept = { model.accept(hasEvidenceLocationPermission(context)) },
        onError = model::fail,
        onRetry = onRetry,
        onAssistance = onAssistance,
        camera = { camera(model, state) },
        locationChoice = { LocationChoice(model, state) },
        onChooseFile = { kind ->
            state.scope?.let { importScope = Triple(it, state.generation, kind); picker.launch(kind.mime) }
        },
        onRemoveRemote = model::removeDemoConfirmed,
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
    onChoosePhoto: (() -> Unit)? = null,
    feedback: (@Composable () -> Unit)? = null,
    onChooseFile: ((EvidenceKind) -> Unit)? = null,
    onRemoveRemote: (String) -> Unit = {},
) {
    var sourceKind by remember(state.scope, state.generation) { mutableStateOf<EvidenceKind?>(null) }
    if (!state.open) return
    sourceKind?.let { kind ->
        AlertDialog(onDismissRequest = { sourceKind = null },
            title = { Text(stringResource(evidenceKindMessage(kind))) },
            text = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { sourceKind = null; onCamera(kind) }, enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight).testTag("evidence-source-camera")) {
                        Text(stringResource(R.string.evidence_gallery_capture))
                    }
                    if (onChooseFile != null || kind == EvidenceKind.PHOTO && onChoosePhoto != null) {
                        OutlinedButton(onClick = {
                            sourceKind = null
                            onChooseFile?.invoke(kind) ?: onChoosePhoto?.invoke()
                        }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight)
                            .testTag("evidence-source-file")) { Text(stringResource(R.string.evidence_gallery_choose)) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { sourceKind = null }) { Text(stringResource(R.string.evidence_gallery_cancel)) } })
    }
    BackHandler { if (!state.busy) { if (state.step == EvidenceStep.LIST) onClose() else onBack() } }
    SolventaPage(headerAlignment = Alignment.Start, feedback = feedback,
        onRefresh = onReload, isRefreshing = state.busy,
        refreshEnabled = !state.busy && state.step == EvidenceStep.LIST,
        header = {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { if (state.step == EvidenceStep.LIST) onClose() else onBack() }, enabled = !state.busy,
                modifier = Modifier.heightIn(min = SolventaLayout.controlHeight)) {
                Icon(painterResource(R.drawable.arrow_back), null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.evidence_back), textAlign = TextAlign.Center)
            }
            SolventaRefreshing(state.busy && !state.loadingClaims) {
                SolventaScreenHeader(stringResource(R.string.evidence_title))
            }
        }
    }) {
        val initialLoadError = state.scope == null && state.claims.isEmpty() && state.error != null
        state.error?.takeUnless { initialLoadError || it == EvidenceError.LOCATION }?.let { error ->
            val message = if (state.scope == null) evidenceQueryErrorMessage(error, state.errorHttpStatus)
                else evidenceErrorMessage(error)
            Text(stringResource(message), color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
        }
        if (state.scope == null) {
            if (state.loadingClaims && state.claims.isEmpty()) SolventaSkeletonList(stringResource(R.string.operational_loading_claims))
            else if (initialLoadError) SolventaLoadError(stringResource(R.string.ui_load_claims_error),
                stringResource(evidenceQueryErrorMessage(checkNotNull(state.error), state.errorHttpStatus)))
            if (!state.busy && state.claims.isEmpty() && state.error == null) SolventaEmptyState(stringResource(R.string.evidence_no_claims))
            for (claim in state.claims) {
                ClaimCard(claim, state.actorId, state.busy, state.claims) { onSelect(claim) }
            }
            return@SolventaPage
        }
        when (state.step) {
            EvidenceStep.LIST -> {
                val policy = checkNotNull(state.policy)
                EvidenceGallery(state, onRemove, onRemoveRemote)
                Text(stringResource(R.string.evidence_gallery_add), Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleLarge)
                for (kind in listOf(EvidenceKind.PHOTO, EvidenceKind.VIDEO, EvidenceKind.DOCUMENT).filter { it in policy.maxBytes }) {
                    SolventaActionRow(
                        modifier = Modifier.testTag(when (kind) {
                            EvidenceKind.PHOTO -> "evidence-add-photo"
                            EvidenceKind.VIDEO -> "evidence-add-video"
                            EvidenceKind.DOCUMENT -> "evidence-add-document"
                        }),
                        title = stringResource(when (kind) {
                            EvidenceKind.PHOTO -> R.string.evidence_gallery_photo
                            EvidenceKind.DOCUMENT -> R.string.evidence_gallery_document
                            EvidenceKind.VIDEO -> R.string.evidence_gallery_video
                        }),
                        onClick = { sourceKind = kind }, enabled = !state.busy,
                    )
                }
                state.progress?.let { (bytes, total) ->
                    val progress = bytes.toFloat() / total
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.evidence_gallery_sending, (progress * 100).toInt()),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                if (onRetry != null && state.items.any { it.status == EvidenceStatus.PENDING }) Button(
                    onClick = onRetry, enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight)) {
                    Text(stringResource(R.string.evidence_retry), textAlign = TextAlign.Center)
                }
            }
            EvidenceStep.CAMERA, EvidenceStep.CAPTURING, EvidenceStep.REVIEW -> {
                var cropping by remember(state.draft, state.preview?.bitmap) { mutableStateOf(false) }
                if (state.step == EvidenceStep.REVIEW) {
                    Text(stringResource(R.string.evidence_gallery_review), modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.titleLarge)
                    val draft = checkNotNull(state.draft)
                    if (draft.kind == EvidenceKind.VIDEO) {
                        EvidenceVideoPreview(draft.file) { onError(EvidenceError.FORMAT) }
                        Text(stringResource(R.string.evidence_video_silent))
                    } else CropReview(state, onCrop, cropping) { cropping = it }
                } else camera()
                locationChoice()
                if (state.step == EvidenceStep.REVIEW) {
                    Button(onClick = onAccept, enabled = !state.busy && !cropping,
                        modifier = Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight)) {
                        Text(stringResource(R.string.evidence_gallery_attach), textAlign = TextAlign.Center)
                    }
                    TextButton(onClick = { sourceKind = state.kind }, enabled = !state.busy) {
                        Text(stringResource(R.string.evidence_gallery_replace), textAlign = TextAlign.Center)
                    }
                } else onAssistance?.takeIf {
                    state.step == EvidenceStep.CAMERA && state.locationEnabled && state.location != null
                }?.let { continueToAssistance ->
                    OutlinedButton(
                        onClick = continueToAssistance,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight).testTag("continue-with-location"),
                    ) {
                        Text(stringResource(R.string.evidence_continue_location), textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

@Composable
internal fun LocationChoice(
    model: EvidenceViewModel,
    state: EvidenceState,
    locateDevice: suspend (Context) -> EvidenceLocation? = ::evidenceLocation,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var loading by remember(state.scope, state.generation) { mutableStateOf(false) }
    var requesting by remember(state.scope, state.generation) { mutableStateOf(false) }
    var locationJob by remember(state.scope, state.generation) { mutableStateOf<Job?>(null) }
    DisposableEffect(state.scope, state.generation) {
        onDispose { locationJob?.cancel() }
    }
    fun current(): Boolean {
        val latest = model.state.value
        return state.scope != null && latest.open && latest.scope == state.scope &&
            latest.generation == state.generation &&
            latest.step in setOf(EvidenceStep.CAMERA, EvidenceStep.CAPTURING, EvidenceStep.REVIEW)
    }
    fun locate() {
        loading = true
        locationJob = coroutineScope.launch {
            try {
                val location = locateDevice(context)
                if (current()) {
                    if (location == null || !hasEvidenceLocationPermission(context)) {
                        model.enableLocation(false)
                        model.fail(EvidenceError.LOCATION)
                    } else {
                        model.enableLocation(true)
                        model.location(location)
                    }
                }
            } finally {
                loading = false
            }
        }
    }
    EvidenceLocationChoice(state, loading) {
        if (!requesting) {
            model.dismissLocationError()
            if (it) requesting = true else model.enableLocation(false)
        }
    }
    if (requesting) LocationPermission(
        onGranted = {
            requesting = false
            if (current()) {
                locate()
            }
        },
        onCancel = {
            requesting = false
            if (current()) model.enableLocation(false)
        },
    )
}

@Composable
internal fun EvidenceLocationChoice(state: EvidenceState, loading: Boolean, onToggle: (Boolean) -> Unit) {
    val label = stringResource(R.string.evidence_location)
    val checked = state.locationEnabled || loading
    Row(Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight).toggleable(
        value = checked, enabled = !state.busy && !loading, role = Role.Checkbox,
        onValueChange = onToggle).semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = null, enabled = !state.busy && !loading)
        Text(label, Modifier.weight(1f).padding(start = 8.dp).clearAndSetSemantics { })
    }
    if (loading || state.location != null && state.error != EvidenceError.LOCATION) Text(stringResource(
        if (loading) R.string.evidence_location_wait else R.string.evidence_location_ready),
        Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium)
    if (!loading && state.error == EvidenceError.LOCATION) {
        Text(stringResource(R.string.evidence_error_location),
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { onToggle(true) }, enabled = !state.busy,
            modifier = Modifier.heightIn(min = SolventaLayout.controlHeight)) {
            Text(stringResource(R.string.evidence_location_retry))
        }
    }
}

@Composable
private fun CropReview(state: EvidenceState, apply: (EvidenceCrop) -> Unit, editing: Boolean, edit: (Boolean) -> Unit) {
    val bitmap = state.preview?.bitmap ?: return
    var crop by remember(bitmap) { mutableStateOf(EvidenceCrop()) }
    Box(Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height)) {
        Image(bitmap.asImageBitmap(), stringResource(R.string.evidence_review), Modifier.matchParentSize())
        if (editing) Canvas(Modifier.matchParentSize()) {
            val origin = Offset(crop.left * size.width, crop.top * size.height)
            val rect = Size((crop.right - crop.left) * size.width, (crop.bottom - crop.top) * size.height)
            drawRect(Color.Black, origin, rect, style = Stroke(5.dp.toPx()))
            drawRect(Color.White, origin, rect, style = Stroke(2.dp.toPx()))
        }
    }
    TextButton(onClick = { crop = EvidenceCrop(); edit(!editing) }, enabled = !state.busy,
        modifier = Modifier.heightIn(min = SolventaLayout.controlHeight)) {
        Text(stringResource(if (editing) R.string.evidence_cancel_crop else R.string.evidence_edit_crop))
    }
    if (!editing) return
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
    EvidenceError.FORMAT -> R.string.evidence_gallery_invalid
    EvidenceError.SIZE -> R.string.evidence_gallery_size_error
    EvidenceError.DURATION -> R.string.evidence_gallery_duration_error
    EvidenceError.COUNT -> R.string.evidence_gallery_count_error
    EvidenceError.STORAGE -> R.string.evidence_gallery_storage_error
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
