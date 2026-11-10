package co.solventa.mobile.feature.auth

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
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
import co.solventa.mobile.core.ui.AccessBackgroundState
import co.solventa.mobile.core.ui.LocalAccessBackgroundState
import co.solventa.mobile.feature.photo.PhotoKind
import co.solventa.mobile.feature.photo.PhotoReviewScreen
import co.solventa.mobile.feature.photo.PhotoState
import co.solventa.mobile.feature.photo.PhotoStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
// Legacy graphics simulate glyph widths and line breaks; these assertions need real font metrics.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccessPresentationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun accessRoutesHaveNoManualMotionControlAndKeepTheirSharedPhase() {
        val background = AccessBackgroundState().apply { resumed = true; durationScale = 1f; progress = .47f }
        val registration = mutableStateOf(false)
        compose.setContent {
            SolventaTheme {
                CompositionLocalProvider(LocalAccessBackgroundState provides background) {
                    if (registration.value) RegistrationScreen() else LoginScreen()
                }
            }
        }
        for (route in listOf(false, true, false)) {
            compose.runOnIdle { registration.value = route }
            for (label in listOf("Pausar fondo", "Reanudar fondo", "Fondo estático")) {
                compose.onNodeWithText(label).assertDoesNotExist()
            }
            compose.runOnIdle { assertEquals(.47f, background.progress, 0f) }
        }
        compose.runOnIdle { background.durationScale = 0f }
        compose.onNodeWithText("Fondo estático").assertDoesNotExist()
    }

    @Test fun compactLoginKeepsBiometricActionsCenteredAndCredentialsEditableAtTwoHundredPercent() {
        var credentials: Pair<String, String>? = null
        compactContent {
            LoginScreen(
                error = AuthError.INVALID_CREDENTIALS,
                onBiometricLogin = {},
                onLogin = { email, password -> credentials = email to password },
            )
        }
        compose.onNodeWithContentDescription("Correo electrónico").performScrollTo().performTextInput("sintetico@example.org")
        compose.onNodeWithContentDescription("Contraseña").performScrollTo().performTextInput("synthetic-password")
        compose.onNodeWithText("Revisa tu correo y contraseña.").performScrollTo().assertIsDisplayed()
        assertCenteredAction("Ingresar").performClick()
        assertEquals("sintetico@example.org" to "synthetic-password", credentials)
        assertCenteredAction("Ingresar con biometría", multiline = true)
        assertCenteredAction("Crear cuenta")
        assertCenteredAction("Consultar biometría")
    }

    @Test fun compactTermsKeepReferenceNoticeAndOptionalChoiceSeparateFromRequiredChoice() {
        var accepted = false
        var backs = 0
        compactContent { RegistrationReferenceScreen({ accepted = it }, { backs++ }) }
        val continueButton = compose.onNode(hasText("Continuar") and hasClickAction())
        continueButton.performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(
            "Autorizo la consulta de información de Open Finance para obtener una oferta personalizada. (Opcional)",
        ).performScrollTo().performClick()
        continueButton.performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Acepto los términos y el tratamiento de datos personales.")
            .performScrollTo().performClick()
        compose.onNodeWithText(
            "Textos de referencia. Estas selecciones no registran consentimiento legal ni autorizan acceso a datos financieros.",
        ).performScrollTo().assertIsDisplayed()
        assertCenteredAction("Continuar").assertIsEnabled().performClick()
        assertTrue(accepted)
        assertCenteredAction("Volver al formulario").performClick()
        assertEquals(1, backs)
    }

    @Test fun compactRegistrationConfirmationPreservesFullEmailPhotoLimitAndCancellation() {
        val email = "nombre.completo.de.prueba.sintetica@example.org"
        var confirms = 0
        var cancels = 0
        compactContent { RegistrationConfirmationScreen(email, { confirms++ }, { cancels++ }) }
        compose.onNodeWithText(email).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Las fotos no se envían al servidor ni verifican tu identidad.")
            .performScrollTo().assertIsDisplayed()
        assertCenteredAction("Crear cuenta").performClick()
        assertCenteredAction("Cancelar registro").performClick()
        assertEquals(1, confirms)
        assertEquals(1, cancels)
    }

    @Test fun compactPhotoResultKeepsLocalScopeCleanupAndFullAccountReturnLabel() {
        var finishes = 0
        var restarts = 0
        compactContent {
            PhotoReviewScreen(
                PhotoState(actorId = "synthetic-actor", step = PhotoStep.COMPLETE,
                    documentReceived = true, selfieReceived = true),
                {}, {}, {}, onFinished = { finishes++ }, onRestart = { restarts++ }, camera = {},
            )
        }
        for (text in listOf("Documento revisado", "Selfie revisada", "Eliminamos los archivos temporales.",
            "Las fotos no verifican identidad ni inician sesión.")) {
            compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
        }
        assertCenteredAction("Volver a mi cuenta").performClick()
        assertCenteredAction("Repetir documento").performClick()
        assertEquals(1, finishes)
        assertEquals(1, restarts)
    }

    @Test fun compactSelfiePreservesPrivacyMetricsAndStorageErrorWithoutStartingCamera() {
        var cameras = 0
        compactContent {
            PhotoReviewScreen(
                PhotoState(kind = PhotoKind.SELFIE, error = R.string.photo_storage),
                { cameras++ }, {}, {}, onRestart = {}, camera = {},
            )
        }
        compose.onNodeWithText("No pudimos preparar o eliminar la foto temporal. Intenta de nuevo antes de continuar.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(
            "Analizamos la selfie en este teléfono. ML Kit envía a Google métricas técnicas y de uso, no la imagen ni el resultado.",
        ).performScrollTo().assertIsDisplayed()
        assertCenteredAction("Cómo se usan los datos", multiline = true).performClick()
        compose.onNodeWithText(
            org.robolectric.RuntimeEnvironment.getApplication().getString(R.string.selfie_privacy_body),
        ).assertExists()
        compose.onNode(hasText("Entendido") and hasClickAction() and hasAnyAncestor(isDialog()))
            .assertIsDisplayed().performClick()
        assertEquals(0, cameras)
        assertCenteredAction("Abrir cámara").performClick()
        assertEquals(1, cameras)
    }

    private fun compactContent(content: @Composable () -> Unit) {
        val background = AccessBackgroundState().apply { resumed = true; durationScale = 0f }
        compose.setContent {
            SolventaTheme(darkTheme = true) {
                CompositionLocalProvider(
                    LocalAccessBackgroundState provides background,
                    LocalDensity provides Density(LocalDensity.current.density, 2f),
                ) {
                    Box(Modifier.requiredSize(320.dp, 480.dp)) { content() }
                }
            }
        }
    }

    private fun assertCenteredAction(label: String, multiline: Boolean = false): SemanticsNodeInteraction {
        val button = compose.onNode(hasText(label) and hasClickAction()).performScrollTo().assertIsDisplayed()
        val results = mutableListOf<TextLayoutResult>()
        val text = compose.onNodeWithText(label, useUnmergedTree = true)
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val layout = results.single()
        assertEquals(TextAlign.Center, layout.layoutInput.style.textAlign)
        assertEquals(label, layout.layoutInput.text.text)
        assertTrue("The paragraph must fit vertically: $label",
            layout.multiParagraph.height <= layout.size.height + 1f)
        assertFalse(layout.multiParagraph.didExceedMaxLines)
        assertEquals("All characters must be laid out: $label", label.length,
            layout.getLineEnd(layout.lineCount - 1, visibleEnd = false))
        // A cached paragraph can retain the parent's width while Text measures to its intrinsic width.
        for (line in 0 until layout.lineCount) {
            assertFalse(layout.isLineEllipsized(line))
            assertTrue("The actual line must fit: $label, line=$line",
                layout.getLineRight(line) - layout.getLineLeft(line) <= layout.size.width + 1f)
        }
        val glyphs = label.indices.map(layout::getBoundingBox)
        assertTrue("The complete glyph span must fit: $label",
            glyphs.maxOf { it.right } - glyphs.minOf { it.left } <= layout.size.width + 1f)
        assertTrue("The complete glyph height must fit: $label",
            glyphs.minOf { it.top } >= -1f && glyphs.maxOf { it.bottom } <= layout.size.height + 1f)
        if (multiline) assertTrue("Exercise a wrapped label: $label", layout.lineCount > 1)
        val bounds = button.fetchSemanticsNode().boundsInRoot
        val labelNode = text.fetchSemanticsNode()
        val labelBounds = labelNode.boundsInRoot
        assertEquals("The label must not be clipped vertically: $label",
            labelNode.layoutInfo.height.toFloat(), labelBounds.height, 1f)
        assertEquals("The label must not be clipped horizontally: $label",
            labelNode.layoutInfo.width.toFloat(), labelBounds.width, 1f)
        assertTrue(labelBounds.left >= bounds.left - 1 && labelBounds.right <= bounds.right + 1)
        assertTrue(labelBounds.top >= bounds.top - 1 && labelBounds.bottom <= bounds.bottom + 1)
        return button
    }
}
