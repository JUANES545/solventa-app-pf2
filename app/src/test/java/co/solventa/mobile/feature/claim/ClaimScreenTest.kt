package co.solventa.mobile.feature.claim

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import co.solventa.mobile.R
import co.solventa.mobile.core.designsystem.SolventaTheme
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ClaimScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun formEditsAllFieldsAndBusyDisablesDuplicateSubmission() {
        val state = mutableStateOf(ClaimState(policies = listOf(policy), cachedPolicies = false,
            draft = ClaimDraft(date = draft.date, time = draft.time)))
        var sends = 0
        compose.setContent {
            SolventaTheme(false) { ClaimScreen(state.value, { state.value = state.value.copy(draft = it) },
                { sends++; state.value = state.value.copy(busy = true) }, {}, {}, {}, {}) }
        }
        compose.onNode(hasText("automotive", substring = true)).performScrollTo().performClick()
        for ((label, value) in listOf("Tipo de evento" to draft.type, "Ubicación del evento" to draft.place,
            "Descripción" to draft.description)) {
            compose.onNodeWithText(label).performScrollTo().performTextReplacement(value)
        }

        compose.runOnIdle { assertEquals(draft, state.value.draft) }
        compose.onNodeWithText("Enviar reporte").performScrollTo().performClick()
        compose.onNodeWithText("Enviar reporte").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, sends) }
    }

    @Test fun descriptionSampleIsExclusiveToMockAndNeverOverwritesExistingText() {
        val state = mutableStateOf(ClaimState(policies = listOf(policy), cachedPolicies = false))
        compose.setContent {
            SolventaTheme(false) { ClaimScreen(state.value, { state.value = state.value.copy(draft = it) },
                {}, {}, {}, {}, {}) }
        }
        compose.onNodeWithText("Descripción").performScrollTo().performClick()
        compose.runOnIdle {
            val expected = if (co.solventa.mobile.BuildConfig.BUILD_TYPE == "mock")
                org.robolectric.RuntimeEnvironment.getApplication().getString(co.solventa.mobile.R.string.claim_sample_description)
            else ""
            assertEquals(expected, state.value.draft.description)
        }
        compose.onNodeWithText("Descripción").performTextReplacement("Mi descripción escrita")
        compose.onNodeWithText("Tipo de evento").performScrollTo().performClick()
        compose.onNodeWithText("Descripción").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("Mi descripción escrita", state.value.draft.description) }
    }

    @Test fun locationControlsFollowBusyStateWithAStableLiveStateReader() {
        val state = mutableStateOf(ClaimState(actor = "a", busy = true, policies = listOf(policy)))
        val live = java.util.concurrent.atomic.AtomicReference(state.value)
        val readState = { live.get() }
        compose.setContent {
            SolventaTheme(false) { ClaimScreen(state.value, {}, {}, {}, {}, {}, {}, readState = readState) }
        }
        compose.onNodeWithTag("claim-current-location").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle {
            state.value = state.value.copy(busy = false)
            live.set(state.value)
        }
        compose.onNodeWithTag("claim-current-location").performScrollTo().assertIsEnabled()
            .assertContentDescriptionEquals("Usar ubicación actual").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Usar ubicación actual").assertDoesNotExist()
        compose.onNodeWithText("Ubicación del evento").assertIsEnabled()
    }

    @Test fun errorsCorrectionCachedPoliciesAndNavigationRemainActionable() {
        val rejected = ClaimReport(UUID.randomUUID().toString(), "actor-a", payload(), error = ClaimError.POLICY)
        val state = mutableStateOf(ClaimState(reports = listOf(rejected), policies = listOf(policy)))
        var retries = 0
        var backs = 0
        var corrections = 0
        var newReports = 0
        compose.setContent {
            SolventaTheme(false) { ClaimScreen(state.value, {}, {}, { retries++ }, { newReports++ }, { backs++ }, {}, { corrections++ }) }
        }
        compose.onNodeWithText(text(R.string.claim_cached_policies)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.claim_no_policies)).assertDoesNotExist()
        for (error in ClaimError.entries) {
            compose.runOnIdle { state.value = state.value.copy(error = error, reports = emptyList()) }
            compose.onNodeWithText(text(claimErrorText(error))).performScrollTo().assertIsDisplayed()
        }
        compose.runOnIdle { state.value = state.value.copy(error = ClaimError.POLICY, editing = false, reports = listOf(rejected)) }
        compose.onAllNodesWithText(text(claimErrorText(ClaimError.POLICY))).assertCountEquals(1)
        compose.onNodeWithText(text(R.string.evidence_rejected)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.claim_correct)).performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText(text(R.string.claim_send_pending)).assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(error = null, reports = listOf(rejected.copy(error = ClaimError.NETWORK))) }
        compose.onNodeWithText(text(R.string.claim_send_pending)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.claim_new)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.claim_back)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(1, 1, 1, 1), listOf(retries, backs, corrections, newReports)) }
        compose.onNodeWithText(text(R.string.claim_failed)).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(reports = listOf(rejected.copy(error = ClaimError.CONFLICT))) }
        compose.onNodeWithText(text(R.string.sync_conflict)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.sync_error_conflict)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.claim_correct)).assertDoesNotExist()
    }

    @Test fun editingShowsOnlyTheFormAndCompactQueueSummaries() {
        val sent = ClaimReport(UUID.randomUUID().toString(), "actor-a", payload().copy(type = "Robo antiguo"),
            receipt = ClaimReceipt(UUID.randomUUID().toString(), "registrado"))
        val superseded = ClaimReport(UUID.randomUUID().toString(), "actor-a", payload().copy(type = "Versión anterior"),
            error = ClaimError.POLICY, supersededBy = UUID.randomUUID().toString())
        val queued = (1..2).map { ClaimReport(UUID.randomUUID().toString(), "actor-a", payload().copy(type = "Choque $it")) }
        val rejected = ClaimReport(UUID.randomUUID().toString(), "actor-a", payload().copy(type = "Granizo"), error = ClaimError.DATE)
        val state = mutableStateOf(ClaimState(policies = listOf(policy), reports = listOf(sent, superseded) + queued + rejected))
        var sends = 0
        var corrected: ClaimReport? = null
        compose.setContent {
            SolventaTheme(false) { ClaimScreen(state.value, {}, {}, { sends++ }, {}, {}, {}, { corrected = it }) }
        }
        compose.onNodeWithText("Robo antiguo", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Versión anterior", substring = true).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.claim_submit)).performScrollTo().assertIsEnabled()
        compose.onNodeWithText(quantity(R.plurals.claim_pending_count, 2)).performScrollTo().assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Heading))
        compose.onNodeWithText("Choque 1 · Choque 2").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.claim_pending)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.claim_send_pending)).assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(1, sends) }
        compose.onNodeWithText(quantity(R.plurals.claim_review_count, 1)).assertIsDisplayed()
        compose.onNodeWithText("Granizo").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.claim_review_show)).assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("Granizo").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.claim_correct)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(rejected, corrected) }
        compose.runOnIdle { state.value = state.value.copy(busy = true) }
        compose.onNodeWithText(text(R.string.claim_sending)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.claim_send_pending)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.claim_submit)).assertIsNotEnabled()
    }

    @Test fun pendingNeverExposesReceiptAndConfirmedLinksToEvidence() {
        val older = ClaimReport(UUID.randomUUID().toString(), "actor-a", payload().copy(type = "Reporte anterior"),
            receipt = ClaimReceipt(UUID.randomUUID().toString(), "registrado"))
        val report = ClaimReport(UUID.randomUUID().toString(), "actor-a", payload())
        val state = mutableStateOf(ClaimState(editing = false, reports = listOf(older, report)))
        var selected: ClaimReport? = null
        var queried: ClaimReport? = null
        compose.setContent {
            SolventaTheme(false) { ClaimScreen(state.value, {}, {}, {}, {}, {}, { selected = it }, status = { queried = it }) }
        }
        compose.onNodeWithText(text(R.string.claim_pending)).performScrollTo().assertIsDisplayed()
        compose.onAllNodes(hasText("Identificador:", substring = true)).assertCountEquals(0)
        compose.onNodeWithText(text(R.string.claim_status_view)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.operational_report_sent)).assertDoesNotExist()
        val receipt = ClaimReceipt(UUID.randomUUID().toString(), "registrado")
        compose.runOnIdle { state.value = state.value.copy(reports = listOf(older, report.copy(receipt = receipt))) }
        compose.onNodeWithText(text(R.string.operational_report_sent)).performScrollTo().assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Heading))
        compose.onNodeWithText("Reporte anterior").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.claim_pending)).assertDoesNotExist()
        compose.onNodeWithText(receipt.id, substring = true).assertDoesNotExist()
        compose.onNodeWithText("Ver detalles").performScrollTo().performClick()
        compose.onNodeWithText(receipt.id, substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.claim_status_view)).performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(receipt, queried?.receipt) }
        compose.onNodeWithText(text(R.string.claim_add_evidence)).performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(receipt, selected?.receipt) }
        compose.onNodeWithText(text(R.string.claim_new)).performScrollTo().assertIsDisplayed()
    }

    private val app get() = org.robolectric.RuntimeEnvironment.getApplication()
    private fun text(id: Int) = app.getString(id)
    private fun quantity(id: Int, count: Int) = app.resources.getQuantityString(id, count, count)
}
