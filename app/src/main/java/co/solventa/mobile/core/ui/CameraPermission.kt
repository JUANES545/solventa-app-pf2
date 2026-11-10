package co.solventa.mobile.core.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import co.solventa.mobile.R

@Composable
internal fun CameraPermission(requestKey: Any, onGranted: () -> Unit, onCancel: () -> Unit) {
    key(requestKey) {
        val context = LocalContext.current
        val activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>().firstOrNull()
        var denied by remember { mutableStateOf(false) }
        var settings by remember { mutableStateOf(false) }
        var settingsFailed by remember { mutableStateOf(false) }
        var active by remember { mutableStateOf(true) }
        var completed by remember { mutableStateOf(false) }
        DisposableEffect(Unit) { onDispose { active = false } }
        val granted = {
            if (active && !completed) {
                completed = true
                onGranted()
            }
        }
        val cancel = {
            if (active && !completed) {
                completed = true
                onCancel()
            }
        }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (active && !completed) {
                val allowed = result[Manifest.permission.CAMERA]
                if (allowed == null) cancel()
                else if (allowed && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED) granted()
                else {
                    denied = true
                    settings = activity != null &&
                        !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
                }
            }
        }
        LaunchedEffect(Unit) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                granted()
            } else {
                launcher.launch(arrayOf(Manifest.permission.CAMERA))
            }
        }
        BackHandler(onBack = cancel)
        if (denied && !completed) AlertDialog(
            onDismissRequest = cancel,
            title = { Text(stringResource(R.string.camera_permission_title)) },
            text = { Text(stringResource(when {
                settingsFailed -> R.string.camera_settings_unavailable
                settings -> R.string.camera_permission_settings
                else -> R.string.camera_permission_needed
            })) },
            confirmButton = {
                if (settings) TextButton(onClick = {
                    try {
                        // Leaving for Settings cancels this capture; returning never starts it automatically.
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}")))
                        cancel()
                    } catch (_: ActivityNotFoundException) {
                        settingsFailed = true
                    } catch (_: SecurityException) {
                        settingsFailed = true
                    }
                }) { Text(stringResource(R.string.camera_open_settings)) }
                else TextButton(onClick = cancel) { Text(stringResource(R.string.camera_permission_close)) }
            },
            dismissButton = {
                if (settings) TextButton(onClick = cancel) { Text(stringResource(R.string.camera_permission_close)) }
            },
        )
    }
}
