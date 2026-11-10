package co.solventa.mobile.feature.auth

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import co.solventa.mobile.R
import co.solventa.mobile.core.ui.SolventaPage
import co.solventa.mobile.core.ui.SolventaConfirmationMark
import co.solventa.mobile.core.ui.SolventaScreenHeader
import co.solventa.mobile.core.designsystem.SolventaLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import co.solventa.mobile.core.ui.AccessBackground
import co.solventa.mobile.core.ui.LocalAccessBackgroundState
import co.solventa.mobile.core.ui.rememberAccessBackgroundState
import co.solventa.mobile.core.ui.status

@Composable
fun RegistrationReferenceScreen(onContinue: (Boolean) -> Unit, onBack: () -> Unit) {
    var terms by remember { mutableStateOf(false) }
    var finance by remember { mutableStateOf(false) }
    SolventaPage(headerAlignment = Alignment.Start, header = {
        SolventaScreenHeader(
            title = stringResource(R.string.registration_reference_title),
            backLabel = stringResource(R.string.registration_back),
            onBack = onBack,
        )
    }, actions = {
        Button(onClick = { onContinue(terms) }, enabled = terms,
            modifier = Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight),
            shape = SolventaLayout.controlShape) {
            Text(stringResource(R.string.registration_continue), textAlign = TextAlign.Center)
        }
    }) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.medium) {
            Row(Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight)
                .toggleable(terms, role = Role.Checkbox, onValueChange = { terms = it }).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Checkbox(terms, null)
                Text(stringResource(R.string.registration_terms), Modifier.weight(1f).padding(start = 8.dp))
            }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.medium) {
            Row(Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight)
                .toggleable(finance, role = Role.Checkbox, onValueChange = { finance = it }).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Checkbox(finance, null)
                Text(stringResource(R.string.registration_finance), Modifier.weight(1f).padding(start = 8.dp))
            }
        }
        Text(stringResource(R.string.registration_reference_notice), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun RegistrationScreen(
    loading: Boolean = false,
    error: AuthError? = null,
    onRegister: (String, String, String, String) -> Unit = { _, _, _, _ -> },
    onBack: () -> Unit = {},
    showTerms: Boolean = false,
    onContinueTerms: (Boolean) -> Unit = {},
    onTermsBack: () -> Unit = {},
) {
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var confirmationVisible by remember { mutableStateOf(false) }
    val emailFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    val confirmationFocus = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var focusedField by remember { mutableStateOf<String?>(null) }
    val background = LocalAccessBackgroundState.current ?: rememberAccessBackgroundState()
    val motionStatus = background.status(focusedField != null, error != null, loading)
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                name = ""; email = ""; password = ""; confirmation = ""; visible = false; confirmationVisible = false
                focus.clearFocus()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    if (showTerms) {
        RegistrationReferenceScreen(onContinueTerms, onTermsBack)
        return
    }

    fun submit() {
        focus.clearFocus()
        visible = false
        confirmationVisible = false
        if (!loading) onRegister(name, email, password, confirmation)
    }

    SolventaPage(header = {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { focus.clearFocus(); onBack() }, enabled = !loading,
                modifier = Modifier.heightIn(min = SolventaLayout.controlHeight)) {
                Text(stringResource(R.string.back_to_login), textAlign = TextAlign.Center)
            }
            Text(stringResource(R.string.registration_title), modifier = Modifier.fillMaxWidth().semantics { heading() },
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.headlineMedium)
        }
    }, actions = {
        Button(onClick = { submit() }, enabled = !loading,
            modifier = Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight), shape = SolventaLayout.controlShape) {
            if (loading) {
                CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(8.dp))
            }
            Text(stringResource(if (loading) R.string.registering else R.string.registration_continue), textAlign = TextAlign.Center)
        }
    }, background = { AccessBackground(background, motionStatus) }) {
                AuthField(stringResource(R.string.name)) { label ->
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth()
                        .onFocusChanged {
                            if (it.isFocused) focusedField = "name"
                            else if (focusedField == "name") focusedField = null
                        }.semantics { contentDescription = label },
                        enabled = !loading, singleLine = true,
                        isError = error == AuthError.NAME,
                        shape = SolventaLayout.controlShape, colors = authFieldColors(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { emailFocus.requestFocus() }))
                }
                AuthField(stringResource(R.string.email)) { label ->
                    OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth().focusRequester(emailFocus)
                        .onFocusChanged {
                            if (it.isFocused) focusedField = "email"
                            else if (focusedField == "email") focusedField = null
                        }
                        .semantics { contentDescription = label },
                        enabled = !loading, singleLine = true, shape = SolventaLayout.controlShape, colors = authFieldColors(),
                        isError = error in listOf(AuthError.EMAIL, AuthError.EMAIL_EXISTS),
                        placeholder = { Text(stringResource(R.string.email_example)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }))
                }
                AuthField(stringResource(R.string.password)) { label ->
                    OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth().focusRequester(passwordFocus)
                        .onFocusChanged {
                            if (it.isFocused) focusedField = "password"
                            else if (focusedField == "password") focusedField = null
                        }
                        .semantics { contentDescription = label },
                        enabled = !loading, singleLine = true, shape = SolventaLayout.controlShape, colors = authFieldColors(),
                        isError = error == AuthError.PASSWORD,
                        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = { PasswordVisibilityToggle(visible, !loading, { visible = !visible }) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { confirmationFocus.requestFocus() }))
                }
                AuthField(stringResource(R.string.confirm_password)) { label ->
                    OutlinedTextField(confirmation, { confirmation = it }, Modifier.fillMaxWidth().focusRequester(confirmationFocus)
                        .onFocusChanged {
                            if (it.isFocused) focusedField = "confirmation"
                            else if (focusedField == "confirmation") focusedField = null
                        }
                        .semantics { contentDescription = label },
                        enabled = !loading, singleLine = true, shape = SolventaLayout.controlShape, colors = authFieldColors(),
                        isError = error == AuthError.PASSWORD_MISMATCH,
                        visualTransformation = if (confirmationVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = { PasswordVisibilityToggle(confirmationVisible, !loading,
                            { confirmationVisible = !confirmationVisible }, confirmation = true) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }))
                }
                error?.let {
                    val message = if (it == AuthError.UNAVAILABLE) R.string.registration_unavailable else authErrorMessage(it)
                    AuthErrorNotice(stringResource(message))
                }
    }
}

