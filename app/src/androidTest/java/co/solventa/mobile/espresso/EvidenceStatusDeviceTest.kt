package co.solventa.mobile.espresso

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import co.solventa.mobile.BuildConfig
import co.solventa.mobile.R
import co.solventa.mobile.core.designsystem.SolventaTheme
import co.solventa.mobile.feature.evidence.*
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@ClaimDeviceE2e
@RunWith(AndroidJUnit4::class)
class EvidenceStatusDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun errorSurvivesModelRecreationAndLostReceiptRecoversWithoutUploadingAgain() {
        check(BuildConfig.APPLICATION_ID == "co.solventa.mobile.pf2.claimtest")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(context.noBackupFilesDir.toPath(), "status-test-").toFile()
        val app = object : Application() {
            override fun getNoBackupFilesDir() = directory
            override fun getCacheDir() = directory
        }
        val scope = EvidenceScope(UUID.randomUUID().toString(), UUID.randomUUID().toString())
        val policy = EvidencePolicy(3, mapOf(EvidenceKind.PHOTO to 100L), 0)
        val source = File(directory, "synthetic.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val item = EvidenceStore(directory).use {
            it.enqueue(scope, EvidenceKind.PHOTO, source, Instant.now(), null, 0, policy, emptySet())
        }
        check(source.delete())
        var remote: EvidenceReceipt? = null
        var uploads = 0
        val transport = object : EvidenceTransport {
            override suspend fun confirmed(scope: EvidenceScope, localId: String) = remote
            override suspend fun upload(item: EvidenceItem, file: File, progress: (Long) -> Unit): EvidenceReceipt {
                uploads++
                remote = EvidenceReceipt(item.id, "synthetic-remote", item.sha256)
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
            compose.onNodeWithText(context.getString(R.string.evidence_pending), substring = true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.evidence_retry)).performScrollTo().performClick()
            compose.waitUntil(15_000) { !models.value.state.value.busy && models.value.state.value.error == EvidenceError.NETWORK }
            compose.runOnIdle {
                clearModel()
                models.value = newModel().also { it.open(scope, policy, emptySet()) }
            }
            compose.waitUntil(15_000) { !models.value.state.value.busy }
            assertNull(models.value.state.value.error)
            assertEquals(item.id, models.value.state.value.items.single().id)
            compose.onNodeWithText(context.getString(R.string.evidence_failed), substring = true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.evidence_error_network)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.evidence_remove)).assertDoesNotExist()
            compose.onNodeWithText(context.getString(R.string.evidence_retry)).performScrollTo().performClick()
            compose.waitUntil(15_000) {
                !models.value.state.value.busy && models.value.state.value.items.single().status == EvidenceStatus.CONFIRMED
            }
            compose.onNodeWithText(context.getString(R.string.evidence_confirmed), substring = true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.evidence_error_network)).assertDoesNotExist()
            compose.onNodeWithText(context.getString(R.string.evidence_retry)).assertDoesNotExist()
            assertEquals(1, uploads)
            EvidenceStore(directory).use {
                val restored = it.list(scope).single()
                assertEquals("synthetic-remote", restored.remoteId)
                assertNull(restored.lastError)
                assertArrayEquals(byteArrayOf(1, 2, 3), it.file(restored).readBytes())
            }
        } finally {
            compose.runOnUiThread { clearModel() }
            check(directory.deleteRecursively())
        }
    }
}
