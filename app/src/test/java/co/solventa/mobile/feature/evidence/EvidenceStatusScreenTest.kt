package co.solventa.mobile.feature.evidence

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelStore
import co.solventa.mobile.R
import co.solventa.mobile.core.designsystem.SolventaTheme
import java.io.File
import java.time.Instant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class EvidenceStatusScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun await(condition: () -> Boolean) = compose.waitUntil(15_000) {
        shadowOf(Looper.getMainLooper()).idle()
        condition()
    }

    @Test fun onlyDefinitivelyRejectedFilesOfferRemoval() {
        val app = RuntimeEnvironment.getApplication()
        val scope = EvidenceScope("actor-a", "claim-a")
        val policy = EvidencePolicy(4, mapOf(EvidenceKind.PHOTO to 100L), 0)
        val source = File.createTempFile("status-", ".jpg", app.cacheDir).apply { writeBytes(byteArrayOf(1)) }
        EvidenceStore(app.noBackupFilesDir).use { store ->
            val items = List(4) {
                store.enqueue(scope, EvidenceKind.PHOTO, source, Instant.now(), null, 0, policy, emptySet())
            }
            store.confirm(items[1], EvidenceReceipt(items[1].id, "remote", items[1].sha256))
            store.reject(items[2], EvidenceError.FORMAT)
            store.reject(items[3], EvidenceError.CONFLICT, "idempotencia_conflictiva")
            assertThrows(IllegalArgumentException::class.java) {
                store.list(scope).single { it.status == EvidenceStatus.CONFIRMED }.copy(lastError = EvidenceError.NETWORK)
            }
        }
        check(source.delete())
        val model = EvidenceViewModel(app, MobileEvidenceApi(evidenceAuth(), "http://127.0.0.1:1"))
        try {
            compose.runOnUiThread { model.open(scope, policy, emptySet()) }
            compose.setContent { SolventaTheme(false) { EvidenceScreen(model, model::close, {}) } }
            await { !model.state.value.busy }
            for (status in listOf(R.string.evidence_pending, R.string.evidence_confirmed, R.string.evidence_rejected, R.string.sync_conflict)) {
                compose.onNodeWithText(app.getString(status), substring = true).performScrollTo().assertIsDisplayed()
            }
            compose.onAllNodesWithText(app.getString(R.string.evidence_remove)).assertCountEquals(1)
            compose.onNodeWithText(app.getString(R.string.sync_error_conflict)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(app.getString(R.string.evidence_remove)).performScrollTo().assertIsDisplayed()
        } finally {
            compose.runOnUiThread { ViewModelStore().apply { put("evidence", model); clear() } }
        }
    }

    @Test fun recoverableErrorRemainsVisibleAfterReopeningTheQueue() {
        val app = RuntimeEnvironment.getApplication()
        val scope = EvidenceScope("actor-a", "claim-a")
        val policy = EvidencePolicy(3, mapOf(EvidenceKind.PHOTO to 100L), 0)
        val source = File.createTempFile("status-", ".jpg", app.cacheDir).apply { writeBytes(byteArrayOf(1)) }
        EvidenceStore(app.noBackupFilesDir).use {
            it.enqueue(scope, EvidenceKind.PHOTO, source, Instant.now(), null, 0, policy, emptySet())
        }
        check(source.delete())
        val model = EvidenceViewModel(app, MobileEvidenceApi(evidenceAuth(), "http://127.0.0.1:1"))
        var recover = false
        var uploads = 0
        val transport = object : EvidenceTransport {
            override suspend fun confirmed(scope: EvidenceScope, localId: String): EvidenceReceipt? = null
            override suspend fun upload(item: EvidenceItem, file: File, progress: (Long) -> Unit): EvidenceReceipt {
                uploads++
                if (!recover) throw EvidenceProblem(EvidenceError.NETWORK)
                return EvidenceReceipt(item.id, "remote", item.sha256)
            }
        }
        try {
            compose.runOnUiThread { model.open(scope, policy, emptySet()) }
            compose.setContent {
                SolventaTheme(false) {
                    EvidenceScreen(model, model::close, { model.retry(transport) { true } })
                }
            }
            await { !model.state.value.busy }
            compose.onNodeWithText(app.getString(R.string.evidence_retry)).performScrollTo().performClick()
            await { !model.state.value.busy && model.state.value.error == EvidenceError.NETWORK }
            compose.runOnIdle { model.open(scope, policy, emptySet()) }
            await { !model.state.value.busy }
            assertNull(model.state.value.error)
            assertEquals(EvidenceStatus.PENDING, model.state.value.items.single().status)
            compose.onNodeWithText(app.getString(R.string.evidence_error_network)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(app.getString(R.string.evidence_failed), substring = true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(app.getString(R.string.evidence_remove)).assertDoesNotExist()
            recover = true
            compose.onNodeWithText(app.getString(R.string.evidence_retry)).performScrollTo().performClick()
            await { !model.state.value.busy && model.state.value.items.single().status == EvidenceStatus.CONFIRMED }
            compose.onNodeWithText(app.getString(R.string.evidence_confirmed), substring = true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(app.getString(R.string.evidence_error_network)).assertDoesNotExist()
            compose.onNodeWithText(app.getString(R.string.evidence_retry)).performScrollTo().assertIsNotEnabled()
            assertEquals(2, uploads)
        } finally {
            compose.runOnUiThread { ViewModelStore().apply { put("evidence", model); clear() } }
        }
    }
}
