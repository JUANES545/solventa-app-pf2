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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import co.solventa.mobile.R
import co.solventa.mobile.core.ui.SolventaPage
import kotlinx.coroutines.launch

@Composable
internal fun EvidenceScreen(
    model: EvidenceViewModel,
    onClose: () -> Unit,
    onRetry: (() -> Unit)? = null,
    camera: @Composable (EvidenceViewModel, EvidenceState) -> Unit = { vm, state -> EvidenceCamera(vm, state) },
) {
    val state by model.state.collectAsState()
    val context = LocalContext.current
    var discard by remember(state.scope) { mutableStateOf<EvidenceItem?>(null) }
    if (!state.open) return
    discard?.let { item ->
        AlertDialog(onDismissRequest = { discard = null },
            title = { Text(stringResource(R.string.evidence_remove)) },
            text = { Text(stringResource(R.string.evidence_remove_info)) },
            confirmButton = { TextButton(onClick = { discard = null; model.removeRejected(item) }) { Text(stringResource(R.string.evidence_remove)) } },
            dismissButton = { TextButton(onClick = { discard = null }) { Text(stringResource(R.string.evidence_back)) } })
    }
    BackHandler { if (!state.busy) { if (state.step == EvidenceStep.LIST) onClose() else model.back() } }
    SolventaPage(header = {
        Text(stringResource(R.string.evidence_title), style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() })
    }, actions = {
        TextButton(onClick = { if (state.step == EvidenceStep.LIST) onClose() else model.back() }, enabled = !state.busy) {
            Text(stringResource(R.string.evidence_back))
        }
    }) {
        state.error?.let { error ->
            Text(stringResource(evidenceErrorMessage(error)), color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
        }
        if (state.busy) CircularProgressIndicator()
        if (state.scope == null) {
            Text(stringResource(R.string.evidence_choose_claim))
            if (!state.busy && state.claims.isEmpty() && state.error == null) Text(stringResource(R.string.evidence_no_claims))
            for (claim in state.claims) {
                Button(onClick = { model.select(claim) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("claim-${claim.id}")) {
                    Text("${claim.type} · ${claim.date}\n${claim.description}\n${stringResource(R.string.evidence_claim_reference, claim.id)}")
                }
            }
            if (state.error != null) TextButton(onClick = { state.actorId?.let(model::showClaims) }, enabled = !state.busy) {
                Text(stringResource(R.string.evidence_reload))
            }
            return@SolventaPage
        }
        when (state.step) {
            EvidenceStep.LIST -> {
                val policy = checkNotNull(state.policy)
                Text(stringResource(R.string.evidence_limit, policy.maxCount))
                for ((kind, bytes) in policy.maxBytes) {
                    Text(stringResource(R.string.evidence_format_limit, stringResource(evidenceKindMessage(kind)), bytes))
                    Button(onClick = { model.camera(kind) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(when (kind) {
                            EvidenceKind.PHOTO -> R.string.evidence_photo
                            EvidenceKind.DOCUMENT -> R.string.evidence_document
                            EvidenceKind.VIDEO -> R.string.evidence_video
                        }))
                    }
                }
                if (EvidenceKind.VIDEO in policy.maxBytes) Text(stringResource(R.string.evidence_video_limit, policy.maxVideoDurationMillis / 1000))
                LocationChoice(model, state)
                if (state.items.isEmpty()) Text(stringResource(R.string.evidence_empty))
                for (item in state.items) {
                    Text(stringResource(R.string.evidence_item, stringResource(evidenceKindMessage(item.kind)),
                        stringResource(when (item.status) {
                            EvidenceStatus.CONFIRMED -> R.string.evidence_confirmed
                            EvidenceStatus.REJECTED -> R.string.evidence_rejected
                            EvidenceStatus.CONFLICT -> R.string.sync_conflict
                            EvidenceStatus.PENDING -> if (item.lastError == null) R.string.evidence_pending else R.string.evidence_failed
                        })), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    Text(item.capturedAt.toString(), style = MaterialTheme.typography.bodySmall)
                    item.queuedAt?.let { Text(stringResource(R.string.sync_queued_at, it.toString()), style = MaterialTheme.typography.bodySmall) }
                    item.lastResultAt?.let { Text(stringResource(R.string.sync_result_at, it.toString()), style = MaterialTheme.typography.bodySmall) }
                    item.serverResult?.let { Text(stringResource(R.string.sync_server_result_at, it.at.toString()), style = MaterialTheme.typography.bodySmall) }
                    (item.rejection ?: item.lastError)?.let {
                        Text(stringResource(evidenceErrorMessage(it)), color = MaterialTheme.colorScheme.error)
                    }
                    if (item.status == EvidenceStatus.REJECTED) {
                        TextButton(onClick = { discard = item }, enabled = !state.busy) { Text(stringResource(R.string.evidence_remove)) }
                    }
                }
                state.progress?.let { (bytes, total) ->
                    val progress = bytes.toFloat() / total
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.evidence_sending, (progress * 100).toInt()),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                if (onRetry == null) Text(stringResource(R.string.evidence_integration_pending))
                else Button(onClick = onRetry, enabled = !state.busy && state.items.any { it.status == EvidenceStatus.PENDING }) {
                    Text(stringResource(R.string.evidence_retry))
                }
            }
            EvidenceStep.CAMERA, EvidenceStep.CAPTURING -> camera(model, state)
            EvidenceStep.REVIEW -> {
                Text(stringResource(R.string.evidence_review), modifier = Modifier.semantics { heading() })
                val draft = checkNotNull(state.draft)
                if (draft.kind == EvidenceKind.VIDEO) {
                    val video = remember(draft) { VideoView(context).apply {
                        setVideoURI(Uri.fromFile(draft.file))
                        setMediaController(MediaController(context).also { it.setAnchorView(this) })
                        setOnPreparedListener { seekTo(1) }
                        setOnErrorListener { _, _, _ -> model.fail(EvidenceError.FORMAT); true }
                    } }
                    DisposableEffect(video) { onDispose { video.stopPlayback() } }
                    AndroidView(factory = { video }, modifier = Modifier.fillMaxWidth().height(260.dp))
                    Text(stringResource(R.string.evidence_video_silent))
                } else {
                    CropReview(state, model::crop)
                }
                Button(onClick = { model.accept(hasEvidenceLocationPermission(context)) }, enabled = !state.busy) {
                    Text(stringResource(R.string.evidence_attach))
                }
                TextButton(onClick = { model.camera(state.kind) }, enabled = !state.busy) {
                    Text(stringResource(R.string.evidence_repeat))
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
    Row {
        val label = stringResource(R.string.evidence_location)
        Checkbox(state.locationEnabled, onCheckedChange = {
            model.enableLocation(it)
            if (!it) model.location(null)
            else if (hasEvidenceLocationPermission(context)) locate()
            else permissions.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
        }, enabled = !state.busy && !loading, modifier = Modifier.semantics { contentDescription = label })
        Text(label)
    }
    Text(stringResource(when {
        loading -> R.string.evidence_location_wait
        state.location != null -> R.string.evidence_location_ready
        else -> R.string.evidence_location_none
    }))
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
    Button(onClick = { apply(crop) }, enabled = !state.busy && crop != EvidenceCrop()) { Text(stringResource(R.string.evidence_crop)) }
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
