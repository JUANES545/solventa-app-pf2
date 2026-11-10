package co.solventa.mobile.feature.claim

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import co.solventa.mobile.R
import co.solventa.mobile.core.designsystem.SolventaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w320dp-h640dp")
class ClaimStatusScreenTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = RuntimeEnvironment.getApplication()
    private fun text(id: Int) = app.getString(id)

    @Test fun summaryDateOmitsSecondsAndTechnicalZoneWhileKeepingTheLocalMinute() {
        val minute = statusDate.truncatedTo(java.time.temporal.ChronoUnit.MINUTES)
        val summary = claimStatusDate(minute)
        assertEquals(summary, claimStatusDate(minute.plusSeconds(37)))
        assertFalse(summary.contains(java.time.ZoneId.systemDefault().id))
        assertNotEquals(summary, claimStatusDate(minute.plusSeconds(60)))
    }

    @Test fun loadingErrorsAndEmptyAreDifferentAndRetryRemainsActionable() {
        val state = mutableStateOf(ClaimStatusState(busy = true))
        var retries = 0
        var backs = 0
        compose.setContent {
            SolventaTheme(false) { ClaimStatusScreen(state.value, {}, { retries++ }, { backs++ }) }
        }
        compose.onNodeWithContentDescription(text(R.string.claim_status_loading)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.claim_status_refresh)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.claim_status_empty)).assertDoesNotExist()
        for (error in ClaimStatusError.entries) {
            compose.runOnIdle { state.value = ClaimStatusState(error = error) }
            compose.onNodeWithText(text(claimStatusErrorText(error))).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(text(R.string.claim_status_empty)).assertDoesNotExist()
            compose.onNodeWithText(text(R.string.claim_status_retry)).performScrollTo().performClick()
        }
        compose.runOnIdle { state.value = ClaimStatusState() }
        compose.onNodeWithText(text(R.string.claim_status_empty)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.claim_status_back)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(5, retries); assertEquals(1, backs) }
    }

    @Test fun serverStatesDatesAndLongReferencesRemainReadableInBothThemesAndLargeText() {
        val dark = mutableStateOf(false)
        val state = mutableStateOf(ClaimStatusState(claims = listOf(statusClaim)))
        var selected = ""
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                SolventaTheme(dark.value) { ClaimStatusScreen(state.value, { selected = it.id }, {}, {}) }
            }
        }
        compose.onNode(hasText(operationalReference(statusClaim.id), substring = true)).performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(statusClaim.id, selected) }
        for (theme in listOf(false, true)) {
            for (progress in ClaimProgress.entries) {
                compose.runOnIdle {
                    dark.value = theme
                    state.value = ClaimStatusState(selectedId = statusClaim.id,
                        detail = ClaimStatus(statusClaim.id, progress, "Detalle desde el servicio.", statusDate))
                }
                compose.onNodeWithText(text(claimProgressText(progress))).performScrollTo().assertIsDisplayed()
                    .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
                compose.onNodeWithText(app.getString(R.string.claim_status_updated, displayDate()))
                    .performScrollTo().assertIsDisplayed()
                compose.onNodeWithText(app.getString(R.string.evidence_claim_reference, operationalReference(statusClaim.id)))
                    .performScrollTo().assertIsDisplayed()
                compose.onNodeWithText(text(R.string.claim_status_refresh)).performScrollTo().assertHeightIsAtLeast(48.dp)
            }
        }
        compose.runOnIdle { state.value = state.value.copy(detail = null, error = ClaimStatusError.NETWORK) }
        compose.onNodeWithText(text(R.string.claim_status_closed)).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.claim_status_updated, displayDate())).assertDoesNotExist()
    }

    private fun displayDate() = app.getString(R.string.operational_date_device_zone,
        claimStatusDate(statusDate), java.time.ZoneId.systemDefault().id)
}
