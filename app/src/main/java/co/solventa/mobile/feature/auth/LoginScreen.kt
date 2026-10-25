package co.solventa.mobile.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.biometric.BiometricManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import co.solventa.mobile.R
import co.solventa.mobile.SolventaApp
import co.solventa.mobile.core.ui.SolventaPage
import co.solventa.mobile.core.ui.SolventaBrandHeader
import co.solventa.mobile.core.designsystem.SolventaLayout

@Composable
fun LoginScreen(
    loading: Boolean = false,
    error: AuthError? = null,
    onLogin: (String, String) -> Unit = { _, _ -> },
    initialEmail: String = "",
    onCreateAccount: () -> Unit = {},
    onBiometricLogin: (() -> Unit)? = null,
    biometricMessage: Int? = null,
    biometricEnabled: Boolean = false,
    extraContent: (@Composable () -> Unit)? = null,
) {
    // Credentials are transient UI state, never saved across activity recreation.
    var email by remember(initialEmail) { mutableStateOf(initialEmail) }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    val passwordFocus = remember { FocusRequester() }
    val emailFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current

    SolventaPage(header = {
                SolventaBrandHeader()
                Text(
                    stringResource(R.string.login_title),
                    modifier = Modifier.fillMaxWidth().semantics { heading() },
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.headlineMedium,
                )
    }, actions = {
        Button(onClick = { focusManager.clearFocus(); onLogin(email, password) }, enabled = !loading,
            modifier = Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight), shape = SolventaLayout.controlShape) {
            if (loading) {
                CircularProgressIndicator(Modifier.width(20.dp).height(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(stringResource(if (loading) R.string.signing_in else R.string.sign_in))
        }
        onBiometricLogin?.let { login ->
            OutlinedButton(onClick = login, enabled = !loading,
                modifier = Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight), shape = SolventaLayout.controlShape) {
                Text(stringResource(R.string.biometric_login))
            }
        }
        TextButton(onClick = { focusManager.clearFocus(); onCreateAccount() }, enabled = !loading,
            modifier = Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight)) {
            Text(stringResource(R.string.create_account))
        }
        extraContent?.invoke()
        BiometricAccessInfo(enabled = !loading, onUsePassword = { emailFocus.requestFocus() },
            serviceEnabled = biometricEnabled, checkCapability = {
                BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            })
    }) {
                biometricMessage?.let {
                    Text(stringResource(it), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                AuthField(stringResource(R.string.email)) { label ->
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        modifier = Modifier.fillMaxWidth().focusRequester(emailFocus)
                            .semantics { contentDescription = label },
                        singleLine = true,
                        enabled = !loading,
                        shape = SolventaLayout.controlShape,
                        colors = authFieldColors(),
                        placeholder = { Text(stringResource(R.string.email_example)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
                    )
                }
                AuthField(stringResource(R.string.password)) { label ->
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        modifier = Modifier.fillMaxWidth().focusRequester(passwordFocus)
                            .semantics { contentDescription = label },
                        singleLine = true,
                        enabled = !loading,
                        shape = SolventaLayout.controlShape,
                        colors = authFieldColors(),
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            PasswordVisibilityToggle(passwordVisible, !loading, { passwordVisible = !passwordVisible })
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            focusManager.clearFocus()
                            if (!loading) onLogin(email, password)
                        }),
                    )
                }
                error?.let {
                    val message = authErrorMessage(it)
                    Text(stringResource(message), color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
    }
}

@Composable
internal fun AuthField(label: String, field: @Composable (String) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        field(label)
    }
}

@Composable
internal fun PasswordVisibilityToggle(visible: Boolean, enabled: Boolean, onClick: () -> Unit, confirmation: Boolean = false) {
    val description = if (confirmation) {
        if (visible) R.string.hide_confirmation else R.string.show_confirmation
    } else {
        if (visible) R.string.hide_password else R.string.show_password
    }
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(painterResource(if (visible) R.drawable.visibility_off else R.drawable.visibility),
            contentDescription = stringResource(description))
    }
}

@Composable
internal fun authFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surface,
    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
)

internal fun authErrorMessage(error: AuthError) = when (error) {
    AuthError.REQUIRED -> R.string.auth_required
    AuthError.NAME -> R.string.auth_name
    AuthError.EMAIL -> R.string.auth_email
    AuthError.PASSWORD -> R.string.auth_password
    AuthError.PASSWORD_MISMATCH -> R.string.auth_password_mismatch
    AuthError.EMAIL_EXISTS -> R.string.auth_email_exists
    AuthError.INVALID_REQUEST -> R.string.auth_request
    AuthError.INVALID_CREDENTIALS -> R.string.auth_invalid
    AuthError.EXPIRED -> R.string.auth_expired
    AuthError.RATE_LIMIT -> R.string.auth_rate_limit
    AuthError.STORAGE -> R.string.auth_storage
    AuthError.UNAVAILABLE -> R.string.auth_unavailable
    AuthError.LOGOUT_REMOTE -> R.string.auth_logout_remote
}

@Preview(widthDp = 390, heightDp = 844, showBackground = true)
@Preview(widthDp = 320, heightDp = 568, fontScale = 1.5f, showBackground = true)
@Composable
private fun LoginPreview() {
    SolventaApp()
}
