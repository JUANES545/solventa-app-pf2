package co.solventa.mobile.feature.photo

import android.view.Surface
import android.view.ViewTreeObserver
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.RectF
import android.graphics.Rect
import android.util.Size
import androidx.camera.core.CameraInfoUnavailableException
import androidx.camera.core.Camera
import androidx.camera.core.CameraState
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as DrawSize
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.core.view.doOnAttach
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Observer
import co.solventa.mobile.R
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

@Composable
@androidx.annotation.OptIn(androidx.camera.view.TransformExperimental::class)
internal fun LocalPhotoCamera(model: PhotoReviewViewModel, state: PhotoState) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val generation = state.generation
    val selfie = state.kind == PhotoKind.SELFIE
    val selector = photoCameraSelector(state.kind)
    val missingCamera = if (selfie) R.string.photo_no_camera else R.string.photo_no_document_camera
    val view = remember(generation) { PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FIT_CENTER
    } }
    val preview = remember(generation) { Preview.Builder().build() }
    val capture = remember(generation) { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    val executor = ContextCompat.getMainExecutor(context)
    var ready by remember(generation) { mutableStateOf(false) }
    var hint by remember(generation) { mutableStateOf(SelfieHint.PREPARING) }
    var oval by remember(generation) { mutableStateOf<RectF?>(null) }
    var documentBounds by remember(generation) { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    fun previewVisible(): Boolean {
        val visible = Rect()
        return view.isShown && view.hasWindowFocus() && view.getGlobalVisibleRect(visible) &&
            visible.width() >= view.width - 2 && visible.height() >= view.height - 2
    }

    fun takePhoto() {
        if (!ready || !lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val request = model.beginCapture(generation) ?: return
        capture.targetRotation = view.display?.rotation ?: Surface.ROTATION_0
        try {
            capture.takePicture(ImageCapture.OutputFileOptions.Builder(request.file).build(), executor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) = model.captured(request)
                    override fun onError(exception: ImageCaptureException) =
                        model.cameraFailed(request.generation, request = request)
                })
        } catch (_: SecurityException) {
            model.cameraFailed(request.generation, R.string.photo_denied, request)
        } catch (_: IllegalStateException) {
            model.cameraFailed(request.generation, request = request)
        }
    }

    DisposableEffect(lifecycle, generation) {
        var disposed = false
        var provider: ProcessCameraProvider? = null
        var boundCamera: Camera? = null
        var analyzer: SelfieAnalyzer? = null
        var documentAnalyzer: DocumentFrameAnalyzer? = null
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_RESUME) {
                analyzer?.invalidate()
                hint = SelfieHint.PREPARING
            }
        }
        val focusObserver = ViewTreeObserver.OnWindowFocusChangeListener {
            analyzer?.invalidate()
            hint = SelfieHint.PREPARING
        }
        var focusTree: ViewTreeObserver? = null
        lifecycle.lifecycle.addObserver(lifecycleObserver)
        view.doOnAttach {
            if (!disposed) {
                focusTree = view.viewTreeObserver.also { it.addOnWindowFocusChangeListener(focusObserver) }
            }
        }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy(Size(640, 480),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build()).build()
        val analysisExecutor = if (selfie) Executors.newSingleThreadExecutor() else null
        val observer = Observer<CameraState> {
            ready = it.type == CameraState.Type.OPEN
            if (it.error != null) model.cameraFailed(generation)
        }
        val future = ProcessCameraProvider.getInstance(context)
        view.doOnLayout {
        future.addListener({
            if (!disposed) {
                try {
                    val cameraProvider = future.get()
                    provider = cameraProvider
                    if (!cameraProvider.hasCamera(selector)) {
                        model.cameraFailed(generation, missingCamera)
                    } else {
                        preview.setSurfaceProvider(view.surfaceProvider)
                        val rotation = view.display?.rotation ?: Surface.ROTATION_0
                        preview.targetRotation = rotation
                        capture.targetRotation = rotation
                        val group = UseCaseGroup.Builder().addUseCase(preview).addUseCase(capture)
                        analysis.targetRotation = rotation
                        if (selfie && analysisExecutor != null) {
                            val detector = FaceDetection.getClient(FaceDetectorOptions.Builder()
                                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE).build())
                            analyzer = SelfieAnalyzer(detector, executor, { view.outputTransform },
                                { view.width to view.height },
                                active = {
                                    !disposed && ready && previewVisible() && lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                                        model.state.value.let { it.open && it.generation == generation && it.step == PhotoStep.CAMERA } &&
                                        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                                },
                                onHint = { next, guide -> hint = next; oval = guide },
                                onCapture = { analysis.clearAnalyzer(); takePhoto() },
                                onError = { executor.execute { model.cameraFailed(generation, R.string.photo_guidance_error) } })
                            analysis.setAnalyzer(analysisExecutor, requireNotNull(analyzer))
                        } else {
                            documentAnalyzer = DocumentFrameAnalyzer({ view.outputTransform },
                                { view.width to view.height },
                                active = {
                                    !disposed && ready && lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                                        model.state.value.let { it.open && it.generation == generation &&
                                            it.step in listOf(PhotoStep.CAMERA, PhotoStep.CAPTURING) }
                                },
                                onBounds = { bounds -> documentBounds = bounds?.let {
                                    androidx.compose.ui.geometry.Rect(it.left, it.top, it.right, it.bottom)
                                } },
                                onError = { model.cameraFailed(generation) })
                            analysis.setAnalyzer(executor, requireNotNull(documentAnalyzer))
                        }
                        group.addUseCase(analysis)
                        group.setViewPort(requireNotNull(view.viewPort))
                        val camera = cameraProvider.bindToLifecycle(lifecycle, selector, group.build())
                        boundCamera = camera
                        camera.cameraInfo.cameraState.observe(lifecycle, observer)
                    }
                } catch (_: ExecutionException) {
                    model.cameraFailed(generation)
                } catch (_: CameraInfoUnavailableException) {
                    model.cameraFailed(generation, missingCamera)
                } catch (_: SecurityException) {
                    model.cameraFailed(generation, R.string.photo_denied)
                } catch (_: IllegalArgumentException) {
                    model.cameraFailed(generation)
                } catch (_: IllegalStateException) {
                    model.cameraFailed(generation)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    model.cameraFailed(generation)
                }
            }
        }, executor)
        }
        onDispose {
            disposed = true
            lifecycle.lifecycle.removeObserver(lifecycleObserver)
            focusTree?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(focusObserver)
            analysis.clearAnalyzer()
            analyzer?.close()
            documentAnalyzer?.close()
            analysisExecutor?.shutdown()
            boundCamera?.cameraInfo?.cameraState?.removeObserver(observer)
            provider?.unbind(preview, capture)
            provider?.unbind(analysis)
        }
    }
    val guideColor = if (hint == SelfieHint.HOLD) MaterialTheme.colorScheme.tertiary else Color.White
    val cameraHeight = (LocalConfiguration.current.screenHeightDp * .42f).coerceIn(160f, 360f).dp
    Box(Modifier.fillMaxWidth().height(cameraHeight)) {
        AndroidView(factory = { view }, modifier = Modifier.matchParentSize())
        if (selfie) Canvas(Modifier.matchParentSize()) {
            oval?.let {
                drawOval(Color.Black, Offset(it.left, it.top), DrawSize(it.width(), it.height()), style = Stroke(6.dp.toPx()))
                drawOval(guideColor, Offset(it.left, it.top), DrawSize(it.width(), it.height()), style = Stroke(3.dp.toPx()))
            }
        } else {
            DocumentFrameOverlay(documentBounds, Modifier.matchParentSize())
        }
    }
    if (selfie) {
        Text(stringResource(selfieHintMessage(if (state.step == PhotoStep.CAPTURING) SelfieHint.CAPTURING else hint)),
            modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
            textAlign = TextAlign.Center)
    } else {
        Text(stringResource(if (documentBounds == null) R.string.photo_document_preparing
            else R.string.photo_document_live_guide), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        PhotoButton(if (state.step == PhotoStep.CAPTURING) R.string.photo_taking else R.string.photo_take,
            ::takePhoto, ready && documentBounds != null && state.step == PhotoStep.CAMERA)
    }
}

internal fun photoCameraSelector(kind: PhotoKind): CameraSelector =
    if (kind == PhotoKind.DOCUMENT) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA

internal fun selfieHintMessage(hint: SelfieHint): Int = when (hint) {
    SelfieHint.PREPARING -> R.string.selfie_preparing
    SelfieHint.NO_FACE -> R.string.selfie_no_face
    SelfieHint.MULTIPLE -> R.string.selfie_multiple
    SelfieHint.CENTER -> R.string.selfie_center
    SelfieHint.CLOSER -> R.string.selfie_closer
    SelfieHint.FARTHER -> R.string.selfie_farther
    SelfieHint.STRAIGHT -> R.string.selfie_straight
    SelfieHint.HOLD -> R.string.selfie_hold
    SelfieHint.CAPTURING -> R.string.photo_taking
}
