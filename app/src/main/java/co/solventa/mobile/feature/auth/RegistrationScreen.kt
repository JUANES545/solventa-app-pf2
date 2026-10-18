package co.solventa.mobile.feature.auth

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import co.solventa.mobile.R
import co.solventa.mobile.core.ui.SolventaBrandMark

@Composable
fun RegistrationScreen(
    loading: Boolean = false,
    error: AuthError? = null,
    onRegister: (String, String, String, String) -> Unit = { _, _, _, _ -> },
    onBack: () -> Unit = {},
) {
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val emailFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    val confirmationFocus = remember { FocusRequester() }
    val focus = LocalFocusManager.current

    fun submit() {
        focus.clearFocus()
        if (!loading) onRegister(name, email, password, confirmation)
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight).padding(horizontal = 24.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SolventaBrandMark(32.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(24.dp))
                Text(stringResource(R.string.registration_title), modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.registration_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center)
                Spacer(Modifier.height(24.dp))
                AuthField(stringResource(R.string.name)) { label ->
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().semantics { contentDescription = label },
                        placeholder = { Text(label) }, enabled = !loading, singleLine = true,
                        shape = RoundedCornerShape(12.dp), colors = authFieldColors(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { emailFocus.requestFocus() }))
                }
                Spacer(Modifier.height(16.dp))
                AuthField(stringResource(R.string.email)) { label ->
                    OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth().focusRequester(emailFocus)
                        .semantics { contentDescription = label }, placeholder = { Text(label) },
                        enabled = !loading, singleLine = true, shape = RoundedCornerShape(12.dp), colors = authFieldColors(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }))
                }
                Spacer(Modifier.height(16.dp))
                AuthField(stringResource(R.string.password)) { label ->
                    OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth().focusRequester(passwordFocus)
                        .semantics { contentDescription = label }, placeholder = { Text(label) },
                        enabled = !loading, singleLine = true, shape = RoundedCornerShape(12.dp), colors = authFieldColors(),
                        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { confirmationFocus.requestFocus() }))
                }
                Spacer(Modifier.height(16.dp))
                AuthField(stringResource(R.string.confirm_password)) { label ->
                    OutlinedTextField(confirmation, { confirmation = it }, Modifier.fillMaxWidth().focusRequester(confirmationFocus)
                        .semantics { contentDescription = label }, placeholder = { Text(label) },
                        enabled = !loading, singleLine = true, shape = RoundedCornerShape(12.dp), colors = authFieldColors(),
                        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }))
                }
                TextButton(onClick = { visible = !visible }, enabled = !loading) {
                    Text(stringResource(if (visible) R.string.hide_password else R.string.show_password))
                }
                error?.let {
                    val message = if (it == AuthError.UNAVAILABLE) R.string.registration_unavailable else authErrorMessage(it)
                    Text(stringResource(message), color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    Spacer(Modifier.height(16.dp))
                }
                Button(onClick = { submit() }, enabled = !loading,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(12.dp)) {
                    if (loading) {
                        CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(if (loading) R.string.registering else R.string.create_account))
                }
                TextButton(onClick = { focus.clearFocus(); onBack() }, enabled = !loading) {
                    Text(stringResource(R.string.back_to_login))
                }
            }
        }
    }
}

@Composable
fun RegistrationSuccessScreen(email: String, onSignIn: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            SolventaBrandMark(56.dp)
            Spacer(Modifier.height(32.dp))
            Text(stringResource(R.string.registration_success), modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.registration_success_body), textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Text(email, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(32.dp))
            Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                shape = RoundedCornerShape(12.dp)) { Text(stringResource(R.string.go_to_login)) }
        }
    }
}
