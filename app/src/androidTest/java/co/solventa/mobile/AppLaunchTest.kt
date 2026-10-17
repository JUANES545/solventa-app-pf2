package co.solventa.mobile

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
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

@RunWith(AndroidJUnit4::class)
class AppLaunchTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun applicationLaunchesAndDisplaysItsName() {
        val appName = composeRule.activity.getString(R.string.app_name)
        composeRule.onNodeWithText(appName).assertIsDisplayed()
        composeRule.onNodeWithText("Protección siempre contigo").assertIsDisplayed()
        composeRule.onNodeWithText("Accede de forma rápida y segura a tus pólizas").assertIsDisplayed()
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
        composeRule.onNodeWithText("Protección siempre contigo").assertIsDisplayed()
    }

    @Test
    fun emptyLoginShowsValidationAndUnavailableFlowsAreDisabled() {
        composeRule.onNodeWithText("Ingresar").performScrollTo().performClick()
        composeRule.onNodeWithText("Ingresa tu correo y contraseña.").assertIsDisplayed()
        listOf("Ingresar con huella o rostro", "Olvidé mi contraseña", "Crear cuenta").forEach {
            composeRule.onNodeWithText(it).performScrollTo().assertIsNotEnabled()
        }
        composeRule.onNodeWithText("Protección siempre contigo").performScrollTo().assertIsDisplayed()
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
