package co.solventa.mobile

import android.app.Application
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import co.solventa.mobile.core.designsystem.SolventaTheme
import co.solventa.mobile.feature.auth.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w640dp-h640dp")
class AdaptiveAccessTest(private val dark: Boolean, private val landscape: Boolean) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0},landscape={1}")
        fun parameters() = listOf(arrayOf(false, false), arrayOf(true, false),
            arrayOf(false, true), arrayOf(true, true))
    }

    @get:Rule val compose = createComposeRule()

    @Test fun accessRegistrationConfirmationAndAccountRemainOperableAtTwoHundredPercent() {
        val screen = mutableStateOf(0)
        var submissions = 0
        var signIns = 0
        var logout = 0
        lateinit var view: View
        var imeBottom = 0
        compose.setContent {
            view = LocalView.current
            imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
            SolventaTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                    Box(Modifier.requiredSize(if (landscape) 640.dp else 320.dp, if (landscape) 320.dp else 480.dp)) {
                        when (screen.value) {
                            0 -> LoginScreen(onLogin = { _, _ -> submissions++ }, onCreateAccount = { screen.value = 1 })
                            1 -> RegistrationScreen(onRegister = { _, _, _, _ -> submissions++ })
                            2 -> RegistrationReferenceScreen(onContinue = {
                                assertTrue(it)
                                screen.value = 3
                            }, onBack = { screen.value = 1 })
                            3 -> RegistrationSuccessScreen("cliente@example.org", onSignIn = { signIns++ })
                            else -> AccountScreen(AuthUser("id", "Cliente", "cliente@example.org"), false,
                                onLogout = { logout++ })
                        }
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("Correo electrónico").performScrollTo().performTextInput("cliente@example.org")
        compose.onNodeWithContentDescription("Correo electrónico").performImeAction()
        compose.onNodeWithContentDescription("Contraseña").assertIsFocused().performTextInput("password")
        compose.runOnIdle {
            ViewCompat.dispatchApplyWindowInsets(view, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 120))
                .setVisible(WindowInsetsCompat.Type.ime(), true).build())
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("Compose must receive the simulated IME inset", 120, imeBottom) }
        compose.onNodeWithText("Ingresar").performScrollTo().assertIsDisplayed().performClick()
        compose.onNode(hasText("Crear cuenta") and hasClickAction()).performScrollTo().assertIsDisplayed().performClick()
        val fields = listOf("Nombre completo", "Correo electrónico", "Contraseña", "Confirmar contraseña")
        fields.zip(listOf("Cliente", "cliente@example.org", "password", "password")).forEach { (name, value) ->
            compose.onNodeWithContentDescription(name).performScrollTo().assertIsDisplayed().performTextInput(value)
            compose.onNodeWithContentDescription(name).performImeAction()
        }
        compose.onNodeWithText("Continuar").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(3, submissions); screen.value = 2 }
        compose.onNodeWithText("Continuar").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Acepto los términos y el tratamiento de datos personales.")
            .performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Continuar").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithText("Iniciar sesión").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, signIns); screen.value = 4 }
        compose.onNodeWithText("Cerrar sesión").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, logout) }
    }
}
