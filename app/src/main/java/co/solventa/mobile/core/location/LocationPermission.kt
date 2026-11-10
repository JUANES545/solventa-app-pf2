package co.solventa.mobile.core.location

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
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
import co.solventa.mobile.R

@Composable
internal fun LocationPermission(onGranted: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }
        .filterIsInstance<Activity>().firstOrNull()
    var active by remember { mutableStateOf(true) }
    var completed by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var settingsFailed by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { active = false } }
    val cancel = {
        if (active && !completed) {
            completed = true
            onCancel()
        }
    }
    val grant = {
        if (active && !completed) {
            completed = true
            onGranted()
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (active && !completed && !denied) {
            if (result.isEmpty()) cancel()
            else if (hasLocationPermission(context)) grant()
            else {
                denied = true
                settings = activity != null && listOf(Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION).none {
                    ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        if (hasLocationPermission(context)) grant()
        else launcher.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
    }
    BackHandler(onBack = cancel)
    if (denied && !completed) AlertDialog(
        onDismissRequest = cancel,
        title = { Text(stringResource(R.string.location_permission_title)) },
        text = { Text(stringResource(when {
            settingsFailed -> R.string.location_permission_settings_failed
            settings -> R.string.location_permission_settings
            else -> R.string.location_permission_denied
        })) },
        confirmButton = {
            TextButton(onClick = {
                if (!settings) cancel()
                else try {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}")))
                    cancel()
                } catch (_: ActivityNotFoundException) {
                    settingsFailed = true
                } catch (_: SecurityException) {
                    settingsFailed = true
                }
            }) { Text(stringResource(if (settings) R.string.location_permission_open_settings else R.string.ui_choose_cancel)) }
        },
        dismissButton = {
            if (settings) TextButton(onClick = cancel) { Text(stringResource(R.string.ui_choose_cancel)) }
        },
    )
}
