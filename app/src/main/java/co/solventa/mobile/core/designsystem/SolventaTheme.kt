package co.solventa.mobile.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import co.solventa.mobile.R

internal val SolventaLightColors = lightColorScheme(
    primary = Color(0xFF123B6D), onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E8FB), onPrimaryContainer = Color(0xFF0B1F3A),
    secondary = Color(0xFF007FA3), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8EEF6), onSecondaryContainer = Color(0xFF0B1F3A),
    tertiary = Color(0xFF047857), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE8EEF6), onTertiaryContainer = Color(0xFF0B1F3A),
    background = Color(0xFFF7F9FC), onBackground = Color(0xFF0B1F3A),
    surface = Color.White, onSurface = Color(0xFF0B1F3A),
    surfaceVariant = Color(0xFFE8EEF6), onSurfaceVariant = Color(0xFF475569),
    surfaceContainerLow = Color(0xFFF1F5FA),
    surfaceContainer = Color(0xFFE8EEF6),
    surfaceContainerLowest = Color.White, surfaceContainerHigh = Color(0xFFE8EEF6),
    surfaceContainerHighest = Color(0xFFE8EEF6),
    surfaceDim = Color(0xFFE8EEF6), surfaceBright = Color.White, surfaceTint = Color(0xFF123B6D),
    inverseSurface = Color(0xFF1E2640), inverseOnSurface = Color.White, inversePrimary = Color(0xFF00D2FF),
    outline = Color(0xFF64748B), outlineVariant = Color(0xFFCBD5E1),
    error = Color(0xFFB42318), onError = Color.White,
    errorContainer = Color(0xFFFEE4E2), onErrorContainer = Color(0xFF7A271A),
)

internal val SolventaDarkColors = darkColorScheme(
    primary = Color(0xFF00D2FF), onPrimary = Color(0xFF001018),
    primaryContainer = Color(0xFF003B49), onPrimaryContainer = Color(0xFF8BE9FF),
    secondary = Color(0xFF00D2FF), onSecondary = Color(0xFF001018),
    secondaryContainer = Color(0xFF27324F), onSecondaryContainer = Color.White,
    tertiary = Color(0xFF10B981), onTertiary = Color(0xFF00150E),
    tertiaryContainer = Color(0xFF27324F), onTertiaryContainer = Color.White,
    background = Color(0xFF0B0F19), onBackground = Color.White,
    surface = Color(0xFF1E2640), onSurface = Color.White,
    surfaceVariant = Color(0xFF27324F), onSurfaceVariant = Color(0xFFCBD5E1),
    surfaceContainerLow = Color(0xFF101625),
    surfaceContainer = Color(0xFF1E2640),
    surfaceContainerLowest = Color(0xFF0B0F19), surfaceContainerHigh = Color(0xFF27324F),
    surfaceContainerHighest = Color(0xFF27324F),
    surfaceDim = Color(0xFF0B0F19), surfaceBright = Color(0xFF27324F), surfaceTint = Color(0xFF00D2FF),
    inverseSurface = Color.White, inverseOnSurface = Color(0xFF0B1F3A), inversePrimary = Color(0xFF123B6D),
    outline = Color(0xFF71829C), outlineVariant = Color(0xFF334155),
    error = Color(0xFFFF8A80), onError = Color(0xFF280000),
    errorContainer = Color(0xFF4A1418), onErrorContainer = Color(0xFFFFDAD6),
)

@OptIn(ExperimentalTextApi::class)
private fun fontFamily(resource: Int) = FontFamily(
    listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { weight ->
        Font(resource, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))
    },
)

private val displayFamily = fontFamily(R.font.geologica_variable)
private val bodyFamily = fontFamily(R.font.afacad_flux_variable)
private val baseline = Typography()
internal val SolventaTypography = Typography(
    displayLarge = baseline.displayLarge.copy(fontFamily = displayFamily),
    displayMedium = baseline.displayMedium.copy(fontFamily = displayFamily),
    displaySmall = baseline.displaySmall.copy(fontFamily = displayFamily),
    headlineLarge = baseline.headlineLarge.copy(fontFamily = displayFamily),
    headlineMedium = baseline.headlineMedium.copy(fontFamily = displayFamily),
    headlineSmall = baseline.headlineSmall.copy(fontFamily = displayFamily),
    titleLarge = baseline.titleLarge.copy(fontFamily = displayFamily),
    titleMedium = baseline.titleMedium.copy(fontFamily = displayFamily),
    titleSmall = baseline.titleSmall.copy(fontFamily = displayFamily),
    bodyLarge = baseline.bodyLarge.copy(fontFamily = bodyFamily),
    bodyMedium = baseline.bodyMedium.copy(fontFamily = bodyFamily),
    bodySmall = baseline.bodySmall.copy(fontFamily = bodyFamily),
    labelLarge = baseline.labelLarge.copy(fontFamily = bodyFamily),
    labelMedium = baseline.labelMedium.copy(fontFamily = bodyFamily),
    labelSmall = baseline.labelSmall.copy(fontFamily = bodyFamily),
)

internal object SolventaLayout {
    val horizontalPadding = 20.dp
    val verticalPadding = 16.dp
    val sectionGap = 16.dp
    val controlShape = RoundedCornerShape(12.dp)
    val controlHeight = 52.dp
    val contentWidth = 560.dp
}

@Composable
internal fun SolventaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) SolventaDarkColors else SolventaLightColors,
        typography = SolventaTypography,
        shapes = Shapes(small = RoundedCornerShape(8.dp), medium = SolventaLayout.controlShape,
            large = RoundedCornerShape(24.dp)),
        content = content,
    )
}
