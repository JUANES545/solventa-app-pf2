package co.solventa.mobile

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import co.solventa.mobile.core.designsystem.SolventaTheme
import co.solventa.mobile.core.designsystem.SolventaLayout
import co.solventa.mobile.core.ui.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SharedPresentationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun failedQueryKeepsFollowingActionStableInBothThemesAndLargeText() {
        val failed = mutableStateOf(false)
        val night = mutableStateOf(false)
        val scale = mutableFloatStateOf(1f)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale.floatValue)) {
                SolventaTheme(night.value) {
                    key(night.value, scale.floatValue) {
                        Box(Modifier.requiredSize(320.dp, 480.dp)) {
                            SolventaPage(header = { SolventaScreenHeader("Avisos") }) {
                                Box(Modifier.testTag("query-region")) {
                                    if (failed.value) SolventaLoadError("No pudimos cargar tus avisos",
                                        "Tu sesión no permite consultar estos avisos. Ingresa nuevamente.")
                                    else SolventaSkeletonList("Consultando tus avisos…")
                                }
                                OutlinedButton(onClick = {}, modifier = Modifier.fillMaxWidth()
                                    .heightIn(min = SolventaLayout.controlHeight).testTag("query-action")) {
                                    Text("Actualizar avisos")
                                }
                            }
                        }
                    }
                }
            }
        }
        for (dark in listOf(false, true)) for (font in listOf(1f, 1.5f, 2f)) {
            compose.runOnIdle { night.value = dark; scale.floatValue = font; failed.value = false }
            val loading = compose.onNodeWithTag("query-region").getUnclippedBoundsInRoot()
            val action = compose.onNodeWithTag("query-action").getUnclippedBoundsInRoot()
            capture("query-loading-$dark-$font")
            compose.runOnIdle { failed.value = true }
            val error = compose.onNodeWithTag("query-region").getUnclippedBoundsInRoot()
            val nextAction = compose.onNodeWithTag("query-action").getUnclippedBoundsInRoot()
            assertEquals((loading.bottom - loading.top).value, (error.bottom - error.top).value, 1f)
            assertEquals(action.top.value, nextAction.top.value, 1f)
            capture("query-error-$dark-$font")
            compose.onNodeWithTag("query-action").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        }
    }

    @Test fun confirmationIsImmediateWithoutMotionAndDoesNotReplayOnRecomposition() {
        val redraw = mutableIntStateOf(0)
        val motion = AccessBackgroundState().apply { resumed = true; durationScale = 0f }
        var markColor = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            SolventaTheme {
                markColor = androidx.compose.material3.MaterialTheme.colorScheme.tertiary.toArgb()
                CompositionLocalProvider(LocalAccessBackgroundState provides motion) {
                    if (redraw.intValue >= 0) SolventaConfirmationMark("confirmed-result")
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        val before = compose.onRoot().captureToImage().asAndroidBitmap()
        var markPixels = 0
        for (x in 0 until before.width) for (y in 0 until before.height) {
            if (before.getPixel(x, y) == markColor) markPixels++
        }
        assertTrue("The static confirmation must be visible without waiting for animation", markPixels > 0)
        compose.runOnIdle { redraw.intValue++ }
        compose.mainClock.advanceTimeByFrame()
        val after = compose.onRoot().captureToImage().asAndroidBitmap()
        assertTrue("Recomposition must keep the completed mark", before.sameAs(after))
        compose.runOnIdle { motion.resumed = false }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { motion.resumed = true; motion.durationScale = 1f }
        compose.mainClock.advanceTimeByFrame()
        assertTrue("Resuming must not replay a result already shown",
            before.sameAs(compose.onRoot().captureToImage().asAndroidBitmap()))
    }

    @Test fun sharedStatesInBothThemesAndThreeTextScales() {
        val night = mutableStateOf(false)
        val scale = mutableFloatStateOf(1f)
        val example = mutableIntStateOf(0)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale.floatValue)) {
                SolventaTheme(night.value) {
                    key(night.value, scale.floatValue, example.intValue) {
                        Box(Modifier.requiredSize(320.dp, 480.dp)) {
                            SolventaPage(header = { SolventaScreenHeader("Mis gestiones") }) {
                                when (example.intValue) {
                                    0 -> SolventaSkeletonList("Consultando tus avisos…")
                                    1 -> SolventaSkeletonDetail("Consultando el reporte…")
                                    2 -> SolventaEmptyState("No tienes avisos por ahora.", SolventaEmptyKind.NOTIFICATIONS)
                                    3 -> SolventaEmptyState("No hay archivos asociados a este siniestro.")
                                    4 -> SolventaMetadataDetails(
                                        "11111111-1111-4111-8111-111111111111",
                                        listOf("Observado en este teléfono" to "9 nov 2026, 10:06 (hora local)",
                                            "Resultado del servidor" to "9 nov 2026, 10:05 (hora local)"),
                                    )
                                    else -> {
                                        SolventaConfirmationMark("synthetic-result")
                                        Text("Reporte enviado")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        for (dark in listOf(false, true)) {
            for (font in listOf(1f, 1.5f, 2f)) {
                for (state in 0..5) {
                    compose.runOnIdle {
                        night.value = dark
                        scale.floatValue = font
                        example.intValue = state
                    }
                    if (state == 4) {
                        compose.onNodeWithText("Ver detalles").performScrollTo().performClick()
                        compose.onNodeWithText("Referencia: 11111111-1111-4111-8111-111111111111")
                            .performScrollTo().assertIsDisplayed()
                    }
                    capture("shared-${if (dark) "dark" else "light"}-$font-$state")
                    if (state == 4) {
                        compose.onNodeWithText("Resultado del servidor: 9 nov 2026, 10:05 (hora local)")
                            .performScrollTo().assertIsDisplayed()
                    }
                }
            }
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val folder = File(compose.activity.filesDir, "ui-shared").apply { check(isDirectory || mkdirs()) }
        File(folder, "$name.png").outputStream().use {
            check(compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
