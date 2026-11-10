package co.solventa.mobile.feature.photo

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import co.solventa.mobile.R
import co.solventa.mobile.core.designsystem.SolventaLayout
import co.solventa.mobile.core.ui.SolventaPage
import co.solventa.mobile.core.ui.SolventaScreenHeader

@Composable
internal fun PhotoReviewScreen(
    state: PhotoState,
    onCamera: () -> Unit,
    onAccept: () -> Unit,
    onClose: () -> Unit,
    onFinished: () -> Unit = onClose,
    onRestart: () -> Unit,
    camera: @Composable () -> Unit,
) {
    var privacy by remember { mutableStateOf(false) }
    val title = stringResource(when (state.step) {
        PhotoStep.REVIEW -> R.string.photo_review
        PhotoStep.PROCESSING -> R.string.photo_processing
        PhotoStep.COMPLETE -> R.string.photo_summary
        else -> if (state.kind == PhotoKind.DOCUMENT) R.string.photo_document_title else R.string.photo_selfie_title
    })
    SolventaPage(headerAlignment = Alignment.Start, header = {
        if (state.step == PhotoStep.COMPLETE) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClose, modifier = Modifier.heightIn(min = SolventaLayout.controlHeight)) {
                    Text(stringResource(R.string.photo_cancel), textAlign = TextAlign.Center)
                }
                Text(title, Modifier.fillMaxWidth().semantics { heading() }, textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.headlineMedium)
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SolventaScreenHeader(title, stringResource(R.string.photo_cancel), onClose)
                Text(stringResource(if (state.kind == PhotoKind.DOCUMENT)
                    R.string.photo_document_step else R.string.photo_selfie_step),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
    }, actions = {
        when (state.step) {
            PhotoStep.INTRO -> PhotoButton(R.string.photo_start, onCamera)
            PhotoStep.REVIEW -> {
                PhotoButton(R.string.photo_accept, onAccept, state.preview != null)
                OutlinedButton(onCamera, modifier = Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight),
                    shape = SolventaLayout.controlShape) { Text(stringResource(R.string.photo_repeat), textAlign = TextAlign.Center) }
            }
            PhotoStep.COMPLETE -> PhotoButton(
                if (state.actorId == null) R.string.photo_back_login else R.string.photo_back_account, onFinished)
            else -> Unit
        }
        if (state.documentReceived && state.step in listOf(PhotoStep.INTRO, PhotoStep.COMPLETE)) {
            TextButton(onRestart, modifier = Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight)) {
                Text(stringResource(R.string.photo_restart_document), textAlign = TextAlign.Center)
            }
        }
    }) {
        state.error?.let {
            Surface(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive },
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.medium) {
                Text(stringResource(it), Modifier.padding(16.dp))
            }
        }
        when (state.step) {
            PhotoStep.INTRO -> {
                PhotoInformation {
                    Text(stringResource(if (state.kind == PhotoKind.DOCUMENT)
                        R.string.photo_document_guide else R.string.photo_guide),
                        style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.photo_privacy),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.kind == PhotoKind.SELFIE) {
                        Text(stringResource(R.string.selfie_privacy_note),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton({ privacy = true }, Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight)) {
                            Text(stringResource(R.string.selfie_privacy_details), textAlign = TextAlign.Center)
                        }
                    }
                }
            }
            PhotoStep.PERMISSION, PhotoStep.PROCESSING -> {
                val description = stringResource(
                    if (state.step == PhotoStep.PROCESSING) R.string.photo_processing
                    else if (state.kind == PhotoKind.DOCUMENT) R.string.photo_document_preparing
                    else R.string.selfie_preparing,
                )
                Box(Modifier.fillMaxWidth().heightIn(min = 80.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                        stateDescription = description
                    })
                }
            }
            PhotoStep.CAMERA, PhotoStep.CAPTURING -> camera()
            PhotoStep.REVIEW -> state.preview?.let {
                Image(it.asImageBitmap(), stringResource(if (state.kind == PhotoKind.DOCUMENT)
                    R.string.photo_document_description else R.string.photo_description),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).clip(MaterialTheme.shapes.medium),
                    contentScale = ContentScale.Fit)
            }
            PhotoStep.COMPLETE -> {
                PhotoInformation {
                    Text(stringResource(R.string.photo_document_received), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.photo_selfie_received), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.photo_complete_body))
                }
                Text(stringResource(R.string.photo_local_limit), Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (privacy) AlertDialog(onDismissRequest = { privacy = false },
        title = { Text(stringResource(R.string.selfie_privacy_title), Modifier.semantics { heading() }) },
        text = { Text(stringResource(R.string.selfie_privacy_body), Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = {
            TextButton({ privacy = false }, Modifier.heightIn(min = SolventaLayout.controlHeight)) {
                Text(stringResource(R.string.selfie_privacy_close), textAlign = TextAlign.Center)
            }
        })
}

@Composable
private fun PhotoInformation(content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

@Composable
internal fun PhotoButton(label: Int, onClick: () -> Unit, enabled: Boolean = true) {
    Button(onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight),
        shape = SolventaLayout.controlShape) { Text(stringResource(label), textAlign = TextAlign.Center) }
}
