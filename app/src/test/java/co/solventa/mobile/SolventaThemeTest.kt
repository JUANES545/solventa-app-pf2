package co.solventa.mobile

import android.app.Application
import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.junit4.createComposeRule
import co.solventa.mobile.core.designsystem.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SolventaThemeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun semanticTextAndControlEdgesMeetContrastInBothThemes() {
        listOf(SolventaLightColors, SolventaDarkColors).forEach { colors ->
            listOf(colors.onPrimary to colors.primary, colors.onSurface to colors.surface,
                colors.onSurfaceVariant to colors.surface, colors.onBackground to colors.background,
                colors.error to colors.background, colors.tertiary to colors.background,
                colors.onSurface to colors.surfaceContainerLow).forEach { (foreground, background) ->
                assertTrue("Text contrast ${contrast(foreground, background)}", contrast(foreground, background) >= 4.5)
            }
            listOf(colors.surface, colors.background).forEach { background ->
                assertTrue("Control edge contrast", contrast(colors.outline, background) >= 3.0)
            }
        }
    }

    @Test fun themeFollowsSystemNightConfigurationWithoutScreenOverrides() {
        val night = androidx.compose.runtime.mutableStateOf(false)
        var background = Color.Unspecified
        compose.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                uiMode = if (night.value) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                SolventaTheme { background = MaterialTheme.colorScheme.background }
            }
        }
        compose.runOnIdle { assertEquals(SolventaLightColors.background, background); night.value = true }
        compose.runOnIdle { assertEquals(SolventaDarkColors.background, background) }
    }

    private fun contrast(a: Color, b: Color): Double {
        val first = a.luminance().toDouble()
        val second = b.luminance().toDouble()
        return (maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05)
    }
}
