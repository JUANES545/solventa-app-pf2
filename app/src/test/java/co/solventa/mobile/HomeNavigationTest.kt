package co.solventa.mobile

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import co.solventa.mobile.core.designsystem.SolventaTheme
import co.solventa.mobile.feature.auth.*
import co.solventa.mobile.feature.home.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class HomeNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val user = AuthUser("synthetic", "Cliente", "cliente@example.org")

    @Test fun homeRemovesRepeatedInstructionsButKeepsAllThreeActions() {
        var actions = 0
        compose.setContent {
            SolventaTheme { HomeScreen(user, false, false, { actions++ }, { actions++ }, { actions++ }) }
        }
        compose.onNodeWithText("Tus gestiones de Solventa, en un solo lugar.").assertDoesNotExist()
        compose.onNodeWithText("Reporta lo que pasó").assertDoesNotExist()
        compose.onNodeWithText("Reportar un siniestro").performScrollTo().performClick()
        compose.onNodeWithText("Mis siniestros").performScrollTo().performClick()
        compose.onNodeWithText("Evidencias").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(3, actions) }
    }

    @Test fun homeKeepsIndependentStatusAccessAndLeavesAccountActionsOut() {
        var status = 0
        compose.setContent {
            SolventaTheme {
                HomeScreen(user, false, false, null, { status++ }, null)
            }
        }
        compose.onNodeWithText("Mis siniestros").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, status) }
        compose.onNodeWithText("Cerrar sesión").assertDoesNotExist()
        compose.onNodeWithText("Habilitar acceso biométrico").assertDoesNotExist()
    }

    @Test fun navigationUsesSelectedSemanticsAndDoesNotRepeatSelectedAction() {
        val selected = mutableStateOf(HomeDestination.HOME)
        var selections = 0
        compose.setContent {
            SolventaTheme {
                HomeNavigation(selected.value, true, true, {
                    selections++
                    selected.value = it
                }) { Text("Contenido") }
            }
        }
        compose.onNodeWithContentDescription("Inicio").assertIsSelected().performClick()
        compose.onNodeWithContentDescription("Notificaciones").performClick().assertIsSelected()
        compose.onNodeWithContentDescription("Notificaciones").performClick()
        compose.onNodeWithContentDescription("Mi cuenta").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(2, selections) }
    }

    @Test fun busyHomePreventsOperationsAndShowsOfflineWithoutInventedData() {
        var actions = 0
        compose.setContent {
            SolventaTheme {
                Box(Modifier.requiredSize(320.dp, 480.dp)) {
                    HomeScreen(user, true, true, { actions++ }, { actions++ }, { actions++ })
                }
            }
        }
        compose.onNodeWithText("Reportar un siniestro").performScrollTo().assertIsNotEnabled().performClick()
        compose.onNodeWithText("Mis siniestros").performScrollTo().assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(0, actions) }
        compose.onNodeWithText("Mis pólizas").assertDoesNotExist()
    }

    @Test fun accountNavigationAndSystemBackDoNotLogOutAndLogoutRemovesProtectedUi() {
        val grant = AuthGrant(user, "synthetic-access", "synthetic-refresh", Long.MAX_VALUE, Long.MAX_VALUE)
        val api = object : AuthApi {
            override fun register(name: String, email: String, password: String) = user
            override fun login(email: String, password: String) = grant
            override fun refresh(token: String) = grant
            override fun current(token: String) = user
            override fun logout(accessToken: String, refreshToken: String) {}
        }
        var stored: AuthGrant? = grant
        val store = object : AuthStore {
            override fun read() = stored
            override fun write(grant: AuthGrant) { stored = grant }
            override fun clear() { stored = null }
        }
        val auth = AuthViewModel(AuthRepository(api, store))
        compose.setContent { SolventaApp(auth) }
        compose.runOnIdle { auth.restore() }
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            auth.state.value.user != null && !auth.state.value.loading
        }
        compose.onNodeWithContentDescription("Inicio").assertIsSelected()
        compose.onNodeWithText(user.email).assertDoesNotExist()
        compose.onNodeWithContentDescription("Mi cuenta").performClick().assertIsSelected()
        compose.onNodeWithText(user.email).assertIsDisplayed()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithContentDescription("Inicio").assertIsSelected()
        assertNotNull(auth.state.value.user)
        compose.onNodeWithContentDescription("Mi cuenta").performClick()
        compose.onNodeWithText("Cerrar sesión").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            auth.state.value.user == null && !auth.state.value.loading
        }
        compose.onNodeWithText(user.email).assertDoesNotExist()
        compose.onNodeWithContentDescription("Mi cuenta").assertDoesNotExist()
        compose.onNodeWithText("Inicia sesión").assertIsDisplayed()
    }
}
