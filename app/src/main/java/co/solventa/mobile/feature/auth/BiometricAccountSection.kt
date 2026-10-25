package co.solventa.mobile.feature.auth

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import co.solventa.mobile.R
import co.solventa.mobile.core.designsystem.SolventaLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal fun BiometricAccountSection(
    state: BiometricUiState,
    enabled: Boolean,
    onEnroll: (String) -> Unit,
    onLoad: () -> Unit,
    onRevoke: (String) -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { password = ""; confirming = false }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(SolventaLayout.sectionGap)) {
    Text(stringResource(R.string.biometric_title), Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleLarge)
    if (state.localCredential == null) {
        TextButton(onClick = { confirming = true }, enabled = enabled && !state.busy) {
            Text(stringResource(R.string.biometric_enable))
        }
    } else if (state.localCredential.userId == state.actorUserId) {
        Text(stringResource(R.string.biometric_linked))
    } else {
        Text(stringResource(R.string.biometric_other_account))
    }
    TextButton(onClick = onLoad, enabled = enabled && !state.busy) {
        Text(stringResource(R.string.biometric_manage))
    }
    state.credentials.forEach { credential ->
        val label = credential.id.take(8)
        Text(stringResource(if (credential.active) R.string.biometric_credential_active else R.string.biometric_credential_revoked, label))
        if (credential.active || state.localCredential?.credentialId == credential.id) {
            TextButton(onClick = { onRevoke(credential.id) }, enabled = enabled && !state.busy) {
                Text(stringResource(R.string.biometric_revoke, label))
            }
        }
    }
    state.message?.let {
        Text(stringResource(it), color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { password = ""; confirming = false },
            title = { Text(stringResource(R.string.biometric_enable), Modifier.semantics { heading() }) },
            text = {
                Column(Modifier.imePadding().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(SolventaLayout.sectionGap)) {
                    Text(stringResource(R.string.biometric_enrollment_consent))
                    val label = stringResource(R.string.biometric_current_password)
                    OutlinedTextField(value = password, onValueChange = { password = it },
                        label = { Text(label) }, singleLine = true,
                        shape = SolventaLayout.controlShape, colors = authFieldColors(),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = label })
                }
            },
            confirmButton = {
                TextButton(enabled = password.isNotEmpty() && enabled && !state.busy, onClick = {
                    val transient = password
                    password = ""
                    confirming = false
                    onEnroll(transient)
                }) { Text(stringResource(R.string.biometric_enable)) }
            },
            dismissButton = {
                TextButton(onClick = { password = ""; confirming = false }) {
                    Text(stringResource(R.string.biometric_not_now))
                }
            },
        )
    }
    }
}
