package co.solventa.mobile.core.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import co.solventa.mobile.core.designsystem.SolventaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SolventaPresentationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun skeletonHasOneLoadingAnnouncementAndNoFakeActions() {
        compose.setContent { SolventaTheme { SolventaSkeletonList("Consultando tus avisos…") } }
        compose.onAllNodesWithContentDescription("Consultando tus avisos…").assertCountEquals(1)
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
        compose.onAllNodes(isFocusable()).assertCountEquals(0)
    }

    @Test fun loadErrorKeepsSkeletonHeightAtNormalAndLargeText() {
        val failed = mutableStateOf(false)
        val detail = mutableStateOf(false)
        val scale = mutableFloatStateOf(1f)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale.floatValue)) {
                SolventaTheme {
                    Box(Modifier.testTag("query-region")) {
                        if (failed.value) SolventaLoadError("No pudimos cargar tus avisos",
                            "Tu sesión no permite consultar estos avisos. Ingresa nuevamente.", detail = detail.value)
                        else if (detail.value) SolventaSkeletonDetail("Consultando…")
                        else SolventaSkeletonList("Consultando…")
                    }
                }
            }
        }
        for (font in listOf(1f, 2f)) for (isDetail in listOf(false, true)) {
            compose.runOnIdle { failed.value = false; detail.value = isDetail; scale.floatValue = font }
            val loadingBounds = compose.onNodeWithTag("query-region").getUnclippedBoundsInRoot()
            compose.runOnIdle { failed.value = true }
            val errorBounds = compose.onNodeWithTag("query-region").getUnclippedBoundsInRoot()
            val loadingHeight = loadingBounds.bottom - loadingBounds.top
            val errorHeight = errorBounds.bottom - errorBounds.top
            assertTrue("Error must not collapse its loading region", errorHeight >= loadingHeight)
            if (!isDetail) assertEquals(loadingHeight.value, errorHeight.value, 1f)
            compose.onNodeWithContentDescription("Consultando…").assertDoesNotExist()
            compose.onAllNodes(hasClickAction()).assertCountEquals(0)
            compose.onAllNodes(SemanticsMatcher.expectValue(
                SemanticsProperties.LiveRegion, androidx.compose.ui.semantics.LiveRegionMode.Polite,
            )).assertCountEquals(1)
        }
    }

    @Test fun skeletonIsRemovedWhenContentArrivesOrFails() {
        val state = mutableIntStateOf(0)
        compose.setContent {
            SolventaTheme {
                when (state.intValue) {
                    0 -> SolventaSkeletonDetail("Consultando el reporte…")
                    1 -> Text("En revisión")
                    else -> Text("No pudimos consultar el reporte.")
                }
            }
        }
        compose.onNodeWithContentDescription("Consultando el reporte…").assertExists()
        compose.runOnIdle { state.intValue = 1 }
        compose.onNodeWithContentDescription("Consultando el reporte…").assertDoesNotExist()
        compose.onNodeWithText("En revisión").assertExists()
        compose.runOnIdle { state.intValue = 2 }
        compose.onNodeWithContentDescription("Consultando el reporte…").assertDoesNotExist()
        compose.onNodeWithText("No pudimos consultar el reporte.").assertExists()
    }

    @Test fun detailsPreserveFullReferenceAndDistinguishLocalFromServerDates() {
        val entries = mutableStateOf(listOf("En cola" to "9 nov, 10:00", "Resultado del servidor" to "9 nov, 10:05"))
        val reference = "11111111-1111-4111-8111-111111111111"
        compose.setContent {
            SolventaTheme { SolventaMetadataDetails(reference, entries.value) }
        }
        compose.onNodeWithText("Referencia: $reference").assertDoesNotExist()
        compose.onNodeWithText("Ver detalles").performClick()
        compose.onNodeWithText("Referencia: $reference").assertIsDisplayed()
        compose.onNodeWithText("En cola: 9 nov, 10:00").assertExists()
        compose.onNodeWithText("Resultado del servidor: 9 nov, 10:05").assertExists()
        compose.onNodeWithText("Ocultar detalles")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expandido"))
        compose.runOnIdle {
            entries.value = entries.value + ("Observado en este teléfono" to "9 nov, 10:06")
        }
        compose.onNodeWithText("Ocultar detalles").assertExists()
        compose.onNodeWithText("Observado en este teléfono: 9 nov, 10:06").assertExists()
        compose.onNodeWithText("Ocultar detalles").performClick()
        compose.onNodeWithText("Referencia: $reference").assertDoesNotExist()
    }

    @Test fun changingReferenceClosesPreviousDetails() {
        val reference = mutableStateOf("anterior")
        compose.setContent { SolventaTheme { SolventaMetadataDetails(reference.value) } }
        compose.onNodeWithText("Ver detalles").performClick()
        compose.onNodeWithText("Referencia: anterior").assertExists()
        compose.runOnIdle { reference.value = "nuevo" }
        compose.onNodeWithText("Referencia: anterior").assertDoesNotExist()
        compose.onNodeWithText("Ver detalles").assertExists()
    }

    @Test fun emptyIllustrationAnnouncesOnlyItsRealMessage() {
        compose.setContent {
            SolventaTheme { SolventaEmptyState("No tienes avisos por ahora.", SolventaEmptyKind.NOTIFICATIONS) }
        }
        compose.onAllNodesWithText("No tienes avisos por ahora.").assertCountEquals(1)
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    @Test fun headerKeepsBackActionAndHeading() {
        var backs = 0
        compose.setContent {
            SolventaTheme { SolventaScreenHeader("Mis siniestros", "Volver al inicio", { backs++ }) }
        }
        compose.onNodeWithText("Mis siniestros").assert(isHeading())
        compose.onNodeWithText("Volver al inicio").performClick()
        assertEquals(1, backs)
    }
}
