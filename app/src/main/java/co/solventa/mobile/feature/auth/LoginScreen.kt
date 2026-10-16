package co.solventa.mobile.feature.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import co.solventa.mobile.R
import co.solventa.mobile.SolventaApp
import co.solventa.mobile.core.ui.SolventaBrandMark

@Composable
fun LoginScreen(
    loading: Boolean = false,
    error: AuthError? = null,
    onLogin: (String, String) -> Unit = { _, _ -> },
) {
    // Credentials are transient UI state, never saved across activity recreation.
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val passwordFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SolventaBrandMark(40.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(32.dp))
                Text(
                    stringResource(R.string.login_title),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.login_subtitle), style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.height(32.dp))

                LoginField(stringResource(R.string.email)) { label ->
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        placeholder = { Text(label) },
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
                        singleLine = true,
                        enabled = !loading,
                        shape = RoundedCornerShape(12.dp),
                        colors = loginFieldColors(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
                    )
                }
                Spacer(Modifier.height(20.dp))
                LoginField(stringResource(R.string.password)) { label ->
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        placeholder = { Text(label) },
                        modifier = Modifier.fillMaxWidth().focusRequester(passwordFocus)
                            .semantics { contentDescription = label },
                        singleLine = true,
                        enabled = !loading,
                        shape = RoundedCornerShape(12.dp),
                        colors = loginFieldColors(),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            focusManager.clearFocus()
                            if (!loading) onLogin(email, password)
                        }),
                    )
                }
                Spacer(Modifier.height(28.dp))
                error?.let {
                    val message = when (it) {
                        AuthError.REQUIRED -> R.string.auth_required
                        AuthError.EMAIL -> R.string.auth_email
                        AuthError.PASSWORD -> R.string.auth_password
                        AuthError.INVALID_CREDENTIALS -> R.string.auth_invalid
                        AuthError.EXPIRED -> R.string.auth_expired
                        AuthError.RATE_LIMIT -> R.string.auth_rate_limit
                        AuthError.STORAGE -> R.string.auth_storage
                        AuthError.UNAVAILABLE -> R.string.auth_unavailable
                        AuthError.LOGOUT_REMOTE -> R.string.auth_logout_remote
                    }
                    Text(stringResource(message), color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    Spacer(Modifier.height(12.dp))
                }
                Button(onClick = { focusManager.clearFocus(); onLogin(email, password) },
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    shape = RoundedCornerShape(12.dp)) {
                    if (loading) {
                        CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(if (loading) R.string.signing_in else R.string.sign_in), style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)) {
                    Text(stringResource(R.string.biometric_sign_in), textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = {}, enabled = false) { Text(stringResource(R.string.forgot_password)) }
                TextButton(onClick = {}, enabled = false) { Text(stringResource(R.string.create_account)) }
            }
        }
    }
}

@Composable
private fun LoginField(label: String, field: @Composable (String) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        field(label)
    }
}

@Composable
private fun loginFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surface,
    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
)

@Preview(widthDp = 390, heightDp = 844, showBackground = true)
@Preview(widthDp = 320, heightDp = 568, fontScale = 1.5f, showBackground = true)
@Composable
private fun LoginPreview() {
    SolventaApp()
}
