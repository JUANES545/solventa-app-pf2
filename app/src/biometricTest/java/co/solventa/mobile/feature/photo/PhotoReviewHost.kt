package co.solventa.mobile.feature.photo

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import co.solventa.mobile.BuildConfig
import co.solventa.mobile.core.designsystem.SolventaTheme
import co.solventa.mobile.feature.auth.AuthViewModel
import co.solventa.mobile.feature.auth.AccessScreen

@Composable
internal fun PhotoReviewHost(
    activity: ComponentActivity,
    auth: AuthViewModel,
    camera: @Composable (PhotoReviewViewModel, PhotoState) -> Unit = { model, state -> LocalPhotoCamera(model, state) },
    content: @Composable ((() -> Unit)?, () -> Unit) -> Unit,
) {
    if (!BuildConfig.PHOTO_REVIEW_ENABLED) {
        content(null, {})
        return
    }
    val model = remember(activity) { ViewModelProvider(activity)[PhotoReviewViewModel::class.java] }
    val account by auth.state.collectAsState()
    val state by model.state.collectAsState()
    val actor = account.user?.id
    LaunchedEffect(actor, account.screen, account.email) {
        model.bindActor(actor)
        if (account.screen != AccessScreen.REGISTER_PHOTOS) model.close()
    }
    LaunchedEffect(state.generation) {
        if (!state.open && state.error == co.solventa.mobile.R.string.photo_storage) {
            Toast.makeText(activity, co.solventa.mobile.R.string.photo_storage, Toast.LENGTH_LONG).show()
        }
    }
    DisposableEffect(activity, model, auth) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                model.pause()
                auth.cancelRegistrationPhotos()
            }
        }
        activity.lifecycle.addObserver(observer)
        onDispose {
            activity.lifecycle.removeObserver(observer)
            model.pause()
            auth.cancelRegistrationPhotos()
        }
    }
    val close = {
        model.close()
        auth.cancelRegistrationPhotos()
    }
    content({ model.bindActor(actor); model.open() }, close)
    if (state.open && state.actorId == actor) {
        PhotoReviewDialog(close) {
            SolventaTheme {
                PhotoReviewScreen(state, model::startCamera, model::accept, close, onFinished = {
                    val completed = model.state.value
                    val received = completed.open && completed.step == PhotoStep.COMPLETE &&
                        completed.documentReceived && completed.selfieReceived &&
                        completed.preview == null && completed.error == null
                    model.close()
                    if (received && model.state.value.error == null) auth.completeRegistrationPhotos()
                    else auth.cancelRegistrationPhotos()
                }, onRestart = model::open) {
                    camera(model, state)
                }
            }
            if (state.step == PhotoStep.PERMISSION) key(state.generation) {
                CameraPermission(state.generation, model::permissionResult)
            }
        }
    }
}

@Composable
internal fun PhotoReviewDialog(onClose: () -> Unit, content: @Composable () -> Unit) {
    // Compose 1.7.8's full-width mode ignores the native height constraint in favor of screenHeightDp.
    Dialog(onDismissRequest = onClose, properties = DialogProperties(
        usePlatformDefaultWidth = true, decorFitsSystemWindows = false,
        securePolicy = SecureFlagPolicy.SecureOn)) {
        val provider = LocalView.current.parent
        check(provider is DialogWindowProvider)
        SideEffect {
            provider.window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        }
        content()
    }
}

@Composable
private fun CameraPermission(generation: Long, onResult: (Long, Boolean) -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        onResult(generation, it)
    }
    LaunchedEffect(generation) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            onResult(generation, true)
        } else {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }
}
