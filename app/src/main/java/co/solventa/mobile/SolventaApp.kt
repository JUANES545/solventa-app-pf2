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
import androidx.compose.runtime.CompositionLocalProvider
import co.solventa.mobile.core.ui.LocalAccessBackgroundState
import co.solventa.mobile.core.ui.rememberAccessBackgroundState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import co.solventa.mobile.feature.home.HomeDestination
import co.solventa.mobile.feature.home.HomeNavigation
import co.solventa.mobile.feature.home.HomeScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import co.solventa.mobile.feature.evidence.EvidenceHost
import co.solventa.mobile.feature.evidence.EvidenceViewModel
import co.solventa.mobile.feature.claim.ClaimViewModel
import co.solventa.mobile.feature.claim.ClaimHost
import co.solventa.mobile.feature.claim.ClaimStatusViewModel
import co.solventa.mobile.feature.claim.ClaimStatusHost
import co.solventa.mobile.feature.evidence.EvidenceClaim
import co.solventa.mobile.feature.notification.NotificationHost
import co.solventa.mobile.feature.notification.NotificationNavigation
import co.solventa.mobile.feature.notification.NotificationViewModel
import co.solventa.mobile.feature.assistance.AssistanceHost
import co.solventa.mobile.feature.assistance.AssistanceViewModel

@Composable
internal fun SolventaApp(
    auth: AuthViewModel? = null,
    biometrics: BiometricViewModel? = null,
    device: BiometricDevice? = null,
    onPhotoReview: (() -> Unit)? = null,
    beforeAccessChange: () -> Unit = {},
    darkTheme: Boolean = isSystemInDarkTheme(),
    evidence: EvidenceViewModel? = null,
    claims: ClaimViewModel? = null,
    claimStatus: ClaimStatusViewModel? = null,
    notifications: NotificationViewModel? = null,
    assistance: AssistanceViewModel? = null,
    requestNotificationPermission: () -> Unit = {},
) {
    SolventaTheme(darkTheme) {
        CompositionLocalProvider(LocalAccessBackgroundState provides rememberAccessBackgroundState()) {
        val biometricState = biometrics?.state?.collectAsState()?.value
        if (auth == null) {
            LoginScreen()
        } else {
            val state by auth.state.collectAsState()
            val user = state.user
            var accountOpen by rememberSaveable(user?.id) { mutableStateOf(false) }
            val evidenceState = evidence?.state?.collectAsState()?.value
            val claimState = claims?.state?.collectAsState()?.value
            val claimStatusState = claimStatus?.state?.collectAsState()?.value
            val notificationState = notifications?.state?.collectAsState()?.value
            val assistanceState = assistance?.state?.collectAsState()?.value
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
            LaunchedEffect(user?.id) { evidence?.bindActor(user?.id) }
            LaunchedEffect(user?.id) { claims?.bindActor(user?.id) }
            LaunchedEffect(user?.id) { claimStatus?.bindActor(user?.id) }
            LaunchedEffect(user?.id) { notifications?.bindActor(user?.id) }
            LaunchedEffect(user?.id) { assistance?.bindActor(user?.id) }
            LaunchedEffect(notificationState?.navigation, user?.id) {
                val navigation = notificationState?.navigation
                if (user != null && navigation is NotificationNavigation.Claim) {
                    claimStatus?.show(user.id, navigation.id)
                    notifications?.consumeNavigation()
                }
            }
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
            } else if (claimStatus != null && claimStatusState?.open == true && claimStatusState.actor == user.id) {
                ClaimStatusHost(claimStatus)
            } else if (
                assistance != null &&
                assistanceState?.open == true &&
                assistanceState.actor == user.id
            ) {
                AssistanceHost(assistance)
            } else if (evidence != null && evidenceState?.open == true && evidenceState.actorId == user.id) {
                EvidenceHost(
                    evidence,
                    assistance?.let { model ->
                        evidenceState.scope?.let { scope ->
                            {
                                evidence.close()
                                model.show(user.id, scope.claimId)
                            }
                        }
                    },
                )
            } else if (claims != null && claimState?.open == true && claimState.actor == user.id) {
                ClaimHost(claims, onStatus = claimStatus?.let { model ->
                    { report -> report.receipt?.let { model.show(user.id, it.id) }; Unit }
                }) { report ->
                    report.receipt?.let { receipt ->
                        evidence?.showClaim(user.id, EvidenceClaim(receipt.id, report.payload.type,
                            report.payload.date.toString(), report.payload.description))
                    }
                }
            } else {
                val notificationsOpen = notifications != null && notificationState?.open == true &&
                    notificationState.actor == user.id
                val selected = when {
                    notificationsOpen -> HomeDestination.NOTIFICATIONS
                    accountOpen -> HomeDestination.ACCOUNT
                    else -> HomeDestination.HOME
                }
                BackHandler(enabled = accountOpen && !notificationsOpen) { accountOpen = false }
                HomeNavigation(selected, !state.loading, notifications != null, onSelect = { destination ->
                    when (destination) {
                        HomeDestination.HOME -> {
                            notifications?.close()
                            accountOpen = false
                        }
                        HomeDestination.NOTIFICATIONS -> {
                            accountOpen = false
                            notifications?.show(user.id)
                        }
                        HomeDestination.ACCOUNT -> {
                            notifications?.close()
                            accountOpen = true
                        }
                    }
                }) {
                    if (notificationsOpen && notifications != null) {
                        NotificationHost(notifications, requestNotificationPermission)
                    } else if (accountOpen) {
                        AccountScreen(user, state.loading || biometricState?.busy == true,
                    { beforeAccessChange(); auth.logout() },
                    biometricContent = {
                        if (biometricState != null && biometricState.actorUserId == user.id && device != null) {
                            BiometricAccountSection(biometricState, !state.loading,
                            { biometrics.enroll(it, device) }, biometrics::loadCredentials,
                            { biometrics.revoke(it, device) })
                        }
                    }, offline = state.offline)
                    } else {
                        HomeScreen(user, state.loading, state.offline,
                            onClaim = claims?.let { { it.show(user.id) } },
                            onClaimStatus = claimStatus?.let { { it.show(user.id) } },
                            onEvidence = evidence?.let { { it.showClaims(user.id) } })
                    }
                    }
                }
            }

        }
    }
}
