package co.solventa.mobile.feature.auth

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.*
import co.solventa.mobile.R
import co.solventa.mobile.core.designsystem.SolventaLayout
import co.solventa.mobile.core.ui.SolventaPage

@Composable
internal fun RecoveryScreen(
    confirm: Boolean, initialEmail: String, busy: Boolean, error: AuthError?,
    request: (String) -> Unit, submit: (String, String) -> Unit, restart: () -> Unit, withLink: () -> Unit, back: () -> Unit,
) {
    var email by remember(initialEmail) { mutableStateOf(initialEmail) }
    var code by remember(confirm) { mutableStateOf("") }
    var password by remember(confirm) { mutableStateOf("") }
    var visible by remember(confirm) { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val control = Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight)
    SolventaPage(header = {
        co.solventa.mobile.core.ui.SolventaScreenHeader(stringResource(R.string.recovery_title),
            stringResource(R.string.back_to_login), back, !busy)
    }, actions = {
        Button(onClick = {
            focus.clearFocus()
            if (confirm) submit(code.trim(), password) else request(email.trim())
        }, enabled = !busy, modifier = control, shape = SolventaLayout.controlShape) {
            Text(stringResource(if (confirm) R.string.recovery_save else R.string.recovery_send))
        }
        if (confirm) TextButton(onClick = restart, enabled = !busy, modifier = control) {
            Text(stringResource(R.string.recovery_restart))
        }
        else TextButton(onClick = withLink, enabled = !busy, modifier = control) {
            Text(stringResource(R.string.recovery_has_link))
        }
    }) {
        if (confirm) {
            Text(stringResource(R.string.recovery_instructions))
            OutlinedTextField(code, { code = it }, Modifier.fillMaxWidth(), enabled = !busy, singleLine = true,
                label = { Text(stringResource(R.string.recovery_code)) }, shape = SolventaLayout.controlShape,
                colors = authFieldColors(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                visualTransformation = PasswordVisualTransformation())
            OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), enabled = !busy, singleLine = true,
                label = { Text(stringResource(R.string.recovery_password)) }, shape = SolventaLayout.controlShape,
                colors = authFieldColors(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { PasswordVisibilityToggle(visible, !busy, { visible = !visible }) })
        } else {
            OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), enabled = !busy, singleLine = true,
                label = { Text(stringResource(R.string.email)) }, shape = SolventaLayout.controlShape,
                colors = authFieldColors(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { AuthErrorNotice(stringResource(authErrorMessage(it))) }
    }
}
