package co.solventa.mobile

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import co.solventa.mobile.feature.auth.LoginScreen
import co.solventa.mobile.feature.auth.AuthViewModel
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

// Provisional MVP palette; exact Figma tokens are pending design access.
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
            if (user == null) {
                LoginScreen(loading = state.loading, error = state.error, onLogin = auth::login)
            } else {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.safeDrawingPadding().padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(stringResource(R.string.session_greeting, user.name), style = MaterialTheme.typography.headlineSmall)
                        Text(user.email)
                        Text(stringResource(R.string.session_confirmed))
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = auth::logout, enabled = !state.loading) {
                            Text(stringResource(R.string.sign_out))
                        }
                    }
                }
            }
        }
    }
}
