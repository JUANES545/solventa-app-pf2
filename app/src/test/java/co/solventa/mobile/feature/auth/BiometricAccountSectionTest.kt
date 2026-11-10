package co.solventa.mobile.feature.auth

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import co.solventa.mobile.R
import co.solventa.mobile.core.designsystem.SolventaTheme
import co.solventa.mobile.core.ui.SolventaPage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
// Legacy graphics do not measure wrapping with the app's font metrics.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BiometricAccountSectionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun disabledOrBusyManagementCannotEnrollLoadOrRevoke() {
        val enabled = mutableStateOf(false)
        val state = mutableStateOf(BiometricUiState(credentials = listOf(
            RemoteBiometricCredential("11111111-1111-4111-8111-111111111111", "2026-11-01T00:00:00Z", true),
            RemoteBiometricCredential("22222222-2222-4222-8222-222222222222", "2026-11-01T00:00:00Z", false),
        )))
        var actions = 0
        compose.setContent {
            SolventaTheme {
                BiometricAccountSection(state.value, enabled.value, { actions++ }, { actions++ }, { actions++ })
            }
        }
        compose.onNodeWithText("Habilitar acceso biométrico").assertIsNotEnabled()
        compose.onNodeWithText("Desactivar credencial 11111111").assertIsNotEnabled()
        compose.onNodeWithText("Desactivar credencial 22222222").assertDoesNotExist()
        compose.runOnIdle { enabled.value = true; state.value = state.value.copy(busy = true) }
        compose.onNodeWithText("Habilitar acceso biométrico").assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(busy = false) }
        compose.onNodeWithText("Habilitar acceso biométrico").performClick()
        compose.onNodeWithContentDescription("Contraseña actual").performTextInput("synthetic")
        val confirm = hasText("Habilitar") and hasClickAction() and hasAnyAncestor(isDialog())
        compose.runOnIdle { enabled.value = false }
        compose.onNode(confirm).assertIsNotEnabled()
        compose.runOnIdle { enabled.value = true; state.value = state.value.copy(busy = true) }
        compose.onNode(confirm).assertIsNotEnabled()
        assertEquals(0, actions)
        compose.onNodeWithText("Ahora no").performClick()
        compose.runOnIdle {
            state.value = state.value.copy(busy = false, localCredential = BiometricCredential(
                "22222222-2222-4222-8222-222222222222", "synthetic", "actor", byteArrayOf(1)))
        }
        compose.onNodeWithText("Desactivar credencial 22222222").assertIsEnabled().performClick()
        assertEquals(1, actions)
    }

    @Test fun compactEnrollmentKeepsAndroidConsentAndPasswordBehindExplicitConfirmation() {
        var enrolled: String? = null
        compose.setContent {
            SolventaTheme(darkTheme = true) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                    Box(Modifier.requiredSize(320.dp, 480.dp)) {
                        SolventaPage {
                            BiometricAccountSection(BiometricUiState(), true, { enrolled = it }, {}, {})
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Habilitar acceso biométrico").performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Habilitar acceso biométrico", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(TextAlign.Center, layouts.single().layoutInput.style.textAlign)
        assertTrue("Exercise a wrapped biometric label; lines=${layouts.single().lineCount}",
            layouts.single().lineCount > 1)
        assertFalse("The full biometric label must fit; size=${layouts.single().size}, " +
            "paragraph=${layouts.single().multiParagraph.width}x${layouts.single().multiParagraph.height}",
            layouts.single().didOverflowWidth || layouts.single().didOverflowHeight)
        compose.onNodeWithText("Habilitar acceso biométrico").performClick()
        val dialog = hasAnyAncestor(isDialog())
        compose.onNode(hasText("Acceso biométrico") and dialog).assertIsDisplayed()
        compose.onNode(hasText(org.robolectric.RuntimeEnvironment.getApplication()
            .getString(R.string.biometric_enrollment_consent)) and dialog).assertExists()
        val confirm = compose.onNode(hasText("Habilitar") and hasClickAction() and dialog)
        confirm.assertIsNotEnabled()
        assertNull(enrolled)
        compose.onNodeWithContentDescription("Contraseña actual").performScrollTo().performTextInput("synthetic")
        confirm.assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals("synthetic", enrolled)
        compose.onNode(isDialog()).assertDoesNotExist()
    }
}
