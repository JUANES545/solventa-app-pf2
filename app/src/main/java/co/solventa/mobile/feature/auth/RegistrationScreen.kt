package co.solventa.mobile.feature.auth

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import co.solventa.mobile.R
import co.solventa.mobile.core.ui.SolventaPage
import co.solventa.mobile.core.ui.SolventaBrandHeader
import co.solventa.mobile.core.designsystem.SolventaLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegistrationReferenceScreen(onContinue: (Boolean) -> Unit, onBack: () -> Unit) {
    var terms by remember { mutableStateOf(false) }
    var finance by remember { mutableStateOf(false) }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.registration_reference_title), Modifier.semantics { heading() },
                fontWeight = FontWeight.SemiBold) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.arrow_back), stringResource(R.string.registration_back))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        )
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
            contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = SolventaLayout.contentWidth).fillMaxWidth()
                .verticalScroll(rememberScrollState()).padding(SolventaLayout.horizontalPadding),
                verticalArrangement = Arrangement.spacedBy(SolventaLayout.sectionGap)) {
                Row(Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight)
                    .toggleable(terms, role = Role.Checkbox, onValueChange = { terms = it }),
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(terms, null)
                    Text(stringResource(R.string.registration_terms), Modifier.weight(1f).padding(start = 8.dp))
                }
                Row(Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight)
                    .toggleable(finance, role = Role.Checkbox, onValueChange = { finance = it }),
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(finance, null)
                    Text(stringResource(R.string.registration_finance), Modifier.weight(1f).padding(start = 8.dp))
                }
                Button(onClick = { onContinue(terms) }, enabled = terms,
                    modifier = Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight),
                    shape = RoundedCornerShape(50)) { Text(stringResource(R.string.registration_continue)) }
                Text(stringResource(R.string.registration_reference_notice), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
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
                TextButton(onClick = { focus.clearFocus(); onBack() }, enabled = !loading) {
                    Text(stringResource(R.string.back_to_login))
                }
                Text(stringResource(R.string.registration_title), modifier = Modifier.fillMaxWidth().semantics { heading() },
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.headlineMedium)
    }, actions = {
        Button(onClick = { submit() }, enabled = !loading,
            modifier = Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight), shape = SolventaLayout.controlShape) {
            if (loading) {
                CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(8.dp))
            }
            Text(stringResource(if (loading) R.string.registering else R.string.registration_continue))
        }
    }) {
                AuthField(stringResource(R.string.name)) { label ->
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().semantics { contentDescription = label },
                        enabled = !loading, singleLine = true,
                        shape = SolventaLayout.controlShape, colors = authFieldColors(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { emailFocus.requestFocus() }))
                }
                AuthField(stringResource(R.string.email)) { label ->
                    OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth().focusRequester(emailFocus)
                        .semantics { contentDescription = label },
                        enabled = !loading, singleLine = true, shape = SolventaLayout.controlShape, colors = authFieldColors(),
                        placeholder = { Text(stringResource(R.string.email_example)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }))
                }
                AuthField(stringResource(R.string.password)) { label ->
                    OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth().focusRequester(passwordFocus)
                        .semantics { contentDescription = label },
                        enabled = !loading, singleLine = true, shape = SolventaLayout.controlShape, colors = authFieldColors(),
                        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = { PasswordVisibilityToggle(visible, !loading, { visible = !visible }) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { confirmationFocus.requestFocus() }))
                }
                AuthField(stringResource(R.string.confirm_password)) { label ->
                    OutlinedTextField(confirmation, { confirmation = it }, Modifier.fillMaxWidth().focusRequester(confirmationFocus)
                        .semantics { contentDescription = label },
                        enabled = !loading, singleLine = true, shape = SolventaLayout.controlShape, colors = authFieldColors(),
                        visualTransformation = if (confirmationVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = { PasswordVisibilityToggle(confirmationVisible, !loading,
                            { confirmationVisible = !confirmationVisible }, confirmation = true) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }))
                }
                error?.let {
                    val message = if (it == AuthError.UNAVAILABLE) R.string.registration_unavailable else authErrorMessage(it)
                    Text(stringResource(message), color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
    }
}

@Composable
fun RegistrationSuccessScreen(email: String, onSignIn: () -> Unit) {
    SolventaPage(header = {
        SolventaBrandHeader()
        Text(stringResource(R.string.registration_success), modifier = Modifier.fillMaxWidth().semantics { heading() },
            textAlign = TextAlign.Center, style = MaterialTheme.typography.headlineMedium)
    }, actions = {
        Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight),
            shape = SolventaLayout.controlShape) { Text(stringResource(R.string.go_to_login)) }
    }) {
        Text(email, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Text(stringResource(R.string.registration_success_body), Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun RegistrationConfirmationScreen(email: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    SolventaPage(header = {
        Text(stringResource(R.string.registration_finish), Modifier.fillMaxWidth().semantics { heading() },
            textAlign = TextAlign.Center, style = MaterialTheme.typography.headlineMedium)
    }, actions = {
        Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight),
            shape = SolventaLayout.controlShape) { Text(stringResource(R.string.create_account)) }
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight),
            shape = SolventaLayout.controlShape) { Text(stringResource(R.string.registration_cancel)) }
    }) {
        Text(email, Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Text(stringResource(R.string.registration_photo_info), Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
