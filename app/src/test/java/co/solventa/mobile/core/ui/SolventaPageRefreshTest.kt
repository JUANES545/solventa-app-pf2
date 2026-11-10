package co.solventa.mobile.core.ui

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import co.solventa.mobile.core.designsystem.SolventaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalTestApi::class)
@Config(application = Application::class, sdk = [35])
class SolventaPageRefreshTest {
    @get:Rule val compose = createComposeRule()

    @Test fun shortEmptyPageCanRefreshBySwipeAndBlocksDuplicateRequests() {
        val refreshing = mutableStateOf(false)
        var refreshes = 0
        compose.setContent {
            SolventaTheme(false) {
                SolventaPage(onRefresh = { refreshes++; refreshing.value = true }, isRefreshing = refreshing.value) {
                    SolventaRefreshing(refreshing.value) { Text("Sin adjuntos") }
                }
            }
        }
        val initialBounds = compose.onNodeWithText("Sin adjuntos").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("page-refresh").performTouchInput {
            swipe(Offset(centerX, height * .15f), Offset(centerX, height * .9f), 500)
        }
        compose.waitUntil(5_000) { refreshes == 1 }
        compose.onNodeWithTag("page-refresh-indicator").assertIsDisplayed()
        compose.onNodeWithTag("content-refresh-indicator").assertDoesNotExist()
        compose.onNodeWithText("Sin adjuntos").assertIsDisplayed()
        assertEquals(initialBounds, compose.onNodeWithText("Sin adjuntos").fetchSemanticsNode().boundsInRoot)
        assertTrue(compose.onNodeWithTag("page-refresh").fetchSemanticsNode().config[SemanticsActions.CustomActions].isEmpty())
        compose.onNodeWithTag("page-refresh").performTouchInput { swipeDown() }
        compose.runOnIdle { assertEquals(1, refreshes); refreshing.value = false }
        compose.onNodeWithTag("page-refresh").performCustomAccessibilityActionWithLabel("Actualizar")
        compose.runOnIdle { assertEquals(2, refreshes) }
    }

    @Test fun automaticLoadingUsesOnlyTheContentIndicatorWithoutMovingContent() {
        val refreshing = mutableStateOf(false)
        compose.setContent {
            SolventaTheme(false) {
                SolventaPage(onRefresh = {}, isRefreshing = refreshing.value) {
                    SolventaRefreshing(refreshing.value) { Text("Póliza vigente") }
                }
            }
        }
        val bounds = compose.onNodeWithText("Póliza vigente").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { refreshing.value = true }
        compose.onNodeWithTag("page-refresh-indicator").assertDoesNotExist()
        compose.onNodeWithTag("content-refresh-indicator").assertIsDisplayed()
        assertEquals(bounds, compose.onNodeWithText("Póliza vigente").fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { refreshing.value = false }
        compose.onNodeWithTag("content-refresh-indicator").assertDoesNotExist()
    }

    @Test fun initialLoadingDoesNotAddAPageSpinnerOverTheSkeleton() {
        compose.setContent {
            SolventaTheme(false) {
                SolventaPage(onRefresh = {}, isRefreshing = true) {
                    SolventaSkeletonList("Cargando pólizas")
                }
            }
        }
        compose.onNodeWithTag("page-refresh-indicator").assertDoesNotExist()
        compose.onNodeWithTag("content-refresh-indicator").assertDoesNotExist()
        compose.onNodeWithContentDescription("Cargando pólizas").assertIsDisplayed()
    }

    @Test fun busyOperationsCannotRefreshAndAccessibleActionReturnsWhenReady() {
        val enabled = mutableStateOf(false)
        var calls = 0
        compose.setContent {
            SolventaTheme(false) {
                SolventaPage(onRefresh = { calls++ }, refreshEnabled = enabled.value) { Text("Borrador") }
            }
        }
        assertTrue(compose.onNodeWithTag("page-refresh").fetchSemanticsNode().config[SemanticsActions.CustomActions].isEmpty())
        compose.onNodeWithTag("page-refresh").performTouchInput { swipeDown() }
        compose.runOnIdle { assertEquals(0, calls); enabled.value = true }
        compose.onNodeWithTag("page-refresh").performCustomAccessibilityActionWithLabel("Actualizar")
        compose.runOnIdle { assertEquals(1, calls) }
    }

    @Test fun nonQueryPagesDoNotExposeRefreshGesturesOrActions() {
        compose.setContent { SolventaTheme(false) { SolventaPage { Text("Formulario") } } }
        compose.onNodeWithTag("page-refresh").assertDoesNotExist()
        compose.onNodeWithText("Formulario").assertIsDisplayed()
    }
}
