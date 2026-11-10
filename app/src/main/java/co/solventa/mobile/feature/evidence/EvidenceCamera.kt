package co.solventa.mobile.feature.evidence

import android.Manifest
import android.content.pm.PackageManager
import androidx.camera.core.CameraInfoUnavailableException
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import co.solventa.mobile.R
import co.solventa.mobile.core.designsystem.SolventaLayout
import co.solventa.mobile.core.ui.CameraPermission
import java.util.concurrent.ExecutionException

@Composable
internal fun EvidenceCamera(model: EvidenceViewModel, state: EvidenceState) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val generation = state.generation
    val policy = checkNotNull(state.policy)
    var permitted by remember(generation) {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    if (!permitted) {
        CameraPermission(generation, onGranted = { permitted = true }, onCancel = {
            if (model.state.value.generation == generation) model.back()
        })
        return
    }

    val video = state.kind == EvidenceKind.VIDEO
    val view = remember(generation) { PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FIT_CENTER
    } }
    val preview = remember(generation) { Preview.Builder().build() }
    val photo = remember(generation) { ImageCapture.Builder().setResolutionSelector(
        ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy(android.util.Size(1920, 1440),
            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build()).build() }
    val recorder = remember(generation) { Recorder.Builder()
        .setQualitySelector(QualitySelector.fromOrderedList(listOf(Quality.HD, Quality.SD))).build() }
    val capture = remember(generation) { VideoCapture.withOutput(recorder) }
    var recording by remember(generation) { mutableStateOf<Recording?>(null) }
    var ready by remember(generation) { mutableStateOf(false) }
    var front by remember(generation) { mutableStateOf(false) }
    var canSwitch by remember(generation) { mutableStateOf(false) }
    var seconds by remember(generation) { mutableLongStateOf(0) }
    val executor = ContextCompat.getMainExecutor(context)
    DisposableEffect(generation, owner, front) {
        ready = false
        var disposed = false
        var provider: ProcessCameraProvider? = null
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!disposed) try {
                val cameraProvider = future.get()
                provider = cameraProvider
                val hasBack = cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
                val hasFront = cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                canSwitch = hasBack && hasFront
                val selector = when {
                    front && hasFront -> CameraSelector.DEFAULT_FRONT_CAMERA
                    hasBack -> CameraSelector.DEFAULT_BACK_CAMERA
                    hasFront -> CameraSelector.DEFAULT_FRONT_CAMERA
                    else -> {
                        model.cameraFailed(null, generation)
                        return@addListener
                    }
                }
                preview.setSurfaceProvider(view.surfaceProvider)
                if (video) cameraProvider.bindToLifecycle(owner, selector, preview, capture)
                else cameraProvider.bindToLifecycle(owner, selector, preview, photo)
                ready = true
            } catch (_: CameraInfoUnavailableException) {
                model.cameraFailed(null, generation)
            } catch (_: ExecutionException) {
                model.cameraFailed(null, generation)
            } catch (_: SecurityException) {
                model.cameraFailed(null, generation, EvidenceError.PERMISSION)
            } catch (_: IllegalArgumentException) {
                model.cameraFailed(null, generation)
            } catch (_: IllegalStateException) {
                model.cameraFailed(null, generation)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                model.cameraFailed(null, generation)
            }
        }, executor)
        onDispose {
            disposed = true
            recording?.close()
            recording = null
            provider?.unbind(preview, photo, capture)
        }
    }
    Box(Modifier.fillMaxWidth().height(300.dp)) {
        AndroidView(factory = { view }, modifier = Modifier.matchParentSize())
        if (canSwitch) FilledIconButton(
            onClick = {
                val current = model.state.value
                if (ready && recording == null && current.generation == generation &&
                    current.step == EvidenceStep.CAMERA && !current.busy) {
                    ready = false
                    front = !front
                }
            },
            enabled = ready && !state.busy && state.step == EvidenceStep.CAMERA && recording == null,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(48.dp)
                .testTag("evidence-switch-camera"),
        ) {
            Icon(painterResource(R.drawable.camera_switch),
                stringResource(if (front) R.string.evidence_camera_back else R.string.evidence_camera_front))
        }
    }
    if (!ready) {
        CircularProgressIndicator()
        Text(stringResource(R.string.evidence_capture_wait), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
    if (state.kind == EvidenceKind.DOCUMENT) Text(stringResource(R.string.evidence_document_guide))
    if (video) Text(stringResource(R.string.evidence_video_silent))
    if (recording != null) Text(stringResource(R.string.evidence_recording, seconds))
    Button(enabled = ready && !state.busy && (state.step == EvidenceStep.CAMERA || recording != null),
        modifier = Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight),
        onClick = {
            val active = recording
            if (active != null) {
                active.stop()
                recording = null
            } else if (ready) {
                val draft = model.beginCapture()
                if (draft != null) {
                    try {
                        if (video) {
                            capture.targetRotation = view.display?.rotation ?: android.view.Surface.ROTATION_0
                            val output = FileOutputOptions.Builder(draft.file)
                                .setFileSizeLimit(checkNotNull(policy.maxBytes[EvidenceKind.VIDEO]))
                                .setDurationLimitMillis(policy.maxVideoDurationMillis).build()
                            recording = recorder.prepareRecording(context, output).start(executor) { event ->
                                when (event) {
                                    is VideoRecordEvent.Status -> seconds = event.recordingStats.recordedDurationNanos / 1_000_000_000
                                    is VideoRecordEvent.Finalize -> {
                                        recording = null
                                        if (!event.hasError() || event.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED ||
                                            event.error == VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED) model.captured(draft)
                                        else model.cameraFailed(draft, generation)
                                    }
                                }
                            }
                        } else {
                            photo.targetRotation = view.display?.rotation ?: android.view.Surface.ROTATION_0
                            photo.takePicture(ImageCapture.OutputFileOptions.Builder(draft.file).build(), executor,
                                object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(output: ImageCapture.OutputFileResults) = model.captured(draft)
                                    override fun onError(exception: ImageCaptureException) = model.cameraFailed(draft, generation)
                                })
                        }
                    } catch (_: SecurityException) {
                        model.cameraFailed(draft, generation, EvidenceError.PERMISSION)
                    } catch (_: IllegalStateException) {
                        model.cameraFailed(draft, generation)
                    } catch (_: IllegalArgumentException) {
                        model.cameraFailed(draft, generation)
                    }
                }
            }
        }) {
        Text(stringResource(if (recording != null) R.string.evidence_stop else R.string.evidence_take))
    }
}
