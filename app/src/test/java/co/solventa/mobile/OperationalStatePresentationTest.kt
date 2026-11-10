package co.solventa.mobile

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import co.solventa.mobile.core.designsystem.SolventaTheme
import co.solventa.mobile.core.ui.SolventaPage
import co.solventa.mobile.feature.assistance.*
import co.solventa.mobile.feature.claim.*
import co.solventa.mobile.feature.evidence.*
import co.solventa.mobile.feature.notification.*
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w320dp-h640dp")
class OperationalStatePresentationTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun loadingAndFailedPoliciesDoNotLookLikeAnEmptyResult() {
        val state = mutableStateOf(ClaimState(busy = true, loadingPolicies = true, cachedPolicies = false))
        compose.setContent {
            SolventaTheme(false) { ClaimScreen(state.value, {}, {}, {}, {}, {}, {}) }
        }
        compose.onNodeWithContentDescription(app.getString(R.string.operational_loading_policies))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.claim_processing)).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.claim_no_policies)).assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(busy = false, loadingPolicies = false, error = ClaimError.NETWORK) }
        compose.onNodeWithContentDescription(app.getString(R.string.operational_loading_policies)).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.claim_error_network)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.claim_no_policies)).assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(error = null) }
        compose.onNodeWithText(app.getString(R.string.claim_no_policies)).performScrollTo().assertIsDisplayed()
    }

    @Test fun assistanceFailureRetainsRetryWithoutClaimingThereAreNoProviders() {
        val state = mutableStateOf(AssistanceState(open = true, step = AssistanceStep.PROVIDERS,
            error = AssistanceError.NETWORK))
        var retries = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                SolventaTheme(true) {
                    AssistanceContent(state.value, {}, {}, {}, {}, { _, _ -> }, {}, {}, { retries++ }, {})
                }
            }
        }
        compose.onNodeWithText(app.getString(R.string.assistance_error_network)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("assistance-empty").assertDoesNotExist()
        compose.onNodeWithTag("assistance-retry-providers").performScrollTo()
            .assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(1, retries); state.value = state.value.copy(error = null) }
        compose.onNodeWithTag("assistance-empty").performScrollTo().assertIsDisplayed()
    }

    @Test fun refreshingNotificationsKeepsExistingContentAndReportsLoading() {
        val notification = MobileNotification(
            "synthetic-notification", NotificationKind.CLAIMS, "Actualizamos tu siniestro",
            "Consulta los detalles en Solventa.",
            NotificationResource(NotificationResourceKind.CLAIM, "synthetic-claim"),
            Instant.parse("2026-11-09T15:00:00Z"),
        )
        compose.setContent {
            SolventaTheme(false) {
                NotificationScreen(
                    NotificationUiState(busy = true, loadingNotifications = true, items = listOf(NotificationItem(notification, false))),
                    {}, {}, {}, {}, {}, {},
                )
            }
        }
        compose.onNodeWithText(app.getString(R.string.operational_refreshing)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(app.getString(R.string.notification_loading)).assertDoesNotExist()
        compose.onNodeWithText(notification.title).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.notification_refresh)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(app.getString(R.string.notification_empty)).assertDoesNotExist()
    }

    @Test fun locationLabelTogglesOneControlAndBusyStateDisablesIt() {
        val state = mutableStateOf(EvidenceState())
        var changes = 0
        compose.setContent {
            SolventaTheme(false) {
                SolventaPage {
                    EvidenceLocationChoice(state.value, false) {
                        changes++
                        state.value = state.value.copy(locationEnabled = it)
                    }
                }
            }
        }
        val control = compose.onNodeWithContentDescription(app.getString(R.string.evidence_location))
        control.assertIsOff().assertHeightIsAtLeast(48.dp).performClick()
        control.assertIsOn()
        compose.runOnIdle { state.value = state.value.copy(busy = true); assertEquals(1, changes) }
        control.assertIsNotEnabled()
    }

    @Test fun providerRowsRetainTheirTaggedSelectionAction() {
        val provider = AssistanceProvider("11111111-1111-4111-8111-111111111111",
            "Asistencia de vehículo", "asistencia_vial", 2.4)
        val state = mutableStateOf(AssistanceState(open = true, step = AssistanceStep.PROVIDERS,
            providers = listOf(provider)))
        var selected: AssistanceProvider? = null
        compose.setContent {
            SolventaTheme(true) {
                AssistanceContent(state.value, {}, {}, {}, {}, { _, _ -> }, {},
                    { selected = it }, {}, {})
            }
        }
        val row = compose.onNodeWithTag("assistance-provider-${provider.id}")
        row.performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(provider, selected); state.value = state.value.copy(busy = true) }
        row.assertIsNotEnabled()
    }

    @Test fun submittingAReportUsesOperationFeedbackInsteadOfPolicySkeletons() {
        compose.setContent {
            SolventaTheme(false) { ClaimScreen(ClaimState(busy = true), {}, {}, {}, {}, {}, {}) }
        }
        compose.onNodeWithText(app.getString(R.string.claim_processing)).assertIsDisplayed()
        compose.onNodeWithContentDescription(app.getString(R.string.operational_loading_policies)).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.claim_submit)).performScrollTo().assertIsNotEnabled()
    }

    @Test fun notificationQueryEmptyFailureAndPermissionSaveHaveDifferentFeedback() {
        val state = mutableStateOf(NotificationUiState(busy = true, loadingNotifications = true))
        compose.setContent {
            SolventaTheme(true) { NotificationScreen(state.value, {}, {}, {}, {}, {}, {}) }
        }
        val loading = app.getString(R.string.notification_loading)
        val empty = app.getString(R.string.notification_empty)
        compose.onNodeWithContentDescription(loading).assertIsDisplayed()
        compose.onNodeWithText(empty).assertDoesNotExist()
        compose.runOnIdle {
            state.value = state.value.copy(busy = false, loadingNotifications = false, error = NotificationError.NETWORK)
        }
        compose.onNodeWithContentDescription(loading).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.notification_error_network)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(empty).assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(error = null) }
        compose.onNodeWithText(empty).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(busy = true) }
        compose.onNodeWithContentDescription(loading).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.operational_saving_notifications)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(empty).assertDoesNotExist()
    }

    @Test fun providerQueryUsesSkeletonButGpsAndSubmissionNeverDo() {
        val state = mutableStateOf(AssistanceState(open = true, busy = true))
        compose.setContent {
            SolventaTheme(false) {
                AssistanceContent(state.value, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {})
            }
        }
        val loading = app.getString(R.string.operational_loading_providers)
        compose.onNodeWithText(app.getString(R.string.operational_location_loading)).assertIsDisplayed()
        compose.onNodeWithContentDescription(loading).assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(step = AssistanceStep.PROVIDERS) }
        compose.onNodeWithContentDescription(loading).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("assistance-empty").assertDoesNotExist()
        compose.runOnIdle {
            state.value = state.value.copy(busy = false, error = AssistanceError.NO_PROVIDER)
        }
        compose.onNodeWithContentDescription(loading).assertDoesNotExist()
        compose.onNodeWithTag("assistance-empty").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            state.value = state.value.copy(busy = true, step = AssistanceStep.CONFIRMATION, error = null,
                claimId = UUID.randomUUID().toString(),
                selected = AssistanceProvider(UUID.randomUUID().toString(), "Taller", "asistencia_vial", 2.4),
                location = AssistanceLocation(4.6, -74.0, AssistanceLocationOrigin.MANUAL, Instant.EPOCH))
        }
        compose.onNodeWithContentDescription(loading).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.operational_assistance_submitting))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("assistance-submit").performScrollTo().assertIsNotEnabled()
    }

    @Test fun evidenceQueryRefreshAndProcessingKeepTheirOwnStates() {
        val claim = EvidenceClaim(UUID.randomUUID().toString(), "Daño en el vehículo",
            "2026-11-09T15:00:00Z", "Daño en la puerta.")
        val state = mutableStateOf(EvidenceState(open = true, busy = true, loadingClaims = true))
        var selected: EvidenceClaim? = null
        compose.setContent {
            SolventaTheme(false) {
                EvidenceContent(state.value, {}, {}, { selected = it }, {}, {}, {}, {}, {}, {}, {}, {},
                    camera = {}, locationChoice = {})
            }
        }
        val loading = app.getString(R.string.operational_loading_claims)
        compose.onNodeWithContentDescription(loading).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.evidence_no_claims)).assertDoesNotExist()
        compose.runOnIdle {
            state.value = state.value.copy(busy = false, loadingClaims = false, error = EvidenceError.NETWORK)
        }
        compose.onNodeWithContentDescription(loading).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.evidence_query_network)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.evidence_no_claims)).assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(errorHttpStatus = 503) }
        compose.onNodeWithText(app.getString(R.string.evidence_query_service)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.evidence_no_claims)).assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(error = null, errorHttpStatus = null) }
        compose.onNodeWithText(app.getString(R.string.evidence_no_claims)).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(claims = listOf(claim), error = EvidenceError.NETWORK, errorHttpStatus = 503) }
        compose.onNodeWithText(app.getString(R.string.evidence_query_service)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.evidence_error_network)).assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(error = null, errorHttpStatus = null) }
        compose.onNodeWithTag("claim-${claim.id}").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(claim, selected)
            state.value = state.value.copy(busy = true, loadingClaims = true)
        }
        compose.onNodeWithTag("claim-${claim.id}").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription(loading).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.operational_refreshing)).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(loadingClaims = false) }
        compose.onNodeWithText(app.getString(R.string.evidence_loading)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(loading).assertDoesNotExist()
    }

    @Test fun refreshKeepsStatusVisibleWithItsOriginalDateAndMetadataCanBeExpandedLocally() {
        val id = UUID.randomUUID().toString()
        val date = Instant.parse("2026-11-09T15:00:00Z")
        val state = mutableStateOf(ClaimStatusState(selectedId = id,
            detail = ClaimStatus(id, ClaimProgress.REVIEWING, "Revisión del reporte.", date), busy = true))
        compose.setContent {
            SolventaTheme(false) { ClaimStatusScreen(state.value, {}, {}, {}) }
        }
        compose.onNodeWithText(app.getString(R.string.claim_status_reviewing)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(app.getString(R.string.claim_status_loading)).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.operational_refreshing)).performScrollTo().assertIsDisplayed()
        val displayDate = app.getString(R.string.operational_date_device_zone, claimStatusDate(date), ZoneId.systemDefault().id)
        compose.onNodeWithText(app.getString(R.string.claim_status_updated, displayDate)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(date.toString(), substring = true).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.ui_show_details)).performScrollTo().performClick()
        compose.onNodeWithText("${app.getString(R.string.operational_updated_instant)}: $date")
            .performScrollTo().assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(busy = false, error = ClaimStatusError.NETWORK) }
        compose.onNodeWithText(app.getString(R.string.operational_refresh_failed)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.claim_status_reviewing)).performScrollTo().assertIsDisplayed()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun multilineActionAtLargeTextUsesCenteredUntruncatedTextLayout() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                SolventaTheme(false) {
                    Box(Modifier.requiredSize(320.dp, 480.dp)) {
                        ClaimScreen(ClaimState(editing = false), {}, {}, {}, {}, {}, {})
                    }
                }
            }
        }
        val label = app.getString(R.string.claim_retry)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(label, useUnmergedTree = true)
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        compose.runOnIdle {
            val layout = layouts.single()
            val metrics = "label=$label, lines=${layout.lineCount}, size=${layout.size}, " +
                "paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}, " +
                "density=${layout.layoutInput.density}, constraints=${layout.layoutInput.constraints}"
            assertTrue("Exercise actual wrapping with native font metrics: $metrics", layout.lineCount > 1)
            assertEquals(TextAlign.Center, layout.layoutInput.style.textAlign)
            assertEquals(label, layout.layoutInput.text.text)
            assertTrue("The paragraph must fit vertically: $metrics",
                layout.multiParagraph.height <= layout.size.height + 1f)
            assertFalse("No lines may be omitted: $metrics", layout.multiParagraph.didExceedMaxLines)
            assertEquals("All characters must be laid out: $metrics",
                label.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = false))
            repeat(layout.lineCount) { line ->
                assertFalse("No ellipsis on line $line: $metrics", layout.isLineEllipsized(line))
                assertTrue("The actual line $line must fit: $metrics",
                    layout.getLineRight(line) - layout.getLineLeft(line) <= layout.size.width + 1f)
            }
            val glyphs = label.indices.map(layout::getBoundingBox)
            assertTrue("The complete glyph span must fit: $metrics",
                glyphs.maxOf { it.right } - glyphs.minOf { it.left } <= layout.size.width + 1f)
            assertTrue("The complete glyph height must fit: $metrics",
                glyphs.minOf { it.top } >= -1f && glyphs.maxOf { it.bottom } <= layout.size.height + 1f)
        }
    }

    @Test fun identicalRowsHaveDifferentVisibleReferencesAndFullLocalDetails() {
        val first = EvidenceClaim("11111111-1111-4111-8111-111111111111",
            "Daño en el vehículo", "2026-11-09T15:00:00Z", "Daño en la puerta.")
        val second = first.copy(id = "11111111-2111-4111-8111-111111111111")
        val peers = listOf(first.id, second.id)
        compose.setContent {
            SolventaTheme(false) {
                ClaimStatusScreen(ClaimStatusState(claims = listOf(first, second)), {}, {}, {})
            }
        }
        for (reference in peers) {
            compose.onNodeWithText(app.getString(R.string.evidence_claim_reference,
                operationalReference(reference, peers)), substring = true).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText(app.getString(R.string.ui_reference, second.id)).assertDoesNotExist()
        compose.onAllNodesWithText(app.getString(R.string.ui_show_details)).onLast().performScrollTo().performClick()
        compose.onNodeWithText(app.getString(R.string.ui_reference, second.id)).performScrollTo().assertIsDisplayed()
    }

    @Test fun assistanceKeepsAuthorizedContextAndFullReceiptOnlyInExpandedDetails() {
        val claim = "11111111-1111-4111-8111-111111111111"
        val receipt = "22222222-2222-4222-8222-222222222222"
        val date = Instant.parse("2026-11-09T15:00:07Z")
        val provider = AssistanceProvider("33333333-3333-4333-8333-333333333333", "Taller", "asistencia_vial", 2.4)
        val state = mutableStateOf(AssistanceState(open = true, step = AssistanceStep.CONFIRMATION,
            claimId = claim, selected = provider,
            location = AssistanceLocation(4.6, -74.0, AssistanceLocationOrigin.MANUAL, date)))
        compose.setContent {
            SolventaTheme(false) {
                AssistanceContent(state.value, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {})
            }
        }
        compose.onNodeWithText(app.getString(R.string.assistance_claim, operationalReference(claim)))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(claim, substring = true).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.ui_show_details)).performScrollTo().performClick()
        compose.onNodeWithText(app.getString(R.string.ui_reference, claim)).performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            state.value = state.value.copy(step = AssistanceStep.SUCCESS,
                receipt = AssistanceReceipt(receipt, "registrada", provider.id, provider.serviceType, date))
        }
        compose.onNodeWithText(app.getString(R.string.assistance_identifier, operationalReference(receipt)))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(receipt, substring = true).assertDoesNotExist()
        compose.onNodeWithText(date.toString(), substring = true).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.ui_show_details)).performScrollTo().performClick()
        compose.onNodeWithText(app.getString(R.string.ui_reference, receipt)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("${app.getString(R.string.operational_server_instant)}: $date")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("${app.getString(R.string.operational_display_zone)}: ${ZoneId.systemDefault().id}")
            .performScrollTo().assertIsDisplayed()
    }
}
