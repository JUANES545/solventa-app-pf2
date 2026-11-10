package co.solventa.mobile.espresso

import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import co.solventa.mobile.BuildConfig
import co.solventa.mobile.MainActivity
import co.solventa.mobile.R
import co.solventa.mobile.feature.auth.AuthRepository
import co.solventa.mobile.feature.auth.AuthViewModel
import co.solventa.mobile.feature.claim.ClaimDraft
import co.solventa.mobile.feature.claim.ClaimStore
import co.solventa.mobile.feature.claim.ClaimViewModel
import co.solventa.mobile.feature.evidence.EvidenceViewModel
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class ClaimDeviceE2e

@ClaimDeviceE2e
@HiltAndroidTest
class ClaimDeviceE2eTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    @Inject lateinit var auth: AuthRepository
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val phase get() = InstrumentationRegistry.getArguments().getString("phase") ?: "normal"
    private lateinit var fixture: JSONObject
    private lateinit var model: ClaimViewModel
    private lateinit var access: AuthViewModel
    private lateinit var evidence: EvidenceViewModel
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun prepare() {
        check(BuildConfig.APPLICATION_ID == "co.solventa.mobile.pf2.claimtest")
        check(localBackendUrl() == BuildConfig.AUTH_BASE_URL)
        hilt.inject()
        fixture = JSONObject(File(context.filesDir, "claim-fixture.json").readText())
        if (phase != "offline-resume") runBlocking { auth.login(fixture.getString("email"), fixture.getString("password")) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity {
            it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            model = ViewModelProvider(it)[ClaimViewModel::class.java]
            access = ViewModelProvider(it)[AuthViewModel::class.java]
            evidence = ViewModelProvider(it)[EvidenceViewModel::class.java]
        }
        compose.waitUntil(30_000) { access.state.value.user != null && !access.state.value.loading }
        compose.onNodeWithText(context.getString(R.string.claim_title)).performScrollTo().performClick()
        settle()
    }

    @After fun finish() { scenario?.close() }

    private fun settle() {
        compose.waitUntil(45_000) { !model.state.value.busy }
    }

    private fun form(type: String): ClaimDraft {
        val now = ZonedDateTime.now().minusMinutes(1).truncatedTo(ChronoUnit.MINUTES)
        return ClaimDraft(model.state.value.policies.first().id, now.toLocalDate().toString(),
            now.toLocalTime().toString(), type, "Ubicacion sintetica local", "Descripcion sintetica del reporte movil.")
    }

    private fun mode(value: String) {
        val connection = URL("http://127.0.0.1:18721/__control/$value").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            assertEquals(200, connection.responseCode)
            connection.inputStream.close()
        } finally { connection.disconnect() }
    }

    @Test fun formValidationConfirmationEvidenceAndActorIsolation() {
        compose.onNodeWithText(context.getString(R.string.claim_submit)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.claim_error_required)).performScrollTo().assertIsDisplayed()
        val draft = form("Formulario fisico SOL170 ${UUID.randomUUID()}")
        compose.onNode(hasText(draft.policyId, substring = true)).performScrollTo().performClick()
        for ((label, value) in listOf(R.string.claim_type to draft.type, R.string.claim_date to draft.date,
            R.string.claim_time to draft.time, R.string.claim_place to draft.place, R.string.claim_description to draft.description)) {
            compose.onNodeWithText(context.getString(label)).performScrollTo().performTextInput(value)
        }
        compose.onNodeWithText(context.getString(R.string.claim_submit)).performScrollTo().performClick()
        compose.runOnIdle { model.submit() }
        settle()
        val report = model.state.value.reports.single { it.payload.type == draft.type }
        assertNotNull(report.receipt)
        repeat(compose.onAllNodesWithText(context.getString(R.string.ui_show_details)).fetchSemanticsNodes().size) {
            compose.onAllNodesWithText(context.getString(R.string.ui_show_details)).onFirst()
                .performScrollTo().performClick()
        }
        compose.onAllNodesWithText(report.receipt!!.id, substring = true).onFirst()
            .performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText(context.getString(R.string.evidence_entry)).onFirst().performScrollTo().performClick()
        compose.waitUntil(30_000) { evidence.state.value.scope != null && !evidence.state.value.busy }
        assertEquals(report.receipt.id, evidence.state.value.scope!!.claimId)
        assertNotNull(evidence.state.value.policy)
        runBlocking { auth.login(fixture.getString("otherEmail"), fixture.getString("otherPassword")) }
        compose.runOnIdle { access.restore() }
        compose.waitUntil(30_000) { access.state.value.user?.id == fixture.getString("otherActor") && !access.state.value.loading }
        compose.waitForIdle()
        assertTrue(model.state.value.reports.isEmpty())
        assertFalse(evidence.state.value.open)
        ClaimStore(context.noBackupFilesDir).use {
            assertTrue(it.list(fixture.getString("otherActor")).isEmpty())
            assertNotNull(it.list(fixture.getString("actor")).single { saved -> saved.operation == report.operation }.receipt)
        }
    }

    @Test fun lostAcknowledgementSurvivesProcessRestart() {
        val marker = File(context.filesDir, "claim-ack-operation.txt")
        if (phase == "interrupt") {
            mode("drop")
            val draft = form("ACK perdido SOL170 ${UUID.randomUUID()}")
            compose.runOnIdle { model.edit(draft); model.submit(); model.submit() }
            settle()
            val report = model.state.value.reports.single { it.payload.type == draft.type }
            assertNull(report.receipt)
            marker.writeText(report.operation)
            compose.onNodeWithText(context.getString(R.string.claim_failed)).performScrollTo().assertIsDisplayed()
        } else {
            assertEquals("resume", phase)
            val report = model.state.value.reports.single { it.operation == marker.readText() }
            assertNotNull(report.receipt)
            assertEquals("registrado", report.receipt!!.status)
        }
    }

    @Test fun offlineQueueSurvivesProcessRestartAndRecovers() {
        val marker = File(context.filesDir, "claim-offline-operation.txt")
        if (phase == "offline") {
            mode("offline")
            val draft = form("Sin conexion SOL170 ${UUID.randomUUID()}")
            compose.runOnIdle { model.newReport(); model.edit(draft); model.submit() }
            settle()
            val report = model.state.value.reports.single { it.payload.type == draft.type }
            assertNull(report.receipt)
            marker.writeText(report.operation)
        } else {
            assertEquals("offline-resume", phase)
            assertTrue(access.state.value.offline)
            val operation = marker.readText()
            assertNull(model.state.value.reports.single { it.operation == operation }.receipt)
            assertTrue(model.state.value.cachedPolicies)
            mode("online")
            compose.runOnIdle { model.retry() }
            settle()
            assertNotNull(model.state.value.reports.single { it.operation == operation }.receipt)
        }
    }
}
