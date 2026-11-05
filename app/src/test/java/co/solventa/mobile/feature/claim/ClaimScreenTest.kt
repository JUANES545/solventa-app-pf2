package co.solventa.mobile.feature.claim

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
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
        val state = mutableStateOf(ClaimState(policies = listOf(policy), cachedPolicies = false))
        var sends = 0
        compose.setContent {
            SolventaTheme(false) { ClaimScreen(state.value, { state.value = state.value.copy(draft = it) },
                { sends++; state.value = state.value.copy(busy = true) }, {}, {}, {}, {}) }
        }
        compose.onNode(hasText("automotive", substring = true)).performScrollTo().performClick()
        for ((label, value) in listOf("Tipo de evento" to draft.type, "Fecha del evento (AAAA-MM-DD)" to draft.date,
            "Hora local (HH:mm, formato 24 horas)" to draft.time, "Ubicación del evento" to draft.place,
            "Descripción (10 a 5000 caracteres)" to draft.description)) {
            compose.onNodeWithText(label).performScrollTo().performTextInput(value)
        }
        compose.runOnIdle { assertEquals(draft, state.value.draft) }
        compose.onNodeWithText("Enviar reporte").performScrollTo().performClick()
        compose.onNodeWithText("Enviar reporte").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, sends) }
    }

    @Test fun errorsCorrectionCachedPoliciesAndNavigationRemainActionable() {
        val rejected = ClaimReport(UUID.randomUUID().toString(), "actor-a", payload(), error = ClaimError.POLICY)
        val state = mutableStateOf(ClaimState(reports = listOf(rejected)))
        var retries = 0
        var backs = 0
        var corrections = 0
        var newReports = 0
        compose.setContent {
            SolventaTheme(false) { ClaimScreen(state.value, {}, {}, { retries++ }, { newReports++ }, { backs++ }, {}, { corrections++ }) }
        }
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        compose.onNodeWithText(app.getString(co.solventa.mobile.R.string.claim_cached_policies)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(app.getString(co.solventa.mobile.R.string.claim_no_policies)).performScrollTo().assertIsDisplayed()
        for (error in ClaimError.entries) {
            compose.runOnIdle { state.value = state.value.copy(error = error, reports = emptyList()) }
            compose.onNodeWithText(app.getString(claimErrorText(error))).performScrollTo().assertIsDisplayed()
        }
        compose.runOnIdle { state.value = state.value.copy(error = null, editing = false, reports = listOf(rejected)) }
        compose.onNodeWithText(app.getString(co.solventa.mobile.R.string.claim_correct)).performScrollTo().performClick()
        compose.onNodeWithText(app.getString(co.solventa.mobile.R.string.claim_retry)).performScrollTo().performClick()
        compose.onNodeWithText(app.getString(co.solventa.mobile.R.string.claim_new)).performScrollTo().performClick()
        compose.onNodeWithText(app.getString(co.solventa.mobile.R.string.claim_back)).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(1, 1, 1, 1), listOf(retries, backs, corrections, newReports))
            state.value = state.value.copy(reports = listOf(rejected.copy(error = ClaimError.NETWORK)))
        }
        compose.onNodeWithText(app.getString(co.solventa.mobile.R.string.claim_failed)).performScrollTo().assertIsDisplayed()
    }

    @Test fun pendingNeverExposesReceiptAndConfirmedLinksToEvidence() {
        val report = ClaimReport(UUID.randomUUID().toString(), "actor-a", payload())
        val state = mutableStateOf(ClaimState(editing = false, reports = listOf(report)))
        var selected: ClaimReport? = null
        var queried: ClaimReport? = null
        compose.setContent {
            SolventaTheme(false) { ClaimScreen(state.value, {}, {}, {}, {}, {}, { selected = it }, status = { queried = it }) }
        }
        compose.onNodeWithText("Pendiente de envío.", substring = true).performScrollTo().assertIsDisplayed()
        compose.onAllNodes(hasText("Identificador:", substring = true)).assertCountEquals(0)
        compose.onNodeWithText("Consultar estado").assertDoesNotExist()
        val receipt = ClaimReceipt(UUID.randomUUID().toString(), "registrado")
        compose.runOnIdle { state.value = state.value.copy(reports = listOf(report.copy(receipt = receipt))) }
        compose.onNodeWithText(receipt.id, substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Consultar estado").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(receipt, queried?.receipt) }
        compose.onNodeWithText("Adjuntar evidencia a un siniestro").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(receipt, selected?.receipt) }
    }
}
