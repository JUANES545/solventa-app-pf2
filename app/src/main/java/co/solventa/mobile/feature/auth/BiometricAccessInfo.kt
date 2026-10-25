package co.solventa.mobile.feature.auth

import androidx.annotation.StringRes
import androidx.biometric.BiometricManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import co.solventa.mobile.R
import co.solventa.mobile.core.designsystem.SolventaLayout

@Composable
internal fun BiometricAccessInfo(
    enabled: Boolean,
    onUsePassword: () -> Unit,
    serviceEnabled: Boolean = false,
    checkCapability: () -> Int,
) {
    var capability by remember { mutableStateOf<Int?>(null) }
    TextButton(onClick = { capability = checkCapability() }, enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight), shape = SolventaLayout.controlShape,
    ) {
        Text(stringResource(R.string.biometric_sign_in))
    }
    capability?.let { status ->
        AlertDialog(
            onDismissRequest = { capability = null },
            title = { Text(stringResource(R.string.biometric_title), Modifier.semantics { heading() }) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(biometricCapabilityMessage(status)))
                    Text(stringResource(if (serviceEnabled) R.string.biometric_service_limited else R.string.biometric_service_unavailable))
                }
            },
            confirmButton = {
                TextButton(onClick = { capability = null; onUsePassword() }) {
                    Text(stringResource(R.string.biometric_use_password))
                }
            },
        )
    }
}

@StringRes
internal fun biometricCapabilityMessage(status: Int): Int = when (status) {
    BiometricManager.BIOMETRIC_SUCCESS -> R.string.biometric_device_ready
    BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> R.string.biometric_not_enrolled
    BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> R.string.biometric_no_hardware
    BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> R.string.biometric_hardware_unavailable
    BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> R.string.biometric_security_update
    BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED -> R.string.biometric_unsupported
    else -> R.string.biometric_status_unknown
}
