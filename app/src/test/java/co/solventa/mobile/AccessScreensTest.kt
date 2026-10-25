package co.solventa.mobile

import android.app.Application
import android.os.Looper
import androidx.biometric.BiometricManager
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

    private fun registrationAuth(onRegister: () -> Unit = {}): AuthViewModel {
        val user = AuthUser("id", "Cliente", "cliente@example.org")
        val api = object : AuthApi {
            override fun register(name: String, email: String, password: String): AuthUser {
                onRegister()
                return user
            }
            override fun login(email: String, password: String): AuthGrant = error("Explicit login required")
            override fun refresh(token: String): AuthGrant = error("No registration session")
            override fun current(token: String): AuthUser = error("No registration session")
            override fun logout(accessToken: String, refreshToken: String) {}
        }
        val store = object : AuthStore {
            override fun read(): AuthGrant? = null
            override fun write(grant: AuthGrant) { error("Registration must not store a session") }
            override fun clear() {}
        }
        return AuthViewModel(AuthRepository(api, store))
    }

    @Config(sdk = [26, 28, 29, 35])
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
        compose.onNodeWithText("Consultar biometría").performScrollTo().performClick()
        compose.onNodeWithText("Usar contraseña").performClick()
        compose.onNodeWithContentDescription("Correo electrónico").assertIsFocused()
        compose.onNodeWithContentDescription("Correo electrónico").performScrollTo().performTextInput("cliente@example.org")
        compose.onNodeWithContentDescription("Correo electrónico").performImeAction()
        compose.onNodeWithContentDescription("Contraseña").assertIsFocused().performTextInput("test-password")
        compose.onNodeWithContentDescription("Contraseña").performImeAction()
        compose.runOnIdle { assertEquals("cliente@example.org" to "test-password", submitted) }
        compose.onNodeWithText("Ingresar").performScrollTo().performClick()
        compose.onNode(hasText("Crear cuenta") and hasClickAction()).performScrollTo().performClick()
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
        compose.onNode(hasText("Crear cuenta") and hasClickAction()).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Consultar biometría").performScrollTo().assertIsNotEnabled()
    }

    @Test fun biometricCapabilityInfoAlwaysExplainsServiceLimitAndReturnsToPasswordWithoutAuthenticating() {
        var status = BiometricManager.BIOMETRIC_SUCCESS
        var queries = 0
        var alternatives = 0
        val application = org.robolectric.RuntimeEnvironment.getApplication()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                Box(Modifier.requiredSize(320.dp, 480.dp)) {
                    BiometricAccessInfo(enabled = true, onUsePassword = { alternatives++ },
                        checkCapability = { queries++; status })
                }
            }
        }
        val statuses = listOf(
            BiometricManager.BIOMETRIC_SUCCESS, BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE, BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED, BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED,
            BiometricManager.BIOMETRIC_STATUS_UNKNOWN,
        )
        statuses.forEachIndexed { index, value ->
            status = value
            compose.onNodeWithText("Consultar biometría").performClick()
            compose.onNodeWithText("Biometría")
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            compose.onNodeWithText(application.getString(biometricCapabilityMessage(value)))
                .performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(application.getString(R.string.biometric_service_unavailable))
                .performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Usar contraseña").assertIsEnabled().performClick()
            compose.onNodeWithText("Biometría").assertDoesNotExist()
            compose.runOnIdle { assertEquals(index + 1, queries); assertEquals(index + 1, alternatives) }
        }
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
        compose.onNodeWithText("Mostrar contraseña").assertDoesNotExist()
        compose.onNodeWithContentDescription("Mostrar contraseña").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Contraseña").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Password))
        compose.onNodeWithContentDescription("Confirmar contraseña").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.onNodeWithContentDescription("Mostrar confirmación de contraseña").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Confirmar contraseña").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Password))
        compose.onNodeWithContentDescription("Ocultar contraseña").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Contraseña").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.onNodeWithText("Continuar").performScrollTo().performClick()
        compose.onNodeWithText("Volver al acceso").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(back) }
    }

    @Test fun registrationErrorsAndLoadingNeverAnnounceSuccess() {
        val error = mutableStateOf<AuthError?>(AuthError.EMAIL_EXISTS)
        val loading = mutableStateOf(false)
        compose.setContent { RegistrationScreen(error = error.value, loading = loading.value) }
        compose.onNodeWithText("Este correo ya tiene cuenta. Inicia sesión.").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { error.value = AuthError.UNAVAILABLE }
        compose.onNodeWithText("No pudimos confirmar el registro. Revisa tu conexión o inicia sesión si ya tienes cuenta.")
            .performScrollTo().assertIsDisplayed()
        compose.runOnIdle { loading.value = true }
        compose.onNodeWithText("Creando cuenta…").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Volver al acceso").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Cuenta creada").assertDoesNotExist()
    }

    @Test fun confirmationOffersExplicitSignIn() {
        var clicked = false
        compose.setContent { RegistrationSuccessScreen("cliente@example.org") { clicked = true } }
        compose.onNodeWithText("Cuenta creada").assertIsDisplayed()
        compose.onNodeWithText("cliente@example.org").assertIsDisplayed()
        compose.onNodeWithText("Iniciar sesión").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(clicked) }
    }

    @Test fun registrationPhotosAreBeforePostAndHaveNoLooseLoginOrSuccessEntry() {
        var posts = 0
        var opens = 0
        val auth = registrationAuth { posts++ }
        compose.setContent { SolventaApp(auth, onPhotoReview = { opens++ }) }
        compose.onNodeWithText("Documento y selfie").assertDoesNotExist()
        compose.onNode(hasText("Crear cuenta") and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithText("Términos y privacidad").assertDoesNotExist()
        listOf("Nombre completo", "Correo electrónico", "Contraseña", "Confirmar contraseña")
            .zip(listOf("Cliente", "cliente@example.org", "password", "password")).forEach { (label, value) ->
                compose.onNodeWithContentDescription(label).performScrollTo().performTextInput(value)
                compose.onNodeWithContentDescription(label).performImeAction()
            }
        compose.onNodeWithText("Términos y privacidad").assertIsDisplayed()
        compose.onNodeWithText("Continuar").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, opens); assertEquals(0, posts) }
        compose.onNodeWithText("Acepto los términos y el tratamiento de datos personales.").performScrollTo().performClick()
        compose.onNodeWithText("Continuar").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(AccessScreen.REGISTER_PHOTOS, auth.state.value.screen)
            assertEquals(1, opens)
            assertEquals(0, posts)
            assertNull(auth.state.value.user)
            auth.completeRegistrationPhotos()
        }
        compose.onNodeWithText("Finalizar registro").assertIsDisplayed()
        compose.onNodeWithText("Cuenta creada").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, posts) }
        compose.onNode(hasText("Crear cuenta") and hasClickAction()).performScrollTo().performClick()
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            auth.state.value.screen == AccessScreen.REGISTERED
        }
        compose.onNodeWithText("Cuenta creada").assertIsDisplayed()
        compose.onNodeWithText("Documento y selfie").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, posts)
            assertNull(auth.state.value.user)
            auth.confirmRegistration()
            assertEquals(1, posts)
        }
        compose.onNodeWithText("Iniciar sesión").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Contraseña").assertTextEquals("")
    }

    @Test fun cancellingOrDisposingRegistrationDiscardsCredentialsAndNeverPosts() {
        var posts = 0
        val mounted = mutableStateOf(true)
        val auth = registrationAuth { posts++ }
        compose.setContent { if (mounted.value) SolventaApp(auth, onPhotoReview = {}) }
        compose.runOnIdle {
            auth.showRegistration()
            auth.prepareRegistration("Cliente", "cliente@example.org", "password", "password", withPhotos = true)
            auth.continueRegistration(true)
        }
        compose.onNodeWithText("Cancelar registro").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Contraseña").assertTextEquals("")
        compose.onNodeWithContentDescription("Confirmar contraseña").assertTextEquals("")
        compose.runOnIdle {
            auth.completeRegistrationPhotos()
            auth.confirmRegistration()
            assertEquals(0, posts)
            auth.prepareRegistration("Cliente", "cliente@example.org", "password", "password", withPhotos = true)
            auth.continueRegistration(true)
            auth.completeRegistrationPhotos()
            mounted.value = false
        }
        compose.runOnIdle {
            auth.completeRegistrationPhotos()
            auth.confirmRegistration()
            assertEquals(AccessScreen.REGISTER, auth.state.value.screen)
            assertEquals(0, posts)
            mounted.value = true
        }
        compose.onNodeWithContentDescription("Contraseña").assertTextEquals("")
    }

    @Test fun accountShowsUserAndReportsCheckingAndSigningOutStates() {
        val loading = mutableStateOf(true)
        val user = AuthUser("id", "Cliente", "cliente@example.org")
        compose.setContent { AccountScreen(user, loading.value, onLogout = { loading.value = true }) }
        compose.onNodeWithText("Mi cuenta").assertIsDisplayed()
        compose.onNodeWithText(user.email).assertIsDisplayed()
        compose.onNodeWithText("Verificando sesión…").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { loading.value = false }
        compose.onNodeWithText("Cerrar sesión").performScrollTo().performClick()
        compose.onNodeWithText("Cerrando sesión…").performScrollTo().assertIsNotEnabled()
    }

    @Test fun applicationRoutesRegistrationAndConfirmationWithoutAutomaticSession() {
        var storedGrant: AuthGrant? = null
        var loginCalls = 0
        val user = AuthUser("id", "Cliente", "cliente@example.org")
        val api = object : AuthApi {
            override fun register(name: String, email: String, password: String) = user
            override fun login(email: String, password: String): AuthGrant {
                loginCalls++
                return AuthGrant(user, "access", "refresh", Long.MAX_VALUE, Long.MAX_VALUE)
            }
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
        compose.onNodeWithText("Consultar biometría").performScrollTo().performClick()
        compose.onNodeWithText("Usar contraseña").performClick()
        compose.runOnIdle {
            assertNull(auth.state.value.user)
            assertNull(storedGrant)
            assertEquals(0, loginCalls)
        }
        compose.onNode(hasText("Crear cuenta") and hasClickAction()).performScrollTo().performClick()
        compose.onNode(hasText("Crear cuenta") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertIsDisplayed()
        listOf("Nombre completo", "Correo electrónico", "Contraseña", "Confirmar contraseña")
            .zip(listOf("Cliente", user.email, "password", "password")).forEach { (label, value) ->
                compose.onNodeWithContentDescription(label).performScrollTo().performTextInput(value)
                compose.onNodeWithContentDescription(label).performImeAction()
            }
        compose.onNodeWithText("Acepto los términos y el tratamiento de datos personales.").performScrollTo().performClick()
        compose.onNodeWithText("Continuar").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            auth.state.value.screen == AccessScreen.REGISTERED
        }
        compose.onNodeWithText("Iniciar sesión").performScrollTo().performClick()
        compose.runOnIdle { auth.login(user.email, "password") }
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            auth.state.value.user != null
        }
        compose.onNodeWithText("Mi cuenta").assertIsDisplayed()
    }

    @Test fun termsFollowValidFormAndBackPreservesTransientInputsWithOptionalFinance() {
        val auth = registrationAuth()
        compose.setContent { SolventaApp(auth) }
        compose.onNode(hasText("Crear cuenta") and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithText("Términos y privacidad").assertDoesNotExist()
        compose.onNodeWithText("nombre@ejemplo.com").performScrollTo().assertIsDisplayed()
        val fields = listOf("Nombre completo", "Correo electrónico", "Contraseña", "Confirmar contraseña")
        val values = listOf("Cliente", "cliente@example.org", "password", "password")
        fields.zip(values).forEach { (label, value) ->
            compose.onNodeWithContentDescription(label).performScrollTo().performTextInput(value)
            compose.onNodeWithContentDescription(label).performImeAction()
        }
        compose.onNodeWithText("Términos y privacidad")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        val application = org.robolectric.RuntimeEnvironment.getApplication()
        compose.onNodeWithText(application.getString(R.string.registration_reference_notice)).performScrollTo().assertIsDisplayed()
        compose.onAllNodes(isToggleable()).assertCountEquals(2)
        compose.onNodeWithContentDescription("Contraseña").assertDoesNotExist()
        compose.onNodeWithText("Continuar").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(application.getString(R.string.registration_finance)).performScrollTo().assertIsOff().performClick()
        compose.onNodeWithText("Continuar").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(application.getString(R.string.registration_terms)).performScrollTo().performClick()
        compose.onNodeWithText("Continuar").performScrollTo().assertIsEnabled()
        compose.onNodeWithContentDescription("Volver al formulario").performClick()
        compose.onNodeWithContentDescription("Mostrar contraseña").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Mostrar confirmación de contraseña").performScrollTo().performClick()
        fields.zip(values).forEach { (label, value) ->
            compose.onNodeWithContentDescription(label).assertTextEquals(value)
        }
        compose.onNodeWithContentDescription("Ocultar contraseña").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Contraseña").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.onNodeWithText("Continuar").performScrollTo().performClick()
        compose.onAllNodes(isToggleable()).assertAll(isOff())
        compose.onNodeWithText("Continuar").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Volver al formulario").performClick()
        compose.runOnIdle {
            assertEquals(AccessScreen.REGISTER, auth.state.value.screen)
            assertNull(auth.state.value.user)
        }
    }

    @Test fun loginEyeUsesAnAccessibleIconAndNeverSubmitsOrChangesPassword() {
        var submissions = 0
        compose.setContent { LoginScreen(onLogin = { _, _ -> submissions++ }) }
        compose.onNodeWithText("nombre@ejemplo.com").assertIsDisplayed()
        compose.onNodeWithContentDescription("Contraseña").performTextInput("test-password")
        compose.onNodeWithContentDescription("Contraseña").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.onNodeWithContentDescription("Mostrar contraseña").performClick()
        compose.onNodeWithContentDescription("Contraseña").assertTextEquals("test-password")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Password))
        compose.onNodeWithText("Mostrar contraseña").assertDoesNotExist()
        compose.onNodeWithContentDescription("Ocultar contraseña").performClick()
        compose.onNodeWithContentDescription("Contraseña").assertTextEquals("\u2022".repeat("test-password".length))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.runOnIdle { assertEquals(0, submissions) }
    }
}
