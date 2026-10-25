package co.solventa.mobile

import androidx.compose.runtime.Composable
import co.solventa.mobile.core.designsystem.SolventaTheme
import androidx.compose.foundation.isSystemInDarkTheme
import co.solventa.mobile.feature.auth.LoginScreen
import co.solventa.mobile.feature.auth.AuthViewModel
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.activity.compose.BackHandler
import co.solventa.mobile.feature.auth.AccessScreen
import co.solventa.mobile.feature.auth.AccountScreen
import co.solventa.mobile.feature.auth.RegistrationScreen
import co.solventa.mobile.feature.auth.RegistrationSuccessScreen
import co.solventa.mobile.feature.auth.RegistrationConfirmationScreen
import co.solventa.mobile.core.ui.SolventaPage
import co.solventa.mobile.feature.auth.BiometricViewModel
import co.solventa.mobile.feature.auth.BiometricDevice
import co.solventa.mobile.feature.auth.BiometricAccountSection
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource

@Composable
internal fun SolventaApp(
    auth: AuthViewModel? = null,
    biometrics: BiometricViewModel? = null,
    device: BiometricDevice? = null,
    onPhotoReview: (() -> Unit)? = null,
    beforeAccessChange: () -> Unit = {},
    darkTheme: Boolean = isSystemInDarkTheme(),
) {
    SolventaTheme(darkTheme) {
        val biometricState = biometrics?.state?.collectAsState()?.value
        if (auth == null) {
            LoginScreen()
        } else {
            val state by auth.state.collectAsState()
            val user = state.user
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            DisposableEffect(auth, lifecycle) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_STOP) auth.cancelRegistrationPhotos()
                }
                lifecycle.addObserver(observer)
                onDispose {
                    lifecycle.removeObserver(observer)
                    auth.cancelRegistrationPhotos()
                }
            }
            LaunchedEffect(user?.id) { biometrics?.bindActor(user?.id) }
            LaunchedEffect(state.screen) {
                if (state.screen == AccessScreen.REGISTER_PHOTOS) onPhotoReview?.invoke()
            }
            BackHandler(enabled = user == null && state.screen != AccessScreen.LOGIN) {
                if (!state.loading) {
                    beforeAccessChange()
                    if (state.screen == AccessScreen.REGISTER_REFERENCE) auth.showRegistration() else auth.showLogin()
                }
            }
            if (user == null) {
                when (state.screen) {
                    AccessScreen.LOGIN -> {
                        LoginScreen(loading = state.loading || biometricState?.busy == true, error = state.error,
                            onLogin = { email, password -> beforeAccessChange(); auth.login(email, password) },
                            initialEmail = state.email, onCreateAccount = { beforeAccessChange(); auth.showRegistration() },
                            biometricEnabled = biometrics != null,
                            biometricMessage = biometricState?.takeIf { it.actorUserId == null }?.message,
                            onBiometricLogin = if (biometricState?.localCredential != null && device != null) {
                                { beforeAccessChange(); biometrics.login(device, auth::restore) }
                            } else null)
                    }
                    AccessScreen.REGISTER, AccessScreen.REGISTER_REFERENCE -> RegistrationScreen(
                        loading = state.loading, error = state.error,
                        onRegister = { name, email, password, confirmation ->
                            auth.prepareRegistration(name, email, password, confirmation, withPhotos = onPhotoReview != null)
                        },
                        showTerms = state.screen == AccessScreen.REGISTER_REFERENCE,
                        onContinueTerms = auth::continueRegistration, onTermsBack = auth::showRegistration,
                        onBack = auth::showLogin)
                    AccessScreen.REGISTER_PHOTOS -> SolventaPage {
                        Text(stringResource(R.string.photo_entry))
                        Text(stringResource(R.string.registration_photo_info))
                        TextButton(onClick = { beforeAccessChange(); auth.cancelRegistrationPhotos() }) {
                            Text(stringResource(R.string.registration_cancel))
                        }
                    }
                    AccessScreen.REGISTER_CONFIRM -> RegistrationConfirmationScreen(state.email,
                        onConfirm = auth::confirmRegistration,
                        onCancel = { beforeAccessChange(); auth.cancelRegistrationPhotos() })
                    AccessScreen.REGISTERED -> RegistrationSuccessScreen(state.email, onSignIn = auth::showLogin)
                }
            } else {
                AccountScreen(user, state.loading || biometricState?.busy == true,
                    { beforeAccessChange(); auth.logout() },
                    biometricContent = {
                        if (biometricState != null && biometricState.actorUserId == user.id && device != null) {
                            BiometricAccountSection(biometricState, !state.loading,
                            { biometrics.enroll(it, device) }, biometrics::loadCredentials,
                            { biometrics.revoke(it, device) })
                        }
                    })
            }

        }
    }
}