@Composable
fun RegistrationSuccessScreen(email: String, onSignIn: () -> Unit) {
    SolventaPage(header = {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SolventaConfirmationMark(key = email)
            Text(stringResource(R.string.registration_success), modifier = Modifier.fillMaxWidth().semantics { heading() },
                textAlign = TextAlign.Center, style = MaterialTheme.typography.headlineMedium)
        }
    }, actions = {
        Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight),
            shape = SolventaLayout.controlShape) { Text(stringResource(R.string.go_to_login), textAlign = TextAlign.Center) }
    }) {
        RegistrationSummary(email, R.string.registration_success_body)
    }
}

@Composable
fun RegistrationConfirmationScreen(email: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    SolventaPage(header = {
        Text(stringResource(R.string.registration_finish), Modifier.fillMaxWidth().semantics { heading() },
            textAlign = TextAlign.Center, style = MaterialTheme.typography.headlineMedium)
    }, actions = {
        Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight),
            shape = SolventaLayout.controlShape) { Text(stringResource(R.string.create_account), textAlign = TextAlign.Center) }
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight),
            shape = SolventaLayout.controlShape) { Text(stringResource(R.string.registration_cancel), textAlign = TextAlign.Center) }
    }) {
        RegistrationSummary(email, R.string.registration_photo_info)
    }
}

@Composable
private fun RegistrationSummary(email: String, message: Int) {
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(email, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(message), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
