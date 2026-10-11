package co.solventa.mobile

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import co.solventa.mobile.feature.auth.LoginScreen

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
fun SolventaApp() {
    MaterialTheme(colorScheme = LoginColors) {
        LoginScreen()
    }
}
