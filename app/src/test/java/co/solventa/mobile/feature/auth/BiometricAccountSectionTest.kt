package co.solventa.mobile.feature.auth

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import co.solventa.mobile.core.designsystem.SolventaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
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
        val confirm = hasText("Habilitar acceso biométrico") and hasClickAction() and hasAnyAncestor(isDialog())
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
}
