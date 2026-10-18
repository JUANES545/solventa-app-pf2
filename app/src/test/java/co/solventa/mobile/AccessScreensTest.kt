package co.solventa.mobile

import android.app.Application
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import co.solventa.mobile.feature.auth.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class AccessScreensTest {
    @get:Rule val compose = createComposeRule()

    @Test fun loginValidatesCredentialsAndOffersRegistrationAtLargeFontSize() {
        var submitted: Pair<String, String>? = null
        var register = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                Box(Modifier.requiredSize(320.dp, 480.dp)) {
                    LoginScreen(onLogin = { email, password -> submitted = email to password },
                        onCreateAccount = { register = true })
                }
            }
        }
        compose.onNodeWithContentDescription("Correo electrónico").performScrollTo().performTextInput("cliente@example.org")
        compose.onNodeWithContentDescription("Correo electrónico").performImeAction()
        compose.onNodeWithContentDescription("Contraseña").assertIsFocused().performTextInput("test-password")
        compose.onNodeWithContentDescription("Contraseña").performImeAction()
        compose.runOnIdle { assertEquals("cliente@example.org" to "test-password", submitted) }
        compose.onNodeWithText("Ingresar").performScrollTo().performClick()
        compose.onNodeWithText("Crear cuenta").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(register) }
    }

    @Test fun loginDisplaysEveryRecoverableErrorAndDisablesActionsWhileLoading() {
        val error = mutableStateOf<AuthError?>(null)
        val loading = mutableStateOf(false)
        compose.setContent { LoginScreen(error = error.value, loading = loading.value) }
        for (reason in AuthError.entries) {
            compose.runOnIdle { error.value = reason }
            val message = org.robolectric.RuntimeEnvironment.getApplication().getString(authErrorMessage(reason))
            compose.onNodeWithText(message).performScrollTo().assertIsDisplayed()
        }
        compose.runOnIdle { loading.value = true }
        compose.onNodeWithText("Ingresando…").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Crear cuenta").performScrollTo().assertIsNotEnabled()
    }

    @Test fun registrationSubmitsMatchingCredentialsAndMasksThemByDefault() {
        var result: List<String>? = null
        var back = false
        compose.setContent {
            RegistrationScreen(onRegister = { name, email, password, confirmation ->
                result = listOf(name, email, password, confirmation)
            }, onBack = { back = true })
        }
        val fields = listOf("Nombre completo", "Correo electrónico", "Contraseña", "Confirmar contraseña")
        val values = listOf("Cliente", "cliente@example.org", "test-password", "test-password")
        fields.zip(values).forEach { (field, value) ->
            compose.onNodeWithContentDescription(field).performScrollTo().performTextInput(value)
            compose.onNodeWithContentDescription(field).performImeAction()
        }
        compose.runOnIdle { assertEquals(values, result) }
        compose.onNodeWithContentDescription("Contraseña").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.onNodeWithText("Mostrar contraseña").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Contraseña").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Password))
        compose.onNodeWithText("Ocultar contraseña").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Contraseña").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.onNodeWithText("Crear cuenta").performScrollTo().performClick()
        compose.onNodeWithText("Volver a iniciar sesión").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(back) }
    }

    @Test fun registrationErrorsAndLoadingNeverAnnounceSuccess() {
        val error = mutableStateOf<AuthError?>(AuthError.EMAIL_EXISTS)
        val loading = mutableStateOf(false)
        compose.setContent { RegistrationScreen(error = error.value, loading = loading.value) }
        compose.onNodeWithText("Este correo ya tiene una cuenta. Inicia sesión con ella.").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { error.value = AuthError.UNAVAILABLE }
        compose.onNodeWithText("No pudimos confirmar el registro. Revisa tu conexión o intenta iniciar sesión si ya creaste tu cuenta.")
            .performScrollTo().assertIsDisplayed()
        compose.runOnIdle { loading.value = true }
        compose.onNodeWithText("Creando cuenta…").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Volver a iniciar sesión").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Tu cuenta está lista").assertDoesNotExist()
    }

    @Test fun confirmationOffersExplicitSignIn() {
        var clicked = false
        compose.setContent { RegistrationSuccessScreen("cliente@example.org") { clicked = true } }
        compose.onNodeWithText("Tu cuenta está lista").assertIsDisplayed()
        compose.onNodeWithText("cliente@example.org").assertIsDisplayed()
        compose.onNodeWithText("Ir a iniciar sesión").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(clicked) }
    }

    @Test fun accountShowsUserAndReportsCheckingAndSigningOutStates() {
        val loading = mutableStateOf(true)
        val user = AuthUser("id", "Cliente", "cliente@example.org")
        compose.setContent { AccountScreen(user, loading.value) { loading.value = true } }
        compose.onNodeWithText("Mi cuenta").assertIsDisplayed()
        compose.onNodeWithText(user.email).assertIsDisplayed()
        compose.onNodeWithText("Verificando sesión…").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { loading.value = false }
        compose.onNodeWithText("Cerrar sesión").performScrollTo().performClick()
        compose.onNodeWithText("Cerrando sesión…").performScrollTo().assertIsNotEnabled()
    }

    @Test fun applicationRoutesRegistrationAndConfirmationWithoutAutomaticSession() {
        var storedGrant: AuthGrant? = null
        val user = AuthUser("id", "Cliente", "cliente@example.org")
        val api = object : AuthApi {
            override fun register(name: String, email: String, password: String) = user
            override fun login(email: String, password: String) = AuthGrant(user, "access", "refresh", Long.MAX_VALUE, Long.MAX_VALUE)
            override fun refresh(token: String) = login("", "")
            override fun current(token: String) = user
            override fun logout(accessToken: String, refreshToken: String) {}
        }
        val store = object : AuthStore {
            override fun read() = storedGrant
            override fun write(grant: AuthGrant) { storedGrant = grant }
            override fun clear() { storedGrant = null }
        }
        val auth = AuthViewModel(AuthRepository(api, store))
        compose.setContent { SolventaApp(auth) }
        compose.onNodeWithText("Crear cuenta").performScrollTo().performClick()
        compose.onNodeWithText("Tu protección comienza aquí").assertIsDisplayed()
        compose.runOnIdle { auth.register("Cliente", user.email, "password", "password") }
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            auth.state.value.screen == AccessScreen.REGISTERED
        }
        compose.onNodeWithText("Ir a iniciar sesión").performScrollTo().performClick()
        compose.runOnIdle { auth.login(user.email, "password") }
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            auth.state.value.user != null
        }
        compose.onNodeWithText("Mi cuenta").assertIsDisplayed()
    }
}
