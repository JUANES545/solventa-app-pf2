package co.solventa.mobile.feature.claim

import android.app.Application
import android.os.Looper
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class PolicyPreselectionTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun await(model: ClaimViewModel) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (model.state.value.busy && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertFalse(model.state.value.busy)
    }

    @Test fun navigationNeverCreatesOrSendsAnOperationAndKeepsDraftAndCorrectionIdentity() {
        MockWebServer().use { server ->
            val report = ClaimStore(app.noBackupFilesDir).use { store ->
                store.enqueue("actor-a", draft.payload(listOf(policy))).also { store.fail(it, ClaimError.DATE) }
            }
            val model = ClaimViewModel(app, statusApi(server))
            assertTrue(model.show("actor-a", policy)); await(model)
            assertEquals(ClaimDraft(policyId = policy.id), model.state.value.draft)
            assertFalse(model.state.value.autoRetry)
            model.correct(model.state.value.reports.single())
            val correction = model.state.value.draft
            model.close()
            assertTrue(model.show("actor-a", policy)); await(model)
            assertEquals(correction, model.state.value.draft)
            assertEquals(report.operation, model.state.value.correction)
            model.close()
            val other = policy.copy(id = UUID.randomUUID().toString())
            assertFalse(model.show("actor-a", other))
            assertFalse(model.state.value.open)
            assertEquals(correction, model.state.value.draft)
            assertEquals(report.operation, model.state.value.correction)
            model.resumeDraft("actor-a")
            assertEquals(correction, model.state.value.draft)
            assertFalse(model.state.value.autoRetry)
            ClaimStore(app.noBackupFilesDir).use { store ->
                assertEquals(listOf(report.operation), store.list("actor-a").map { it.operation })
            }
            assertEquals(0, server.requestCount)
            model.bindActor("actor-b")
            assertEquals(ClaimDraft(), model.state.value.draft)
            assertTrue(model.state.value.reports.isEmpty())
            assertNull(model.state.value.correction)
        }
    }

    @Test fun filledDraftWithoutPolicyIsNotSilentlyAssignedAndAnotherActorStartsClean() {
        MockWebServer().use { server ->
            val model = ClaimViewModel(app, statusApi(server))
            model.bindActor("actor-a")
            val previous = ClaimDraft(description = "Datos que pertenecen al reporte anterior")
            model.edit(previous)
            assertFalse(model.show("actor-a", policy))
            assertEquals(previous, model.state.value.draft)
            assertTrue(model.show("actor-b", policy)); await(model)
            assertEquals(ClaimDraft(policyId = policy.id), model.state.value.draft)
            assertEquals(0, server.requestCount)
        }
    }
}
