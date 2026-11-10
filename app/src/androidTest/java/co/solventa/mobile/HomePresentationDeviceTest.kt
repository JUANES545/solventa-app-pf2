package co.solventa.mobile

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import co.solventa.mobile.core.designsystem.SolventaTheme
import co.solventa.mobile.feature.auth.AccountScreen
import co.solventa.mobile.feature.auth.AuthUser
import co.solventa.mobile.feature.home.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class HomePresentationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun homeAndAccountInBothThemesAndLargeText() {
        val destination = mutableStateOf(HomeDestination.HOME)
        val dark = mutableStateOf(false)
        val scale = mutableStateOf(1f)
        val user = AuthUser("synthetic-ui", "Cliente Solventa", "cliente@example.org")
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale.value)) {
                SolventaTheme(dark.value) {
                    key(dark.value, scale.value) {
                        HomeNavigation(destination.value, true, true, { destination.value = it }) {
                            when (destination.value) {
                                HomeDestination.HOME -> HomeScreen(user, false, false, {}, {}, {})
                                HomeDestination.ACCOUNT -> AccountScreen(user, false, {})
                                HomeDestination.NOTIFICATIONS -> Text("Avisos sintéticos")
                            }
                        }
                    }
                }
            }
        }
        for (night in listOf(false, true)) {
            for (font in listOf(1f, 2f)) {
                compose.runOnIdle {
                    dark.value = night
                    scale.value = font
                    destination.value = HomeDestination.HOME
                }
                compose.onNodeWithContentDescription("Inicio").assertIsSelected().assertIsDisplayed()
                if (font >= 1.5f) compose.onNodeWithText("Avisos").assertIsDisplayed()
                compose.onNodeWithText("Reportar un siniestro").performScrollTo().assertIsDisplayed()
                capture("home-${if (night) "dark" else "light"}-${font.toInt()}")
                compose.onNodeWithText("Mis siniestros").performScrollTo().assertIsDisplayed()
                compose.onNodeWithContentDescription("Mi cuenta").performClick().assertIsSelected()
                compose.onNodeWithText(user.email).performScrollTo().assertIsDisplayed()
                capture("account-${if (night) "dark" else "light"}-${font.toInt()}")
                compose.onNodeWithText("Cerrar sesión").performScrollTo().assertIsDisplayed()
            }
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val folder = File(compose.activity.filesDir, "ui-home").apply { check(isDirectory || mkdirs()) }
        File(folder, "$name.png").outputStream().use {
            check(compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
