package co.solventa.mobile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import co.solventa.mobile.feature.auth.AccessScreen
import co.solventa.mobile.feature.auth.AuthApi
import co.solventa.mobile.feature.auth.AuthGrant
import co.solventa.mobile.feature.auth.AuthHttpError
import co.solventa.mobile.feature.auth.AuthRepository
import co.solventa.mobile.feature.auth.AuthStore
import co.solventa.mobile.feature.auth.AuthUser
import co.solventa.mobile.feature.auth.AuthViewModel
import co.solventa.mobile.feature.auth.RegistrationScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RegistrationFlowTest {
    @get:Rule val compose = createComposeRule()

    private class Store : AuthStore {
        var grant: AuthGrant? = null
        override fun read() = grant
        override fun write(grant: AuthGrant) { this.grant = grant }
        override fun clear() { grant = null }
    }

    private class Api : AuthApi {
        val user = AuthUser("11111111-1111-4111-8111-111111111111", "Cliente", "cliente@example.org")
        var registrationError: Int? = null
        var currentError: Int? = null
        var registered = 0
        var revoked = false
        override fun register(name: String, email: String, password: String): AuthUser {
            registrationError?.let { throw AuthHttpError(it) }
            registered++
            return user
        }
        override fun login(email: String, password: String) = AuthGrant(user, "test-access", "test-refresh",
            System.currentTimeMillis() + 60_000, System.currentTimeMillis() + 600_000)
        override fun refresh(token: String) = login("", "")
        override fun current(token: String): AuthUser {
            currentError?.let { throw AuthHttpError(it) }
            return user
        }
        override fun logout(accessToken: String, refreshToken: String) { revoked = true }
    }

    private fun fillRegistration(confirmation: String = "test-password") {
        compose.onNodeWithContentDescription("Nombre completo").performScrollTo().performTextInput("Cliente")
        compose.onNodeWithContentDescription("Nombre completo").performImeAction()
        compose.onNodeWithContentDescription("Correo electrónico").assertIsFocused().performScrollTo().performTextInput("cliente@example.org")
        compose.onNodeWithContentDescription("Correo electrónico").performImeAction()
        compose.onNodeWithContentDescription("Contraseña").assertIsFocused().performScrollTo().performTextInput("test-password")
        compose.onNodeWithContentDescription("Contraseña").performImeAction()
        compose.onNodeWithContentDescription("Confirmar contraseña").assertIsFocused().performScrollTo().performTextInput(confirmation)
        compose.onNodeWithContentDescription("Confirmar contraseña").performImeAction()
    }

    @Test fun registrationConfirmationLoginAndLogoutAreSeparateSteps() {
        val api = Api()
        val store = Store()
        val auth = AuthViewModel(AuthRepository(api, store))
        compose.setContent { SolventaApp(auth) }
        compose.onNodeWithText("Crear cuenta").performScrollTo().performClick()
        fillRegistration()
        compose.waitUntil(5_000) { auth.state.value.screen == AccessScreen.REGISTERED }
        compose.onNodeWithText("Tu cuenta está lista").assertIsDisplayed()
        assertEquals(1, api.registered)
        assertNull(store.grant)
        assertNull(auth.state.value.user)
        compose.onNodeWithText("Ir a iniciar sesión").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Correo electrónico")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(api.user.email)))
        compose.onNodeWithContentDescription("Contraseña").performScrollTo().performTextInput("test-password")
        compose.onNodeWithContentDescription("Contraseña").performImeAction()
        compose.waitUntil(5_000) { auth.state.value.user != null }
        compose.onNodeWithText("Mi cuenta").assertIsDisplayed()
        compose.onNodeWithText("Cerrar sesión").performScrollTo().performClick()
        compose.waitUntil(5_000) { auth.state.value.user == null && !auth.state.value.loading }
        assertTrue(api.revoked)
        assertNull(store.grant)
        compose.onNodeWithText("Crear cuenta").performScrollTo().assertIsEnabled()
    }

    @Test fun existingEmailDoesNotPretendRegistrationOrLoginSucceeded() {
        val api = Api().apply { registrationError = 409 }
        val auth = AuthViewModel(AuthRepository(api, Store()))
        compose.setContent { SolventaApp(auth) }
        compose.onNodeWithText("Crear cuenta").performScrollTo().performClick()
        fillRegistration()
        compose.waitUntil(5_000) { auth.state.value.error != null }
        compose.onNodeWithText("Este correo ya tiene una cuenta. Inicia sesión con ella.").performScrollTo().assertIsDisplayed()
        assertEquals(AccessScreen.REGISTER, auth.state.value.screen)
        assertNull(auth.state.value.user)
        assertEquals(0, api.registered)
        compose.onNodeWithText("Volver a iniciar sesión").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Correo electrónico")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(api.user.email)))
        compose.onNodeWithContentDescription("Contraseña")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }

    @Test fun loadingDisablesRegistrationAndBackActions() {
        compose.setContent { RegistrationScreen(loading = true) }
        compose.onNodeWithContentDescription("Nombre completo").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Correo electrónico").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Creando cuenta…").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Volver a iniciar sesión").performScrollTo().assertIsNotEnabled()
    }

    @Test fun differentPasswordsDoNotSubmitRegistration() {
        val api = Api()
        val auth = AuthViewModel(AuthRepository(api, Store()))
        compose.setContent { SolventaApp(auth) }
        compose.onNodeWithText("Crear cuenta").performScrollTo().performClick()
        fillRegistration("different-password")
        compose.onNodeWithText("Las contraseñas no coinciden.").performScrollTo().assertIsDisplayed()
        assertEquals(0, api.registered)
        assertNull(auth.state.value.user)
    }

    @Test fun unavailableRegistrationIsNotPresentedAsCreated() {
        val api = Api().apply { registrationError = 503 }
        val auth = AuthViewModel(AuthRepository(api, Store()))
        compose.setContent { SolventaApp(auth) }
        compose.onNodeWithText("Crear cuenta").performScrollTo().performClick()
        fillRegistration()
        compose.waitUntil(5_000) { auth.state.value.error != null }
        compose.onNodeWithText("No pudimos confirmar el registro. Revisa tu conexión o intenta iniciar sesión si ya creaste tu cuenta.")
            .performScrollTo().assertIsDisplayed()
        assertEquals(AccessScreen.REGISTER, auth.state.value.screen)
        assertNull(auth.state.value.user)
    }

    @Test fun registrationRemainsReachableWithLargeTextAndSmallViewport() {
        var submitted = false
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f)) {
                Box(Modifier.requiredSize(320.dp, 480.dp)) {
                    RegistrationScreen(onRegister = { _, _, _, _ -> submitted = true })
                }
            }
        }
        fillRegistration()
        compose.runOnIdle { assertTrue(submitted) }
        compose.onNodeWithText("Volver a iniciar sesión").performScrollTo().assertIsDisplayed()
    }

    @Test fun expiredSessionReturnsToLoginWithAnExpirationMessage() {
        val api = Api().apply { currentError = 401 }
        val store = Store().apply { grant = api.login("", "") }
        val auth = AuthViewModel(AuthRepository(api, store))
        compose.setContent { SolventaApp(auth) }
        compose.runOnIdle { auth.restore() }
        compose.waitUntil(5_000) { auth.state.value.error != null }
        compose.onNodeWithText("Tu sesión expiró. Ingresa nuevamente.").performScrollTo().assertIsDisplayed()
        assertNull(auth.state.value.user)
        assertNull(store.grant)
    }
}
