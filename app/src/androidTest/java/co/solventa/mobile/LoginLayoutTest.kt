package co.solventa.mobile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test

class LoginLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun smallViewportAndLargeFontKeepFieldsAndControlsReachable() {
        composeRule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f)) {
                Box(Modifier.requiredSize(320.dp, 480.dp)) { SolventaApp() }
            }
        }
        composeRule.onNodeWithText("Inicia sesión").performScrollTo().assertIsDisplayed()
        val emailField = composeRule.onNodeWithContentDescription("Correo electrónico")
        emailField.performScrollTo().assertIsDisplayed().performTextInput("persona@example.org")
        emailField.performImeAction()
        val passwordField = composeRule.onNodeWithContentDescription("Contraseña")
        passwordField.assertIsFocused().performScrollTo().assertIsDisplayed().performTextInput("secreto")
        passwordField.performImeAction()
        listOf("Ingresar", "Consultar biometría", "Crear cuenta").forEach {
            composeRule.onNodeWithText(it).performScrollTo().assertIsDisplayed()
        }
        composeRule.onNodeWithText("Olvidé mi contraseña").assertDoesNotExist()
    }
}
