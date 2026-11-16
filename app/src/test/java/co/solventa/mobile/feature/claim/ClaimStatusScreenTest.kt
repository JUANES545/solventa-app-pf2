package co.solventa.mobile.feature.claim

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalTestApi::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w320dp-h640dp")
class ClaimStatusScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = RuntimeEnvironment.getApplication()
    private fun text(id: Int) = app.getString(id)

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun reportAndFullReferencesRemainReadableInBothThemesAndLargeText() = checkReportReferences("portrait")

    @Test
    @Config(qualifiers = "w640dp-h320dp-land")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun reportAndReferencesRemainReadableInLandscape() = checkReportReferences("landscape")

    private fun checkReportReferences(orientation: String) {
        val claim = statusClaim.copy(type = "Choque en estacionamiento",
            policyId = "c0ca4037-5e08-4ddf-967a-377b5e420102", place = "Parqueadero de prueba",
            description = "Daño de prueba en la puerta del vehículo. Se adjuntó una fotografía del daño y el recibo.",
            registeredAt = statusDate)
        val dark = mutableStateOf(false)
        val scale = mutableStateOf(1f)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale.value)) {
                SolventaTheme(dark.value) {
                    ClaimStatusScreen(ClaimStatusState(actor = "actor-a", selectedId = claim.id,
                        selectedClaim = claim, caseLoaded = true,
                        detail = ClaimStatus(claim.id, ClaimProgress.REPORTED, "Recibimos tu reporte.", statusDate)),
                        {}, {}, {}, attachments = { _, _ -> })
                }
            }
        }
        compose.onNodeWithText(text(R.string.ui_show_details)).performScrollTo().performClick()
        for (theme in listOf(false, true)) for (fontScale in listOf(1f, 2f)) {
            compose.runOnIdle { dark.value = theme; scale.value = fontScale }
            compose.onNodeWithText(text(R.string.claim_status_report_details)).performScrollTo().assertIsDisplayed()
            saveVisual("$orientation-report-$theme-$fontScale")
            for (value in listOf(claim.description, claim.id, checkNotNull(claim.policyId))) {
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                compose.onNodeWithText(value).performScrollTo().assertIsDisplayed()
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertFalse("Full report values must not be clipped", layouts.single().hasVisualOverflow)
            }
            saveVisual("$orientation-references-$theme-$fontScale")
        }
    }

    private fun saveVisual(name: String) {
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height,
                android.graphics.Bitmap.Config.ARGB_8888)
            try {
                view.draw(android.graphics.Canvas(bitmap))
                val target = java.io.File("build/reports/claim-case/$name.png")
                check(target.parentFile.mkdirs() || target.parentFile.isDirectory)
                target.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
        }
    }

    @Test fun selectedCaseUsesItsNameAndDetailsShowReportFieldsAndScopedAttachments() {
        val claim = statusClaim.copy(type = "Choque en estacionamiento",
            policyId = "c0ca4037-5e08-4ddf-967a-377b5e420102", place = "Lugar sintético",
            registeredAt = statusDate)
        val state = mutableStateOf(ClaimStatusState(actor = "actor-a", selectedId = claim.id,
            selectedClaim = claim, caseLoaded = true,
            detail = ClaimStatus(claim.id, ClaimProgress.REPORTED, "Recibimos tu reporte.", statusDate)))
        compose.setContent {
            SolventaTheme(false) {
                ClaimStatusScreen(state.value, {}, {}, {}, attachments = { scope, _ ->
                    androidx.compose.material3.Text("Adjuntos de ${scope.actorId}/${scope.claimId}")
                })
            }
        }
        compose.onNodeWithText(claim.type).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.claim_status_title)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.claim_status_report_details)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.ui_show_details)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.claim_status_report_details)).performScrollTo().assertIsDisplayed()
        for (value in listOf(checkNotNull(claim.policyId), checkNotNull(claim.place), claim.description, claim.id)) {
            compose.onNodeWithText(value).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText("Adjuntos de actor-a/${claim.id}").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.ui_hide_details)).performScrollTo().performClick()
        compose.onNodeWithText(claim.description).assertDoesNotExist()
        compose.runOnIdle { state.value = ClaimStatusState() }
        compose.onNodeWithText(text(R.string.claim_status_title)).assertIsDisplayed()
    }

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
        compose.onNodeWithText(text(R.string.claim_status_refresh)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.claim_status_empty)).assertDoesNotExist()
        for (error in ClaimStatusError.entries) {
            compose.runOnIdle { state.value = ClaimStatusState(error = error) }
            compose.onNodeWithText(text(claimStatusErrorText(error))).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(text(R.string.claim_status_empty)).assertDoesNotExist()
            compose.onNodeWithTag("page-refresh").performCustomAccessibilityActionWithLabel("Actualizar")
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
        compose.onNodeWithTag("claim-${statusClaim.id}").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
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
                compose.onNodeWithText(app.getString(R.string.evidence_claim_reference, operationalReference(statusClaim.id))).assertDoesNotExist()
                compose.onNodeWithTag("page-refresh").assertExists()
            }
        }
        compose.runOnIdle { state.value = state.value.copy(detail = null, error = ClaimStatusError.NETWORK) }
        compose.onNodeWithText(text(R.string.claim_status_closed)).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.claim_status_updated, displayDate())).assertDoesNotExist()
    }

    @Test fun networkFailureHasAVisibleRetryWithOrWithoutPreviouslyLoadedDetail() {
        val state = mutableStateOf(ClaimStatusState(error = ClaimStatusError.NETWORK))
        var retries = 0
        compose.setContent {
            SolventaTheme(false) { ClaimStatusScreen(state.value, {}, { retries++ }, {}) }
        }
        for (detail in listOf(null, ClaimStatus(statusClaim.id, ClaimProgress.REPORTED,
            "Detalle desde el servicio.", statusDate))) {
            compose.runOnIdle { state.value = ClaimStatusState(detail = detail, error = ClaimStatusError.NETWORK) }
            compose.onNodeWithText(text(R.string.claim_status_network)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(text(R.string.claim_status_retry)).performScrollTo()
                .assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        }
        compose.runOnIdle { assertEquals(2, retries); state.value = state.value.copy(busy = true) }
        compose.onNodeWithText(text(R.string.claim_status_retry)).assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(busy = false, error = null) }
        compose.onNodeWithText(text(R.string.claim_status_retry)).assertDoesNotExist()
        compose.onNodeWithTag("page-refresh").performCustomAccessibilityActionWithLabel("Actualizar")
        compose.runOnIdle { assertEquals(3, retries) }
    }

    private fun displayDate() = app.getString(R.string.operational_date_device_zone,
        claimStatusDate(statusDate), java.time.ZoneId.systemDefault().id)
}
