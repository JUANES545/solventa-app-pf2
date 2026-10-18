package co.solventa.mobile

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import co.solventa.mobile.feature.auth.LoginScreen
import co.solventa.mobile.feature.auth.AuthViewModel
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.activity.compose.BackHandler
import co.solventa.mobile.feature.auth.AccessScreen
import co.solventa.mobile.feature.auth.AccountScreen
import co.solventa.mobile.feature.auth.RegistrationScreen
import co.solventa.mobile.feature.auth.RegistrationSuccessScreen

private val LoginColors = darkColorScheme(
    primary = Color(0xFF22D3EE),
    onPrimary = Color(0xFF082F49),
    background = Color(0xFF020617),
    onBackground = Color(0xFFF8FAFC),
    surface = Color(0xFF0F172A),
    onSurface = Color(0xFFF8FAFC),
    onSurfaceVariant = Color(0xFFCBD5E1),
    outline = Color(0xFF64748B),
)

@Composable
fun SolventaApp(auth: AuthViewModel? = null) {
    MaterialTheme(colorScheme = LoginColors) {
        if (auth == null) {
            LoginScreen()
        } else {
            val state by auth.state.collectAsState()
            val user = state.user
            BackHandler(enabled = user == null && state.screen != AccessScreen.LOGIN) { auth.showLogin() }
            if (user == null) {
                when (state.screen) {
                    AccessScreen.LOGIN -> LoginScreen(loading = state.loading, error = state.error,
                        onLogin = auth::login, initialEmail = state.email, onCreateAccount = auth::showRegistration)
                    AccessScreen.REGISTER -> RegistrationScreen(loading = state.loading, error = state.error,
                        onRegister = auth::register, onBack = auth::showLogin)
                    AccessScreen.REGISTERED -> RegistrationSuccessScreen(state.email, auth::showLogin)
                }
            } else {
                AccountScreen(user, state.loading, auth::logout)
            }
        }
    }
}
