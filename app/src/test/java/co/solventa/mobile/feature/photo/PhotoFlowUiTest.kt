package co.solventa.mobile.feature.photo

import android.app.Application
import android.Manifest
import android.content.res.Configuration
import android.os.Looper
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModel
import co.solventa.mobile.R
import co.solventa.mobile.SolventaApp
import co.solventa.mobile.feature.auth.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import org.json.JSONObject

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w640dp-h640dp")
class PhotoFlowUiTest(private val dark: Boolean, private val landscape: Boolean) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0},landscape={1}")
        fun parameters() = listOf(arrayOf(false, false), arrayOf(true, false),
            arrayOf(false, true), arrayOf(true, true))
    }
    init {
        org.robolectric.RuntimeEnvironment.setQualifiers(
            if (landscape) "w640dp-h320dp-land" else "w320dp-h480dp-port")
    }
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var grant: AuthGrant? = null
    private var actor = AuthUser("22222222-2222-4222-8222-222222222222", "Cliente", "synthetic@example.org")
    private var calls = 0
    private var registerCalls = 0
    private val api = object : AuthApi {
        override fun register(name: String, email: String, password: String): AuthUser { calls++; registerCalls++; return actor }
        override fun login(email: String, password: String): AuthGrant {
            calls++
            return AuthGrant(actor, "synthetic-access", "synthetic-refresh",
                System.currentTimeMillis() + 3_600_000, System.currentTimeMillis() + 86_400_000)
        }
        override fun current(token: String): AuthUser { calls++; return actor }
        override fun refresh(token: String): AuthGrant { calls++; return requireNotNull(grant) }
        override fun logout(accessToken: String, refreshToken: String) { calls++ }
    }
    private val store = object : AuthStore {
        override fun read() = grant
        override fun write(grant: AuthGrant) { this@PhotoFlowUiTest.grant = grant }
        override fun clear() { grant = null }
    }
    private val authRepository = AuthRepository(api, store)
    private val auth = AuthViewModel(authRepository)
    private var biometricCredential: BiometricCredential? = null
    private var biometricFlow = false
    private var biometricSignatures = 0
    private var biometricLogins = 0
    private val keyPair by lazy {
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    }
    private val credentialId = "11111111-1111-4111-8111-111111111111"
    private val issuer = "https://identidad.solventa.test"
    private val biometricApi = object : BiometricApi {
        private fun challenge(purpose: String): JSONObject {
            val expires = Instant.now().plusSeconds(60).epochSecond
            val nonce = base64Url(ByteArray(32) { it.toByte() })
            val message = listOf("SOLVENTA-BIOMETRIC-V1", purpose, "movil", sha256Base64(issuer.toByteArray()),
                nonce, credentialId, actor.id, sha256Base64(keyPair.public.encoded), expires.toString()).joinToString("\n")
            return JSONObject().put("credencialId", credentialId).put("desafioId", nonce).put("proposito", purpose)
                .put("algoritmo", "ECDSA_P256_SHA256_DER").put("mensaje", base64Url(message.toByteArray()))
                .put("expiraEn", Instant.ofEpochSecond(expires).toString())
        }
        private fun verify(challenge: BiometricChallenge, signature: ByteArray) {
            assertTrue(Signature.getInstance("SHA256withECDSA").run {
                initVerify(keyPair.public)
                update(challenge.message)
                verify(signature)
            })
        }
        override fun beginEnrollment(token: String, password: String, publicKey: ByteArray): JSONObject {
            assertNotNull(grant)
            assertEquals("password", password)
            assertArrayEquals(keyPair.public.encoded, publicKey)
            return challenge("enrolar")
        }
        override fun confirmEnrollment(token: String, challenge: BiometricChallenge, signature: ByteArray) {
            verify(challenge, signature)
        }
        override fun biometricChallenge(credentialId: String): JSONObject {
            assertEquals(this@PhotoFlowUiTest.credentialId, credentialId)
            assertNull(grant)
            return challenge("login")
        }
        override fun biometricLogin(challenge: BiometricChallenge, signature: ByteArray): AuthGrant {
            verify(challenge, signature)
            biometricLogins++
            return AuthGrant(actor, "signed-access", "signed-refresh",
                System.currentTimeMillis() + 3_600_000, System.currentTimeMillis() + 86_400_000)
        }
        override fun biometricCredentials(token: String) = emptyList<RemoteBiometricCredential>()
        override fun revokeBiometricCredential(token: String, credentialId: String) = Unit
    }
    private val biometricStore = object : BiometricCredentialStore {
        override fun read() = biometricCredential
        override fun write(credential: BiometricCredential) { biometricCredential = credential }
        override fun clear() { biometricCredential = null }
    }
    private val biometricDevice = object : BiometricDevice {
        override fun createKey() = BiometricKey("synthetic-photo-journey-key", keyPair.public.encoded)
        override fun deleteKey(alias: String) = Unit
        override suspend fun sign(alias: String, payload: ByteArray): ByteArray {
            biometricSignatures++
            return Signature.getInstance("SHA256withECDSA").run {
                initSign(keyPair.private)
                update(payload)
                sign()
            }
        }
    }
    private val biometrics = BiometricViewModel(BiometricRepository(biometricApi, authRepository, biometricStore, issuer))
    private lateinit var model: PhotoReviewViewModel
    private var captured: PhotoCapture? = null

    private fun openApp(permission: Boolean = true) {
        if (permission) shadowOf(compose.activity.application).grantPermissions(Manifest.permission.CAMERA)
        else shadowOf(compose.activity.application).denyPermissions(Manifest.permission.CAMERA)
        model = ViewModelProvider(compose.activity,
            ViewModelProvider.AndroidViewModelFactory(compose.activity.application))[PhotoReviewViewModel::class.java]
        assertSame(compose.activity.application, model.getApplication<Application>())
        compose.setContent { AppContent() }
    }

    @Composable
    private fun AppContent() {
            val configuration = Configuration(LocalConfiguration.current).apply {
                uiMode = if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f),
                LocalConfiguration provides configuration) {
                Box(Modifier.requiredSize(if (landscape) 640.dp else 320.dp, if (landscape) 320.dp else 480.dp)) {
                    PhotoReviewHost(compose.activity, auth, camera = { controller, state ->
                        PhotoButton(R.string.photo_take, {
                            val request = requireNotNull(controller.beginCapture())
                            captured = request
                            val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
                            request.file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
                            bitmap.recycle()
                            controller.captured(request)
                        }, state.step == PhotoStep.CAMERA)
                    }) { open, beforeChange ->
                        SolventaApp(auth = auth, biometrics = if (biometricFlow) biometrics else null,
                            device = if (biometricFlow) biometricDevice else null,
                            onPhotoReview = open, beforeAccessChange = beforeChange, darkTheme = dark)
                }
            }
        }
    }

    private fun await(condition: () -> Boolean) {
        compose.waitUntil(10_000) { shadowOf(Looper.getMainLooper()).idleFor(50, TimeUnit.MILLISECONDS); condition() }
    }

    private fun click(label: String) = compose.onNode(hasText(label) and hasClickAction()).performScrollTo().performClick()

    private fun beginRegistration() {
        if (auth.state.value.screen == AccessScreen.LOGIN) click("Crear cuenta")
        compose.runOnIdle { auth.prepareRegistration(actor.name, actor.email, "password", "password", withPhotos = true) }
        click("Acepto los términos y el tratamiento de datos personales.")
        click("Continuar")
        await { auth.state.value.screen == AccessScreen.REGISTER_PHOTOS && model.state.value.open }
        assertEquals(0, registerCalls)
        assertNull(auth.state.value.user)
        assertNull(grant)
    }

    private fun review(open: Boolean = true) {
        if (open) beginRegistration()
        val roots = compose.onAllNodes(isRoot()).fetchSemanticsNodes()
        val photoBounds = roots.last().boundsInRoot
        val density = compose.activity.resources.displayMetrics.density
        assertEquals(if (landscape) 640f else 320f, photoBounds.width / density, 1f)
        assertTrue("Photo dialog must fit the viewport", photoBounds.height / density <= if (landscape) 320f else 480f)
        click("Abrir cámara")
        await { model.state.value.step == PhotoStep.CAMERA }
        click("Tomar foto")
        await { model.state.value.step == PhotoStep.REVIEW }
        compose.onNodeWithText("Revisar foto").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(if (model.state.value.kind == PhotoKind.DOCUMENT)
            "Foto del documento capturada" else "Foto capturada").assertExists()
    }

    @Test fun photosRequireExplicitRegistrationConfirmationWithoutAnySessionOrHttp() {
        openApp()
        review()
        click("Usar esta foto")
        await { model.state.value.documentReceived && model.state.value.step == PhotoStep.INTRO }
        assertFalse(requireNotNull(captured).file.exists())
        assertFalse(model.state.value.selfieReceived)
        compose.onNodeWithText(compose.activity.getString(R.string.selfie_privacy_note)).performScrollTo().assertIsDisplayed()
        click(compose.activity.getString(R.string.selfie_privacy_details))
        compose.onNodeWithText(compose.activity.getString(R.string.selfie_privacy_body)).assertExists()
        compose.onNode(hasText(compose.activity.getString(R.string.selfie_privacy_close)) and hasClickAction())
            .assertIsDisplayed().performClick()
        assertEquals(0, calls)
        review(open = false)
        click("Usar esta foto")
        await { model.state.value.step == PhotoStep.COMPLETE }
        compose.onNodeWithText("Fotos revisadas").performScrollTo().assertIsDisplayed()
        assertFalse(requireNotNull(captured).file.exists())
        assertNull(model.state.value.preview)
        assertNull(auth.state.value.user)
        assertNull(grant)
        assertEquals(0, calls)
        click("Continuar")
        assertEquals(AccessScreen.REGISTER_CONFIRM, auth.state.value.screen)
        assertFalse(model.state.value.open)
        compose.onNodeWithText("Finalizar registro").performScrollTo().assertIsDisplayed()
        click("Cancelar registro")
        assertEquals(AccessScreen.REGISTER, auth.state.value.screen)
        compose.onNodeWithText("Volver al acceso").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Mi cuenta").assertDoesNotExist()
        assertEquals(0, calls)
    }

    @Test fun repeatAndCancelDoNotShowCompletionOrAuthorize() {
        openApp()
        review()
        val file = requireNotNull(captured).file
        click("Repetir foto")
        assertFalse(file.exists())
        click("Cancelar")
        compose.onNodeWithText("Fotos revisadas").assertDoesNotExist()
        assertEquals(AccessScreen.REGISTER, auth.state.value.screen)
        assertNull(grant)
        assertEquals(0, calls)
    }

    @Test fun deniedPermissionAndCameraErrorAllowExitWithoutSuccess() {
        openApp(permission = false)
        beginRegistration()
        click("Abrir cámara")
        compose.runOnIdle { model.permissionResult(model.state.value.generation, false) }
        compose.onNodeWithText(compose.activity.getString(R.string.photo_denied)).assertExists()
        compose.onNodeWithText("Fotos revisadas").assertDoesNotExist()
        compose.runOnIdle { model.cameraFailed(model.state.value.generation, R.string.photo_no_camera) }
        compose.onNodeWithText(compose.activity.getString(R.string.photo_no_camera)).assertExists()
        click("Cancelar")
        assertEquals(0, calls)
    }

    @Test fun cleanupFailureOnLeavingCompletedPhotosCannotEnableFinalRegistration() {
        openApp()
        review()
        click("Usar esta foto")
        await { model.state.value.documentReceived && model.state.value.step == PhotoStep.INTRO }
        review(open = false)
        click("Usar esta foto")
        await { model.state.value.step == PhotoStep.COMPLETE }
        val obstruction = java.io.File(compose.activity.cacheDir, "photo-review/obstruction")
        check(obstruction.mkdir())
        try {
            click("Continuar")
            await { auth.state.value.screen == AccessScreen.REGISTER }
            assertEquals(R.string.photo_storage, model.state.value.error)
            assertFalse(model.state.value.open)
            compose.runOnIdle { auth.completeRegistrationPhotos(); auth.confirmRegistration() }
            assertEquals(AccessScreen.REGISTER, auth.state.value.screen)
            assertEquals(0, calls)
            assertNull(grant)
            assertNull(auth.state.value.user)
        } finally {
            check(obstruction.delete())
        }
    }

    @Test fun registrationCanCancelRetryAndFinishWithOneExplicitPostAndSeparateLogin() {
        // The installed activity is portrait-only; keep the existing landscape photo checks separate.
        biometricFlow = !landscape
        openApp()
        beginRegistration()
        click("Cancelar")
        assertEquals(AccessScreen.REGISTER, auth.state.value.screen)
        assertEquals(0, registerCalls)
        beginRegistration()
        review(open = false)
        click("Usar esta foto")
        await { model.state.value.documentReceived && model.state.value.step == PhotoStep.INTRO }
        click(compose.activity.getString(R.string.photo_restart_document))
        assertFalse(model.state.value.documentReceived)
        review(open = false)
        click("Usar esta foto")
        await { model.state.value.documentReceived && model.state.value.step == PhotoStep.INTRO }
        review(open = false)
        click("Usar esta foto")
        await { model.state.value.step == PhotoStep.COMPLETE }
        assertEquals(0, calls)
        assertEquals(0, registerCalls)
        assertNull(grant)
        assertNull(auth.state.value.user)
        click("Continuar")
        assertEquals(AccessScreen.REGISTER_CONFIRM, auth.state.value.screen)
        assertEquals(0, registerCalls)
        assertNull(grant)
        click("Crear cuenta")
        await { auth.state.value.screen == AccessScreen.REGISTERED }
        compose.runOnIdle { auth.confirmRegistration() }
        assertEquals(1, registerCalls)
        assertEquals(1, calls)
        assertNull(grant)
        assertNull(auth.state.value.user)
        click("Iniciar sesión")
        assertEquals(AccessScreen.LOGIN, auth.state.value.screen)
        compose.onNodeWithContentDescription("Correo electrónico").performScrollTo()
            .assertTextEquals(actor.email)
        assertNull(grant)
        assertEquals(1, calls)
        compose.onNodeWithContentDescription("Contraseña").performScrollTo().performTextInput("password")
        click("Ingresar")
        await { auth.state.value.user != null }
        assertEquals(1, registerCalls)
        assertEquals(2, calls)
        assertNotNull(grant)
        if (!biometricFlow) return
        await { biometrics.state.value.actorUserId == actor.id }
        assertNull(biometricCredential)
        assertEquals(0, biometricSignatures)
        click("Habilitar acceso biométrico")
        compose.onNodeWithText("Ahora no").performClick()
        assertNull(biometricCredential)
        assertNotNull(auth.state.value.user)
        click("Habilitar acceso biométrico")
        compose.onNodeWithContentDescription("Contraseña actual").performScrollTo().performTextInput("password")
        compose.onNode(hasText("Habilitar acceso biométrico") and hasClickAction() and hasAnyAncestor(isDialog()))
            .performClick()
        await { biometricCredential != null && !biometrics.state.value.busy }
        assertEquals(actor.id, biometricCredential?.userId)
        assertEquals(1, biometricSignatures)
        click("Cerrar sesión")
        await { auth.state.value.user == null && biometrics.state.value.actorUserId == null && !auth.state.value.loading }
        assertNull(grant)
        assertNotNull(biometricCredential)
        click("Ingresar con biometría")
        await { auth.state.value.user != null && !auth.state.value.loading && !biometrics.state.value.busy }
        assertEquals(actor.id, auth.state.value.user?.id)
        assertEquals("signed-access", grant?.accessToken)
        assertEquals(1, registerCalls)
        assertEquals(1, biometricLogins)
        assertEquals(2, biometricSignatures)
    }

    @Test fun activityRecreationClearsPhotosAndPendingDraftWithoutPostingRegistration() {
        openApp()
        @Suppress("UNCHECKED_CAST")
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == AuthViewModel::class.java)
                return auth as T
            }
        }
        ViewModelProvider(compose.activity, factory)[AuthViewModel::class.java]
        beginRegistration()
        review(open = false)
        click("Usar esta foto")
        await { model.state.value.documentReceived && model.state.value.step == PhotoStep.INTRO }
        review(open = false)
        val file = requireNotNull(captured).file
        assertTrue(file.exists())
        assertNotNull(model.state.value.preview)
        compose.activityRule.scenario.recreate()
        assertSame(auth, ViewModelProvider(compose.activity)[AuthViewModel::class.java])
        model = ViewModelProvider(compose.activity)[PhotoReviewViewModel::class.java]
        compose.activity.setContent { AppContent() }
        compose.waitForIdle()
        assertFalse(file.exists())
        assertNull(model.state.value.preview)
        assertFalse(model.state.value.documentReceived)
        assertFalse(model.state.value.selfieReceived)
        assertEquals(AccessScreen.REGISTER, auth.state.value.screen)
        assertEquals(actor.email, auth.state.value.email)
        assertEquals(0, registerCalls)
        assertEquals(0, calls)
        assertNull(grant)
        compose.runOnIdle { auth.completeRegistrationPhotos(); auth.confirmRegistration() }
        assertEquals(AccessScreen.REGISTER, auth.state.value.screen)
        assertEquals(0, calls)
        beginRegistration()
        assertEquals(PhotoKind.DOCUMENT, model.state.value.kind)
        assertFalse(model.state.value.documentReceived)
        assertFalse(model.state.value.selfieReceived)
        click("Cancelar")
    }

    @Test fun loginAndAccountChangeDiscardThePhotoAndKeepConventionalAccess() {
        openApp()
        review()
        val file = requireNotNull(captured).file
        compose.runOnIdle { auth.login(actor.email, "password") }
        await { model.state.value.actorId == actor.id && !model.state.value.open }
        assertFalse(file.exists())
        assertNull(model.state.value.preview)
        assertEquals(1, calls)
        assertNotNull(grant)
        compose.runOnIdle { model.open() }
        review(open = false)
        val accountFile = requireNotNull(captured).file
        compose.runOnIdle {
            actor = actor.copy(id = "account-b")
            auth.login(actor.email, "password")
        }
        await { model.state.value.actorId == "account-b" && !model.state.value.open }
        assertFalse(accountFile.exists())
        assertNull(model.state.value.preview)
        assertEquals(2, calls)
        compose.runOnIdle { auth.logout() }
        await { auth.state.value.user == null && model.state.value.actorId == null }
        assertNull(grant)
    }
}
