package co.solventa.mobile

import android.app.Application
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import co.solventa.mobile.feature.auth.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Job
import androidx.lifecycle.viewModelScope

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class BiometricFlowUiTest {
    @get:Rule val compose = createComposeRule()
    private val user = AuthUser("22222222-2222-4222-8222-222222222222", "Cliente", "synthetic@example.org")
    private val id = "11111111-1111-4111-8111-111111111111"
    private val issuer = "https://identidad.solventa.test"
    private var grant: AuthGrant? = null
    private var local: BiometricCredential? = null
    private var active = false
    private var loginFailure = false
    private var publicKey = byteArrayOf()
    private var primaryLogins = 0
    private var registrations = 0
    private var biometricLogins = 0
    private var failLocalWrite = false
    private var currentActor = user
    private var loginStarted: CountDownLatch? = null
    private var loginRelease: CountDownLatch? = null
    private var revocations = 0
    private var malformedExpiration: String? = null
    private var keyStarted: CountDownLatch? = null
    private var keyRelease: CountDownLatch? = null
    private var publicationStarted: CountDownLatch? = null
    private var publicationRelease: CountDownLatch? = null
    private val device = TestDevice()
    private val authStore = object : AuthStore {
        override fun read() = grant
        override fun write(grant: AuthGrant) { this@BiometricFlowUiTest.grant = grant }
        override fun clear() { grant = null }
    }
    private val localStore = object : BiometricCredentialStore {
        override fun read() = local
        override fun write(credential: BiometricCredential) {
            if (failLocalWrite) throw AuthProblem(AuthError.STORAGE)
            local = credential
            publicationStarted?.countDown()
            check(publicationRelease?.await(10, TimeUnit.SECONDS) != false)
        }
        override fun clear() { local = null }
    }
    private fun issued(token: String) = AuthGrant(currentActor, token, "refresh-$token",
        System.currentTimeMillis() + 3_600_000, System.currentTimeMillis() + 86_400_000)
    private val api = object : AuthApi, BiometricApi {
        override fun register(name: String, email: String, password: String): AuthUser {
            registrations++
            assertEquals(user.name, name)
            assertEquals(user.email, email)
            assertEquals("password", password)
            return user
        }
        override fun login(email: String, password: String): AuthGrant { primaryLogins++; return issued("password") }
        override fun refresh(token: String) = issued("rotated")
        override fun current(token: String) = currentActor
        override fun logout(accessToken: String, refreshToken: String) {}
        override fun beginEnrollment(token: String, password: String, publicKey: ByteArray): JSONObject {
            if (password != "password") throw AuthHttpError(401)
            this@BiometricFlowUiTest.publicKey = publicKey
            return challenge("enrolar")
        }
        override fun confirmEnrollment(token: String, challenge: BiometricChallenge, signature: ByteArray) { active = true }
        override fun biometricChallenge(credentialId: String) = challenge("login")
        override fun biometricLogin(challenge: BiometricChallenge, signature: ByteArray): AuthGrant {
            if (loginFailure) throw AuthHttpError(401)
            val result = issued("biometric")
            loginStarted?.countDown()
            check(loginRelease?.await(10, TimeUnit.SECONDS) != false)
            biometricLogins++
            return result
        }
        override fun biometricCredentials(token: String) =
            if (currentActor.id == user.id) listOf(RemoteBiometricCredential(id, "2026-10-24T00:00:00Z", active)) else emptyList()
        override fun revokeBiometricCredential(token: String, credentialId: String) {
            revocations++
            if (currentActor.id == user.id) active = false
        }
    }
    private val repository = AuthRepository(api, authStore)
    private val auth = AuthViewModel(repository)
    private val biometrics = BiometricViewModel(BiometricRepository(api, repository, localStore, issuer))

    private inner class TestDevice : BiometricDevice {
        // Synthetic JVM key: exercises app flow, never a physical biometric result.
        private val pair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        var failure: BiometricFailure? = null
        @Volatile var deleted = 0
        override fun createKey(): BiometricKey {
            keyStarted?.countDown()
            check(keyRelease?.await(10, TimeUnit.SECONDS) != false)
            return BiometricKey("synthetic-test-key", pair.public.encoded)
        }
        override fun deleteKey(alias: String) { deleted++ }
        override suspend fun sign(alias: String, payload: ByteArray): ByteArray {
            failure?.let { throw BiometricProblem(it) }
            return Signature.getInstance("SHA256withECDSA").run {
                initSign(pair.private)
                update(payload)
                sign()
            }
        }
    }

    private fun challenge(purpose: String): JSONObject {
        val expires = Instant.ofEpochMilli(System.currentTimeMillis() + 60_000).epochSecond
        val nonce = base64Url(ByteArray(32) { it.toByte() })
        val message = listOf("SOLVENTA-BIOMETRIC-V1", purpose, "movil", sha256Base64(issuer.toByteArray()),
            nonce, id, user.id, sha256Base64(publicKey), expires.toString()).joinToString("\n")
        return JSONObject().put("credencialId", id).put("desafioId", nonce).put("proposito", purpose)
            .put("algoritmo", "ECDSA_P256_SHA256_DER").put("mensaje", base64Url(message.toByteArray()))
            .put("expiraEn", malformedExpiration ?: Instant.ofEpochSecond(expires).toString())
    }

    private fun await(condition: () -> Boolean) {
        compose.waitUntil(10_000) { shadowOf(Looper.getMainLooper()).idle(); condition() }
    }
    private fun openAccount() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                Box(Modifier.requiredSize(320.dp, 480.dp)) { SolventaApp(auth, biometrics, device) }
            }
        }
        compose.runOnIdle { auth.login(user.email, "password") }
        await { auth.state.value.user != null && !auth.state.value.loading && biometrics.state.value.actorUserId == user.id }
    }

    @Test fun anotherAccountCannotSeeOrDeleteThePreviousAccountsLocalLink() {
        openAccount()
        compose.runOnIdle { biometrics.enroll("password", device) }
        await { local != null && !biometrics.state.value.busy }
        compose.runOnIdle { biometrics.loadCredentials() }
        await { biometrics.state.value.credentials.isNotEmpty() && !biometrics.state.value.busy }
        compose.runOnIdle { auth.logout() }
        await { auth.state.value.user == null && biometrics.state.value.actorUserId == null }
        assertTrue(biometrics.state.value.credentials.isEmpty())
        assertNull(biometrics.state.value.message)
        assertFalse(biometrics.state.value.busy)
        assertEquals(user.id, local?.userId)
        currentActor = user.copy(id = "33333333-3333-4333-8333-333333333333", email = "other@example.org")
        compose.runOnIdle { auth.login(currentActor.email, "password") }
        await { biometrics.state.value.actorUserId == currentActor.id }
        assertTrue(biometrics.state.value.credentials.isEmpty())
        assertNull(biometrics.state.value.message)
        compose.onNodeWithText(org.robolectric.RuntimeEnvironment.getApplication().getString(R.string.biometric_other_account))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Desactivar credencial 11111111").assertDoesNotExist()
        compose.runOnIdle { biometrics.revoke(id, device) }
        await { !biometrics.state.value.busy && biometrics.state.value.message != null }
        assertEquals(0, revocations)
        assertEquals(0, device.deleted)
        assertTrue(active)
        assertEquals(user.id, local?.userId)
        assertEquals(currentActor.id, grant?.user?.id)
    }

    @Test fun lateNetworkBiometricResponseCannotUndoLogoutOrReplaceAnotherAccount() = runBlocking {
        val key = device.createKey()
        publicKey = key.publicKeySpki
        localStore.write(BiometricCredential(id, key.alias, user.id, publicKey))
        val biometricRepository = BiometricRepository(api, repository, localStore, issuer)
        for (switchAccount in listOf(false, true)) {
            val pending = biometricRepository.loginChallenge()
            loginStarted = CountDownLatch(1)
            loginRelease = CountDownLatch(1)
            val late = async(Dispatchers.Default) {
                runCatching { biometricRepository.login(pending, byteArrayOf(1)) }
            }

            try {
                assertTrue(loginStarted!!.await(5, TimeUnit.SECONDS))
                repository.logout()
                if (switchAccount) {
                    currentActor = user.copy(id = "33333333-3333-4333-8333-333333333333")
                    repository.login("other@example.org", "password")
                }
            } finally {
                loginRelease!!.countDown()
            }
            val error = late.await().exceptionOrNull()
            assertTrue(error is AuthProblem)
            assertEquals(AuthError.EXPIRED, (error as AuthProblem).reason)
            if (switchAccount) assertEquals(currentActor.id, grant?.user?.id) else assertNull(grant)
            assertEquals(user.id, local?.userId)
        }
    }

    @Test fun actorChangeDuringKeyCreationDeletesTheUndeliveredCandidate() {
        openAccount()
        keyStarted = CountDownLatch(1)
        keyRelease = CountDownLatch(1)
        compose.runOnIdle { biometrics.enroll("password", device) }
        await { keyStarted!!.count == 0L }
        var pending: Job? = null
        try {
            compose.runOnIdle {
                pending = biometrics.viewModelScope.coroutineContext[Job]!!.children.single()
                biometrics.bindActor("33333333-3333-4333-8333-333333333333")
            }
        } finally { keyRelease!!.countDown() }
        await { pending!!.isCompleted }
        assertEquals(1, device.deleted)
        assertNull(local)
        assertFalse(active)
        assertFalse(biometrics.state.value.busy)
        assertTrue(biometrics.state.value.credentials.isEmpty())
        assertNull(biometrics.state.value.message)
        assertEquals("password", grant?.accessToken)
    }

    @Test fun actorChangeAfterPublicationReloadsLinkWithoutPublishingTheOldAccountsState() {
        openAccount()
        compose.runOnIdle { biometrics.loadCredentials() }
        await { !biometrics.state.value.busy && biometrics.state.value.credentials.isNotEmpty() }
        publicationStarted = CountDownLatch(1)
        publicationRelease = CountDownLatch(1)
        compose.runOnIdle { biometrics.enroll("password", device) }
        await { publicationStarted!!.count == 0L }
        var pending: Job? = null
        val other = "33333333-3333-4333-8333-333333333333"
        try {
            compose.runOnIdle {
                pending = biometrics.viewModelScope.coroutineContext[Job]!!.children.single()
                biometrics.bindActor(other)
            }
            assertEquals(user.id, biometrics.state.value.localCredential?.userId)
        } finally { publicationRelease!!.countDown() }
        await { pending!!.isCompleted }
        assertTrue(active)
        assertEquals(0, device.deleted)
        assertEquals(user.id, local?.userId)
        assertEquals(user.id, biometrics.state.value.localCredential?.userId)
        assertEquals(other, biometrics.state.value.actorUserId)
        assertFalse(biometrics.state.value.busy)
        assertTrue(biometrics.state.value.credentials.isEmpty())
        assertNull(biometrics.state.value.message)
    }

    @Test fun explicitEnrollmentLogoutSignedLoginAndRevocationKeepTheirBoundaries() {
        openAccount()
        compose.onNodeWithText("Habilitar acceso biométrico").performScrollTo().performClick()
        compose.onNodeWithText(org.robolectric.RuntimeEnvironment.getApplication().getString(R.string.biometric_enrollment_consent))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Contraseña actual").performScrollTo().performTextInput("password")
        compose.onNodeWithContentDescription("Contraseña actual")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.onNode(hasText("Habilitar acceso biométrico") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        await { local != null && !biometrics.state.value.busy }
        assertEquals(1, primaryLogins)
        assertEquals(0, biometricLogins)
        assertEquals("password", grant?.accessToken)
        compose.onNodeWithText("Cerrar sesión").performScrollTo().performClick()
        await { auth.state.value.user == null && !auth.state.value.loading }
        assertNull(grant)
        assertNotNull(local)
        compose.onNodeWithText("Ingresar con biometría").performScrollTo().performClick()
        await { auth.state.value.user != null && !auth.state.value.loading && !biometrics.state.value.busy }
        assertEquals(1, biometricLogins)
        assertEquals("biometric", grant?.accessToken)
        compose.onNodeWithText("Ver credenciales").performScrollTo().performClick()
        await { biometrics.state.value.credentials.isNotEmpty() && !biometrics.state.value.busy }
        compose.onNodeWithText("Desactivar credencial 11111111").performScrollTo().performClick()
        await { local == null && !biometrics.state.value.busy }
        assertFalse(active)
        assertEquals(1, device.deleted)
        assertNotNull(auth.state.value.user)
    }

    @Test fun newAccountUsesOneRegistrationThenExplicitPasswordAndOptionalBiometricLogin() {
        compose.setContent { SolventaApp(auth, biometrics, device) }
        compose.onNode(hasText("Crear cuenta") and hasClickAction()).performScrollTo().performClick()
        listOf("Nombre completo", "Correo electrónico", "Contraseña", "Confirmar contraseña")
            .zip(listOf(user.name, user.email, "password", "password")).forEach { (label, value) ->
                compose.onNodeWithContentDescription(label).performScrollTo().performTextInput(value)
                compose.onNodeWithContentDescription(label).performImeAction()
            }
        compose.onNodeWithText("Continuar").performScrollTo().assertIsNotEnabled()
        assertEquals(0, registrations)
        assertEquals(0, primaryLogins)
        assertNull(grant)
        assertNull(local)
        compose.onNodeWithText("Acepto los términos y el tratamiento de datos personales.").performScrollTo().performClick()
        compose.onNodeWithText("Continuar").performScrollTo().performClick()
        await { auth.state.value.screen == AccessScreen.REGISTERED && !auth.state.value.loading }
        assertEquals(1, registrations)
        assertNull(auth.state.value.user)
        assertNull(grant)
        assertNull(local)
        compose.onNodeWithText("Iniciar sesión").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Correo electrónico").assertTextEquals(user.email)
        compose.onNodeWithContentDescription("Contraseña").assertTextEquals("").performTextInput("password")
        compose.onNodeWithText("Ingresar").performScrollTo().performClick()
        await { auth.state.value.user == user && biometrics.state.value.actorUserId == user.id }
        assertEquals(1, primaryLogins)
        assertNull(local)
        assertEquals(0, biometricLogins)
        compose.onNodeWithText("Habilitar acceso biométrico").performScrollTo().performClick()
        compose.onNodeWithText("Ahora no").performClick()
        assertNull(local)
        assertEquals(user, auth.state.value.user)
        compose.onNodeWithText("Habilitar acceso biométrico").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Contraseña actual").performScrollTo().performTextInput("password")
        compose.onNode(hasText("Habilitar acceso biométrico") and hasClickAction() and hasAnyAncestor(isDialog()))
            .performClick()
        await { local != null && !biometrics.state.value.busy }
        assertEquals(user.id, local?.userId)
        assertTrue(active)
        compose.onNodeWithText("Cerrar sesión").performScrollTo().performClick()
        await { auth.state.value.user == null && biometrics.state.value.actorUserId == null && !auth.state.value.loading }
        assertNull(grant)
        assertNotNull(local)
        compose.onNodeWithText("Ingresar con biometría").performScrollTo().performClick()
        await { auth.state.value.user == user && !auth.state.value.loading && !biometrics.state.value.busy }
        assertEquals(1, registrations)
        assertEquals(1, primaryLogins)
        assertEquals(1, biometricLogins)
        assertEquals("biometric", grant?.accessToken)
    }

    @Test fun cancelledEnrollmentDeletesOnlyCandidateAndKeepsAuthorizedPasswordSession() {
        openAccount()
        device.failure = BiometricFailure.CANCELLED
        compose.runOnIdle { biometrics.enroll("password", device) }
        await { !biometrics.state.value.busy && biometrics.state.value.message == R.string.biometric_cancelled }
        assertNull(local)
        assertFalse(active)
        assertEquals(1, device.deleted)
        assertEquals("password", grant?.accessToken)
        assertNotNull(auth.state.value.user)
    }

    @Test fun rejectedBackendProofNeverCreatesALocalSessionAndPasswordStillWorks() {
        val key = device.createKey()
        publicKey = key.publicKeySpki
        localStore.write(BiometricCredential(id, key.alias, user.id, publicKey))
        val model = BiometricViewModel(BiometricRepository(api, repository, localStore, issuer))
        loginFailure = true
        compose.setContent { SolventaApp(auth, model, device) }
        compose.onNodeWithText("Ingresar con biometría").performScrollTo().performClick()
        await { !model.state.value.busy && model.state.value.message == R.string.biometric_rejected }
        assertNull(grant)
        assertNull(auth.state.value.user)
        device.failure = BiometricFailure.KEY_INVALIDATED
        compose.onNodeWithText("Ingresar con biometría").performScrollTo().performClick()
        await { !model.state.value.busy && model.state.value.message == R.string.biometric_key_missing }
        assertNull(grant)
        assertNotNull(local)
        device.failure = BiometricFailure.CANCELLED
        compose.onNodeWithText("Ingresar con biometría").performScrollTo().performClick()
        await { !model.state.value.busy && model.state.value.message == R.string.biometric_cancelled }
        assertNull(grant)
        assertEquals(0, device.deleted)
        compose.runOnIdle { auth.login(user.email, "password") }
        await { auth.state.value.user != null }
        assertEquals("password", grant?.accessToken)
    }

    @Test fun wrongStepUpPasswordNeverPublishesCredentialOrErasesAuthorizedSession() {
        openAccount()
        compose.runOnIdle { biometrics.enroll("wrong-password", device) }
        await { !biometrics.state.value.busy && biometrics.state.value.message == R.string.biometric_rejected }
        assertNull(local)
        assertFalse(active)
        assertEquals(1, device.deleted)
        assertEquals("password", grant?.accessToken)
        assertNotNull(auth.state.value.user)
    }

    @Test fun malformedChallengeDateIsRecoverableAndDoesNotPublishCredential() {
        openAccount()
        for ((index, invalid) in listOf("not-a-timestamp", "+999999999-12-31T23:59:59Z").withIndex()) {
            malformedExpiration = invalid
            compose.runOnIdle { biometrics.enroll("password", device) }
            await { !biometrics.state.value.busy && biometrics.state.value.message == R.string.biometric_invalid_response }
            assertNull(local)
            assertFalse(active)
            assertEquals(index + 1, device.deleted)
            assertEquals("password", grant?.accessToken)
        }
    }

    @Test fun failedLocalPublicationRevokesCandidateAndKeepsAuthorizedSession() {
        openAccount()
        failLocalWrite = true
        compose.runOnIdle { biometrics.enroll("password", device) }
        await { !biometrics.state.value.busy && biometrics.state.value.message == R.string.auth_storage }
        assertNull(local)
        assertFalse(active)
        assertEquals(1, device.deleted)
        assertEquals("password", grant?.accessToken)
    }
}
