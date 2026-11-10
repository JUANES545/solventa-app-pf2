package co.solventa.mobile.feature.evidence

import android.net.Uri
import android.text.format.Formatter
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import co.solventa.mobile.BuildConfig
import co.solventa.mobile.R
import co.solventa.mobile.feature.claim.claimStatusDate
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun evidenceGalleryStatus(item: EvidenceItem): Int = when (item.status) {
    EvidenceStatus.CONFIRMED -> R.string.evidence_gallery_saved
    EvidenceStatus.REJECTED -> R.string.evidence_gallery_rejected
    EvidenceStatus.CONFLICT -> R.string.sync_conflict
    EvidenceStatus.PENDING -> if (item.lastError == null) R.string.evidence_gallery_pending else R.string.evidence_gallery_failed
}

internal fun evidenceCanRemove(item: EvidenceItem): Boolean =
    item.status in setOf(EvidenceStatus.PENDING, EvidenceStatus.REJECTED) ||
        item.status == EvidenceStatus.CONFIRMED && BuildConfig.BUILD_TYPE == "mock"

private data class GalleryEntry(val id: String, val item: EvidenceItem? = null)

@Composable
internal fun EvidenceGallery(
    state: EvidenceState,
    onRemove: (EvidenceItem) -> Unit,
    onRemoveRemote: (String) -> Unit,
) {
    var selected by remember(state.scope, state.generation) { mutableStateOf<GalleryEntry?>(null) }
    val entries = state.items.map { GalleryEntry(it.id, it) } +
        (state.confirmedRemoteIds - state.items.mapNotNull { it.remoteId }.toSet()).map { GalleryEntry(it) }
    Text(stringResource(R.string.evidence_gallery_title), Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleLarge)
    if (entries.isEmpty()) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
            Text(stringResource(R.string.evidence_gallery_empty), Modifier.fillMaxWidth().padding(20.dp))
        }
    }
    for (row in entries.chunked(2)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (entry in row) {
                val item = entry.item
                val name = stringResource(item?.let { evidenceKindMessage(it.kind) } ?: R.string.evidence_gallery_remote)
                Card(onClick = { selected = entry }, enabled = !state.busy,
                    modifier = Modifier.weight(1f).testTag("evidence-tile-${entry.id}").semantics { contentDescription = name },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    if (item != null) {
                        Box {
                            EvidenceThumbnail(state.scope, item, Modifier.clearAndSetSemantics { })
                            if (item.kind != EvidenceKind.PHOTO) Surface(
                                Modifier.align(Alignment.BottomStart).padding(8.dp).testTag("evidence-kind-badge"),
                                color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.small,
                            ) {
                                Icon(painterResource(when (item.kind) {
                                    EvidenceKind.PHOTO -> R.drawable.evidence_photo
                                    EvidenceKind.VIDEO -> R.drawable.evidence_video
                                    EvidenceKind.DOCUMENT -> R.drawable.evidence_document
                                }), null, Modifier.padding(8.dp).size(20.dp))
                            }
                        }
                    } else Box(Modifier.fillMaxWidth().height(144.dp), contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_evidence), null, Modifier.size(32.dp))
                    }
                    if (item != null && item.status != EvidenceStatus.CONFIRMED) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(evidenceGalleryStatus(item)),
                            style = MaterialTheme.typography.bodySmall)
                            (item.rejection ?: item.lastError)?.let { error ->
                                Text(stringResource(evidenceErrorMessage(error)), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
    selected?.let { entry ->
        EvidenceGalleryPreview(entry, onDismiss = { selected = null }, canRemove = !state.busy &&
            (entry.item?.let(::evidenceCanRemove) ?: (BuildConfig.BUILD_TYPE == "mock")),
            onRemove = {
                selected = null
                entry.item?.let(onRemove) ?: onRemoveRemote(entry.id)
            })
    }
}

private data class StoredPreview(val file: File? = null, val bitmap: android.graphics.Bitmap? = null, val loading: Boolean = true)

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun EvidenceGalleryPreview(entry: GalleryEntry, onDismiss: () -> Unit, canRemove: Boolean, onRemove: () -> Unit) {
    val context = LocalContext.current
    val item = entry.item
    var deleting by remember(entry.id) { mutableStateOf(false) }
    val preview by produceState(StoredPreview(), entry) {
        val loaded = if (item == null) StoredPreview(loading = false) else withContext(Dispatchers.IO) {
            try {
                EvidenceStore(context.noBackupFilesDir).use { store ->
                    val file = store.file(item)
                    StoredPreview(file, if (item.kind == EvidenceKind.VIDEO) null
                        else EvidenceMedia(context.cacheDir).image(file, 1600), loading = false)
                }
            } catch (_: java.io.IOException) { StoredPreview(loading = false) }
            catch (_: android.database.sqlite.SQLiteException) { StoredPreview(loading = false) }
            catch (_: SecurityException) { StoredPreview(loading = false) }
            catch (_: IllegalArgumentException) { StoredPreview(loading = false) }
        }
        withContext(Dispatchers.Main.immediate) { value = loaded }
    }
    val image = preview.bitmap
    DisposableEffect(image) { onDispose { image?.recycle() } }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(item?.let { evidenceKindMessage(it.kind) } ?: R.string.evidence_gallery_remote),
                    style = MaterialTheme.typography.titleLarge)
                val bitmap = preview.bitmap
                val file = preview.file
                when {
                    preview.loading -> Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    file != null && item?.kind == EvidenceKind.VIDEO -> EvidenceVideoPreview(file)
                    bitmap != null -> Image(bitmap.asImageBitmap(), stringResource(R.string.evidence_local_preview),
                        Modifier.fillMaxWidth().heightIn(max = 360.dp).aspectRatio(bitmap.width.toFloat() / bitmap.height),
                        contentScale = ContentScale.Fit)
                    else -> Box(Modifier.fillMaxWidth().height(180.dp).background(MaterialTheme.colorScheme.surfaceContainerLow),
                        contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.evidence_preview_unavailable), Modifier.padding(16.dp))
                    }
                }
                item?.let {
                    Text(stringResource(R.string.evidence_gallery_date, claimStatusDate(it.capturedAt)))
                    Text(stringResource(R.string.evidence_gallery_size, Formatter.formatShortFileSize(context, it.bytes)))
                    if (it.kind == EvidenceKind.VIDEO) Text(stringResource(R.string.evidence_gallery_duration, it.durationMillis / 1000))
                    Text(stringResource(evidenceGalleryStatus(it)))
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (canRemove) TextButton(onClick = { deleting = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.evidence_gallery_remove), color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.evidence_gallery_close))
                    }
                }
            }
        }
    }
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false },
        title = { Text(stringResource(R.string.evidence_gallery_remove_question)) },
        text = { Text(stringResource(R.string.evidence_gallery_remove_info)) },
        confirmButton = { TextButton(onClick = onRemove) { Text(stringResource(R.string.evidence_gallery_remove)) } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.evidence_gallery_cancel)) } },
    )
}

@Composable
internal fun EvidenceVideoPreview(file: File, onError: () -> Unit = {}) {
    val context = LocalContext.current
    var failed by remember(file) { mutableStateOf(false) }
    val video = remember(file) { VideoView(context).apply {
        setVideoURI(Uri.fromFile(file))
        setMediaController(MediaController(context).also { it.setAnchorView(this) })
        setOnPreparedListener { it.setVolume(0f, 0f); seekTo(1) }
        setOnErrorListener { _, _, _ -> failed = true; onError(); true }
    } }
    DisposableEffect(video) { onDispose { video.stopPlayback() } }
    if (failed) Text(stringResource(R.string.evidence_preview_unavailable), Modifier.padding(16.dp))
    else AndroidView(factory = { video }, modifier = Modifier.fillMaxWidth().height(260.dp))
}
