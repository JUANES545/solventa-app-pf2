package co.solventa.mobile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import co.solventa.mobile.core.designsystem.SolventaTheme
import co.solventa.mobile.core.ui.AccessBackgroundState
import co.solventa.mobile.core.ui.LocalAccessBackgroundState
import co.solventa.mobile.feature.auth.*
import co.solventa.mobile.feature.photo.PhotoKind
import co.solventa.mobile.feature.photo.PhotoReviewScreen
import co.solventa.mobile.feature.photo.PhotoState
import co.solventa.mobile.feature.photo.PhotoStep
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class AccessPresentationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun captureSyntheticAccessAndPhotoStatesInBothThemes() {
        captureStates(compact = false)
    }

    @Test fun captureCompactSyntheticAccessAndPhotoStatesAtTwoHundredPercentInBothThemes() {
        captureStates(compact = true)
    }

    private fun captureStates(compact: Boolean) {
        val dark = mutableStateOf(false)
        val fixture = mutableStateOf(Fixture.LOGIN)
        val background = AccessBackgroundState().apply { resumed = true; durationScale = 0f }
        val preview = syntheticDocument()
        compose.setContent {
            SolventaTheme(darkTheme = dark.value) {
                CompositionLocalProvider(
                    LocalAccessBackgroundState provides background,
                    LocalDensity provides Density(LocalDensity.current.density, if (compact) 2f else 1f),
                ) {
                    Box((if (compact) Modifier.requiredSize(320.dp, 480.dp) else Modifier.fillMaxSize())
                        .testTag("access-viewport")) {
                        key(dark.value, fixture.value) {
                            when (fixture.value) {
                                Fixture.LOGIN -> LoginScreen(initialEmail = "sintetico@example.org")
                                Fixture.LOGIN_ERROR -> LoginScreen(error = AuthError.INVALID_CREDENTIALS)
                                Fixture.LOGIN_LOADING -> LoginScreen(loading = true)
                                Fixture.REGISTER -> RegistrationScreen()
                                Fixture.TERMS -> RegistrationReferenceScreen({}, {})
                                Fixture.CONFIRMATION -> RegistrationConfirmationScreen("sintetico@example.org", {}, {})
                                Fixture.SUCCESS -> RegistrationSuccessScreen("sintetico@example.org", {})
                                else -> {
                                    val state = when (fixture.value) {
                                        Fixture.SELFIE -> PhotoState(kind = PhotoKind.SELFIE)
                                        Fixture.REVIEW -> PhotoState(step = PhotoStep.REVIEW, preview = preview)
                                        Fixture.PROCESSING -> PhotoState(step = PhotoStep.PROCESSING)
                                        Fixture.COMPLETE -> PhotoState(step = PhotoStep.COMPLETE,
                                            documentReceived = true, selfieReceived = true)
                                        Fixture.PHOTO_ERROR -> PhotoState(error = R.string.photo_denied)
                                        else -> PhotoState()
                                    }
                                    PhotoReviewScreen(state, {}, {}, {}, onRestart = {}, camera = {})
                                }
                            }
                        }
                    }
                }
            }
        }
        val directory = File(compose.activity.filesDir, "ui-access")
        assertTrue(directory.isDirectory || directory.mkdirs())
        for (night in listOf(false, true)) {
            for (screen in Fixture.entries) {
                compose.runOnIdle { dark.value = night; fixture.value = screen }
                compose.waitForIdle()
                val prefix = "${if (night) "dark" else "light"}-${screen.name.lowercase()}" +
                    if (compact) "-compact-200" else ""
                val title = when (screen) {
                    Fixture.LOGIN, Fixture.LOGIN_ERROR, Fixture.LOGIN_LOADING -> R.string.login_title
                    Fixture.REGISTER -> R.string.registration_title
                    Fixture.TERMS -> R.string.registration_reference_title
                    Fixture.CONFIRMATION -> R.string.registration_finish
                    Fixture.SUCCESS -> R.string.registration_success
                    Fixture.SELFIE -> R.string.photo_selfie_title
                    Fixture.REVIEW -> R.string.photo_review
                    Fixture.PROCESSING -> R.string.photo_processing
                    Fixture.COMPLETE -> R.string.photo_summary
                    else -> R.string.photo_document_title
                }
                compose.onNodeWithText(compose.activity.getString(title)).performScrollTo().assertIsDisplayed()
                capture(directory, prefix)
                val action = when (screen) {
                    Fixture.LOGIN -> "Consultar biometría"
                    Fixture.REGISTER, Fixture.COMPLETE -> "Continuar"
                    Fixture.TERMS -> "Continuar"
                    Fixture.CONFIRMATION -> "Cancelar registro"
                    Fixture.SUCCESS -> "Iniciar sesión"
                    Fixture.DOCUMENT, Fixture.SELFIE, Fixture.PHOTO_ERROR -> "Abrir cámara"
                    Fixture.REVIEW -> "Repetir foto"
                    else -> null
                }
                action?.let {
                    assertFullCenteredAction(it)
                    capture(directory, "$prefix-actions")
                }
                if (screen == Fixture.COMPLETE) {
                    compose.onNodeWithText(compose.activity.getString(R.string.photo_local_limit)).assertExists()
                    compose.onNodeWithText(compose.activity.getString(R.string.photo_complete_body)).assertExists()
                }
                if (screen == Fixture.SELFIE) {
                    compose.onNodeWithText(compose.activity.getString(R.string.selfie_privacy_note)).assertExists()
                    compose.onNodeWithText("Cómo se usan los datos").performScrollTo().performClick()
                    val inDialog = hasAnyAncestor(isDialog())
                    compose.onNode(isDialog()).assertIsDisplayed()
                    compose.onNode(hasText("Privacidad de la selfie") and inDialog).assertIsDisplayed()
                    compose.onNode(hasText(compose.activity.getString(R.string.selfie_privacy_body)) and inDialog)
                        .assertExists()
                    val close = compose.onNode(hasText("Entendido") and hasClickAction() and inDialog)
                    close.assertIsDisplayed().assertIsEnabled()
                    capture(directory, "$prefix-privacy", isDialog())
                    close.performClick()
                    compose.onNode(isDialog()).assertDoesNotExist()
                }
            }
        }
    }

    private fun assertFullCenteredAction(label: String) {
        val button = compose.onNode(hasText(label) and hasClickAction()).performScrollTo().assertIsDisplayed()
        val text = compose.onNodeWithText(label, useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertEquals(TextAlign.Center, layout.layoutInput.style.textAlign)
        assertEquals(label, layout.layoutInput.text.text)
        assertTrue("The paragraph must fit vertically: $label",
            layout.multiParagraph.height <= layout.size.height + 1f)
        assertFalse(layout.multiParagraph.didExceedMaxLines)
        assertEquals("All characters must be laid out: $label", label.length,
            layout.getLineEnd(layout.lineCount - 1, visibleEnd = false))
        // Paragraph width may retain the parent's constraint rather than Text's intrinsic width.
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
        val control = button.fetchSemanticsNode()
        val textNode = text.fetchSemanticsNode()
        val textBounds = textNode.boundsInRoot
        assertEquals("The label must not be clipped vertically: $label",
            textNode.layoutInfo.height.toFloat(), textBounds.height, 1f)
        assertEquals("The label must not be clipped horizontally: $label",
            textNode.layoutInfo.width.toFloat(), textBounds.width, 1f)
        val bounds = control.boundsInRoot
        assertTrue("Control keeps its minimum 52dp", control.layoutInfo.height >=
            52f * compose.activity.resources.displayMetrics.density - 1)
        assertTrue(textBounds.left >= bounds.left - 1 && textBounds.right <= bounds.right + 1)
        assertTrue(textBounds.top >= bounds.top - 1 && textBounds.bottom <= bounds.bottom + 1)
    }

    private fun capture(directory: File, name: String, target: SemanticsMatcher = hasTestTag("access-viewport")) {
        val image = compose.onNode(target).captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun syntheticDocument(): Bitmap =
        Bitmap.createBitmap(640, 400, Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this)
            canvas.drawColor(Color.rgb(235, 244, 246))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(9, 69, 84); textSize = 32f }
            canvas.drawText("DOCUMENTO SINTÉTICO", 32f, 64f, paint)
            canvas.drawRect(32f, 104f, 180f, 280f, paint)
            paint.strokeWidth = 12f
            canvas.drawLine(220f, 132f, 560f, 132f, paint)
            canvas.drawLine(220f, 184f, 500f, 184f, paint)
            canvas.drawLine(220f, 236f, 540f, 236f, paint)
        }

    private enum class Fixture {
        LOGIN, LOGIN_ERROR, LOGIN_LOADING, REGISTER, TERMS, CONFIRMATION, SUCCESS,
        DOCUMENT, SELFIE, REVIEW, PROCESSING, COMPLETE, PHOTO_ERROR,
    }
}
