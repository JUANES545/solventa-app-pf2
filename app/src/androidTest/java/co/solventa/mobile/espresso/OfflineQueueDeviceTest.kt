package co.solventa.mobile.espresso

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import co.solventa.mobile.BuildConfig
import co.solventa.mobile.R
import co.solventa.mobile.core.SyncResult
import co.solventa.mobile.core.designsystem.SolventaTheme
import co.solventa.mobile.feature.evidence.*
import co.solventa.mobile.feature.claim.claimStatusDate
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@ClaimDeviceE2e
@RunWith(AndroidJUnit4::class)
class OfflineQueueDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun conflictHistoryAndLostAckSurviveRecreationWithoutOverwritingOrDuplicating() {
        check(BuildConfig.APPLICATION_ID == "co.solventa.mobile.pf2.claimtest")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.noBackupFilesDir, "queue-test-${UUID.randomUUID()}").apply { check(mkdir()) }
        val app = object : Application() {
            override fun getNoBackupFilesDir() = directory
            override fun getCacheDir() = directory
        }
        val scope = EvidenceScope(UUID.randomUUID().toString(), UUID.randomUUID().toString())
        val policy = EvidencePolicy(3, mapOf(EvidenceKind.PHOTO to 100L), 0)
        val queuedAt = Instant.parse("2026-11-05T12:00:00Z")
        val source = File(directory, "synthetic.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val original = EvidenceStore(directory) { queuedAt }.use { store ->
            List(2) { store.enqueue(scope, EvidenceKind.PHOTO, source, queuedAt.minusSeconds(it.toLong()),
                null, 0, policy, emptySet()) }
        }
        check(source.delete())
        var remote: EvidenceReceipt? = null
        var uploads = 0
        val lookups = mutableListOf<String>()
        val transport = object : EvidenceTransport {
            override suspend fun confirmed(scope: EvidenceScope, localId: String): EvidenceReceipt? {
                lookups += localId
                if (localId == original[0].id) {
                    throw EvidenceHttpProblem(409, "idempotencia_conflictiva", true, EvidenceError.CONFLICT,
                        serverResult = SyncResult(localId, queuedAt.plusSeconds(10), 409, "idempotencia_conflictiva"))
                }
                return remote
            }
            override suspend fun upload(item: EvidenceItem, file: File, progress: (Long) -> Unit): EvidenceReceipt {
                uploads++
                remote = EvidenceReceipt(item.id, "synthetic-remote", item.sha256,
                    SyncResult(item.id, queuedAt.plusSeconds(20), 200, null))
                throw EvidenceProblem(EvidenceError.NETWORK)
            }
        }
        fun newModel() = EvidenceViewModel(app, MobileEvidenceApi(syntheticAuth(scope.actorId), "http://127.0.0.1:1"))
        val models = mutableStateOf(newModel())
        fun clearModel() = ViewModelStore().apply { put("evidence", models.value); clear() }
        try {
            compose.runOnUiThread { models.value.open(scope, policy, emptySet()) }
            compose.setContent {
                val model = models.value
                SolventaTheme(false) { EvidenceScreen(model, model::close, { model.retry(transport) { true } }) }
            }
            compose.waitUntil(15_000) { !models.value.state.value.busy }
            compose.onNodeWithText(context.getString(R.string.evidence_retry)).performScrollTo().performClick()
            compose.waitUntil(15_000) { !models.value.state.value.busy && models.value.state.value.error == EvidenceError.NETWORK }
            val saved = models.value.state.value.items
            assertEquals(original.map { it.id }, saved.map { it.id })
            assertNotNull(saved[0].lastResultAt)
            assertNotNull(saved[1].lastResultAt)
            compose.runOnIdle {
                clearModel()
                models.value = newModel().also { it.open(scope, policy, emptySet()) }
            }
            compose.waitUntil(15_000) { !models.value.state.value.busy }
            assertEquals(saved, models.value.state.value.items)
            for (item in saved) {
                val localDate = context.getString(R.string.operational_date_device_zone,
                    claimStatusDate(item.capturedAt), ZoneId.systemDefault().id)
                compose.onNodeWithText(context.getString(R.string.operational_captured_date, localDate))
                    .performScrollTo().assertIsDisplayed()
            }
            compose.onAllNodesWithText(context.getString(R.string.ui_show_details)).assertCountEquals(2)
            repeat(2) {
                compose.onAllNodesWithText(context.getString(R.string.ui_show_details))[0]
                    .performScrollTo().performClick()
            }
            compose.onAllNodesWithText("${context.getString(R.string.operational_queued_instant)}: $queuedAt")
                .assertCountEquals(2)
            for ((observedAt, items) in saved.groupBy { it.lastResultAt }) {
                compose.onAllNodesWithText("${context.getString(R.string.operational_observed_instant)}: $observedAt")
                    .assertCountEquals(items.size)
            }
            compose.onNodeWithText("${context.getString(R.string.operational_server_instant)}: ${queuedAt.plusSeconds(10)}")
                .performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.sync_conflict), substring = true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.sync_error_conflict)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.evidence_error_network)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.evidence_remove)).assertDoesNotExist()
            compose.onNodeWithText(context.getString(R.string.evidence_retry)).performScrollTo().performClick()
            compose.waitUntil(15_000) {
                !models.value.state.value.busy && models.value.state.value.items.last().status == EvidenceStatus.CONFIRMED
            }
            compose.onNodeWithText(context.getString(R.string.evidence_confirmed), substring = true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.evidence_error_network)).assertDoesNotExist()
            compose.onNodeWithText(context.getString(R.string.evidence_retry)).assertDoesNotExist()
            assertEquals(1, uploads)
            assertEquals(listOf(original[0].id, original[1].id, original[1].id), lookups)
            EvidenceStore(directory).use { store ->
                assertEquals(saved[0], store.list(scope)[0])
                assertEquals("idempotencia_conflictiva", store.list(scope)[0].errorCode)
                assertEquals("synthetic-remote", store.list(scope)[1].remoteId)
                assertEquals(queuedAt.plusSeconds(20), store.list(scope)[1].serverResult?.at)
                assertEquals(queuedAt, store.list(scope)[1].queuedAt)
                assertNull(store.list(scope)[1].lastError)
                assertTrue(store.list(scope.copy(actorId = "other")).isEmpty())
                for (item in store.list(scope)) assertArrayEquals(byteArrayOf(1, 2, 3), store.file(item).readBytes())
            }
            compose.runOnIdle {
                clearModel()
                models.value = newModel().also { it.open(scope, policy, emptySet()) }
            }
            compose.waitUntil(15_000) { !models.value.state.value.busy }
            compose.onNodeWithText(context.getString(R.string.evidence_retry)).assertDoesNotExist()
            assertEquals(EvidenceStatus.CONFLICT, models.value.state.value.items.first().status)
        } finally {
            compose.runOnUiThread { clearModel() }
            check(directory.deleteRecursively())
        }
    }
}
