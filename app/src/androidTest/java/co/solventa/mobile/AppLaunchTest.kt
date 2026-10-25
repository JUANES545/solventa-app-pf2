package co.solventa.mobile

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest

@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class AppLaunchTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun applicationLaunchesAndDisplaysItsName() {
        val appName = composeRule.activity.getString(R.string.app_name)
        composeRule.onNodeWithText(appName).assertIsDisplayed()
        composeRule.onNodeWithText("Inicia sesión").assertIsDisplayed()
        composeRule.onNodeWithText("Accede de forma rápida y segura a tus pólizas").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Correo electrónico").assertInputText("")
        composeRule.onNodeWithContentDescription("Contraseña").assertInputText("")
    }

    @Test
    fun fieldsAreEditableAndPasswordIsMasked() {
        composeRule.onNodeWithContentDescription("Correo electrónico").performTextInput("persona@example.org")
        composeRule.onNodeWithContentDescription("Correo electrónico").assertInputText("persona@example.org")
        composeRule.onNodeWithContentDescription("Contraseña").performTextInput("secreto")
        composeRule.onNodeWithContentDescription("Contraseña")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            .assertInputText("•••••••")
    }

    @Test
    fun keyboardNextFocusesPasswordAndDoneClearsFocus() {
        composeRule.onNodeWithContentDescription("Correo electrónico").performClick().performImeAction()
        composeRule.onNodeWithContentDescription("Contraseña").assertIsFocused().performImeAction()
        composeRule.onNodeWithContentDescription("Contraseña")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Focused, false))
        composeRule.onNodeWithText("Inicia sesión").assertIsDisplayed()
    }

    @Test
    fun emptyLoginShowsValidationAndUnavailableFlowsAreDisabled() {
        composeRule.onNodeWithText("Ingresar").performScrollTo().performClick()
        composeRule.onNodeWithText("Ingresa tu correo y contraseña.").assertIsDisplayed()
        composeRule.onNodeWithText("Olvidé mi contraseña").assertDoesNotExist()
        composeRule.onNodeWithText("Consultar biometría").performScrollTo().assertIsEnabled().performClick()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.biometric_service_limited))
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Usar contraseña").performClick()
        composeRule.onNodeWithContentDescription("Correo electrónico").assertIsFocused()
        composeRule.onNodeWithText("Crear cuenta").performScrollTo().assertIsEnabled()
        composeRule.onNodeWithText("Inicia sesión").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun credentialsAreNotRestoredAfterActivityRecreation() {
        composeRule.onNodeWithContentDescription("Correo electrónico").performTextInput("persona@example.org")
        composeRule.onNodeWithContentDescription("Contraseña").performTextInput("secreto")
        composeRule.activityRule.scenario.recreate()
        composeRule.onNodeWithContentDescription("Correo electrónico").assertInputText("")
        composeRule.onNodeWithContentDescription("Contraseña").assertInputText("")
    }
}

private fun SemanticsNodeInteraction.assertInputText(value: String) =
    assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(value)))
