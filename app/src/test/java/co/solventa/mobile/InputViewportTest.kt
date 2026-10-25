package co.solventa.mobile

import android.app.Application
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import co.solventa.mobile.core.designsystem.SolventaTheme
import co.solventa.mobile.feature.auth.LoginScreen
import co.solventa.mobile.feature.auth.RegistrationScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w411dp-h914dp-280dpi")
class InputViewportTest(private val registration: Boolean) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "registration={0}")
        fun parameters() = listOf(arrayOf(false), arrayOf(true))
    }
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var view: View
    private var imeBottom = 0
    private var submissions = 0
    private val fields get() = if (registration)
        listOf("Nombre completo", "Correo electrónico", "Contraseña", "Confirmar contraseña")
        else listOf("Correo electrónico", "Contraseña")

    private fun show() {
        compose.activity.enableEdgeToEdge()
        compose.setContent {
            view = LocalView.current
            imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
            SolventaTheme {
                if (registration) RegistrationScreen(onRegister = { _, _, _, _ -> submissions++ })
                else LoginScreen(onLogin = { _, _ -> submissions++ })
            }
        }
    }

    private fun setIme(height: Int) {
        compose.runOnIdle {
            ViewCompat.dispatchApplyWindowInsets(view, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 68, 0, 0))
                .setVisible(WindowInsetsCompat.Type.statusBars(), true)
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, height))
                .setVisible(WindowInsetsCompat.Type.ime(), height > 0).build())
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1600f, view.height.toFloat(), 1f)
            assertEquals(height, imeBottom)
        }
    }

    @Test fun normalImeKeepsEachFocusedFieldCompleteInsideTheRemainingViewport() {
        show()
        compose.onNodeWithContentDescription("Correo electrónico").performScrollTo().performClick()
        setIme(650)
        fields.forEach { label ->
            val field = compose.onNodeWithContentDescription(label).performScrollTo().performClick()
                .assertIsFocused().assertIsDisplayed().fetchSemanticsNode()
            assertEquals(field.layoutInfo.height.toFloat(), field.boundsInRoot.height, 1f)
            assertTrue(field.boundsInRoot.top >= 68f)
            assertTrue(field.boundsInRoot.bottom <= view.height - imeBottom + 1f)
        }
        compose.runOnIdle { assertEquals(0, submissions) }
    }

    @Test fun imeCoveringTheWholeWindowDoesNotDiscardInputsOrSubmit() {
        show()
        fields.forEach { label ->
            compose.onNodeWithContentDescription(label).performScrollTo().performTextInput("retained")
        }
        compose.onNodeWithContentDescription("Correo electrónico").performScrollTo().performClick()
        setIme(1532)
        val scroll = compose.onNode(hasScrollAction()).fetchSemanticsNode()
        assertEquals("68px status + 1532px IME exhaust this window", 0f, scroll.boundsInRoot.height, 0f)
        compose.runOnIdle { assertEquals(0, submissions) }
        setIme(0)
        fields.forEach { label ->
            val password = label == "Contraseña" || label == "Confirmar contraseña"
            compose.onNodeWithContentDescription(label).performScrollTo().assertIsDisplayed()
                .assertTextEquals(if (password) "\u2022".repeat("retained".length) else "retained")
        }
        compose.onNodeWithContentDescription("Correo electrónico").assertIsFocused()
        compose.runOnIdle { assertEquals(0, submissions) }
    }
}
