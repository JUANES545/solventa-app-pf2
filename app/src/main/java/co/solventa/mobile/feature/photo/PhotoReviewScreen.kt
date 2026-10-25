package co.solventa.mobile.feature.photo

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import co.solventa.mobile.R
import co.solventa.mobile.core.designsystem.SolventaLayout
import co.solventa.mobile.core.ui.SolventaPage

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
    SolventaPage(header = {
        TextButton(onClose, modifier = Modifier.heightIn(min = SolventaLayout.controlHeight)) {
            Text(stringResource(R.string.photo_cancel))
        }
        if (state.step != PhotoStep.COMPLETE) Text(stringResource(
            if (state.kind == PhotoKind.DOCUMENT) R.string.photo_document_step else R.string.photo_selfie_step),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(when (state.step) {
            PhotoStep.REVIEW -> R.string.photo_review
            PhotoStep.PROCESSING -> R.string.photo_processing
            PhotoStep.COMPLETE -> R.string.photo_summary
            else -> if (state.kind == PhotoKind.DOCUMENT) R.string.photo_document_title else R.string.photo_selfie_title
        }), Modifier.fillMaxWidth().semantics { heading() }, textAlign = TextAlign.Center,
            style = MaterialTheme.typography.headlineMedium)
    }, actions = {
        when (state.step) {
            PhotoStep.INTRO -> PhotoButton(R.string.photo_start, onCamera)
            PhotoStep.REVIEW -> {
                PhotoButton(R.string.photo_accept, onAccept, state.preview != null)
                OutlinedButton(onCamera, modifier = Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight),
                    shape = SolventaLayout.controlShape) { Text(stringResource(R.string.photo_repeat)) }
            }
            PhotoStep.COMPLETE -> PhotoButton(
                if (state.actorId == null) R.string.photo_back_login else R.string.photo_back_account, onFinished)
            else -> Unit
        }
        if (state.documentReceived && state.step in listOf(PhotoStep.INTRO, PhotoStep.COMPLETE)) {
            TextButton(onRestart, modifier = Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight)) {
                Text(stringResource(R.string.photo_restart_document))
            }
        }
    }) {
        state.error?.let {
            Text(stringResource(it), color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
        }
        when (state.step) {
            PhotoStep.INTRO -> {
                Text(stringResource(if (state.kind == PhotoKind.DOCUMENT) R.string.photo_document_guide else R.string.photo_guide),
                    Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                Text(stringResource(R.string.photo_privacy), Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.kind == PhotoKind.SELFIE) {
                    Text(stringResource(R.string.selfie_privacy_note), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    TextButton({ privacy = true }, Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight)) {
                        Text(stringResource(R.string.selfie_privacy_details))
                    }
                }
            }
            PhotoStep.PERMISSION, PhotoStep.PROCESSING -> CircularProgressIndicator()
            PhotoStep.CAMERA, PhotoStep.CAPTURING -> camera()
            PhotoStep.REVIEW -> state.preview?.let {
                Image(it.asImageBitmap(), stringResource(if (state.kind == PhotoKind.DOCUMENT)
                    R.string.photo_document_description else R.string.photo_description),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp), contentScale = ContentScale.Fit)
            }
            PhotoStep.COMPLETE -> {
                Text(stringResource(R.string.photo_document_received), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                Text(stringResource(R.string.photo_selfie_received), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                Text(stringResource(R.string.photo_complete_body), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                Text(stringResource(R.string.photo_local_limit), Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (privacy) AlertDialog(onDismissRequest = { privacy = false },
        title = { Text(stringResource(R.string.selfie_privacy_title)) },
        text = { Text(stringResource(R.string.selfie_privacy_body), Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = {
            TextButton({ privacy = false }, Modifier.heightIn(min = SolventaLayout.controlHeight)) {
                Text(stringResource(R.string.selfie_privacy_close))
            }
        })
}

@Composable
internal fun PhotoButton(label: Int, onClick: () -> Unit, enabled: Boolean = true) {
    Button(onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight),
        shape = SolventaLayout.controlShape) { Text(stringResource(label)) }
}
