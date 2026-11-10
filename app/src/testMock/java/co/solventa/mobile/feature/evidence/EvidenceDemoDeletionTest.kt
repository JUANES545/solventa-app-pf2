package co.solventa.mobile.feature.evidence

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
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
class EvidenceDemoDeletionTest {
    private val app = RuntimeEnvironment.getApplication()
    private val scope = EvidenceScope("actor-a", UUID.randomUUID().toString())
    private val policy = EvidencePolicy(4, mapOf(EvidenceKind.PHOTO to 100L), 0)

    private fun await(model: EvidenceViewModel) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (model.state.value.busy && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertFalse(model.state.value.busy)
    }

    @Test fun demoConfirmationDeletionRemovesBothLocalFileAndDurableMetadataOnlyAfter204() {
        MockWebServer().use { server ->
            val remote = UUID.randomUUID().toString()
            val source = File(app.cacheDir, "${UUID.randomUUID()}.jpg").apply { writeBytes(byteArrayOf(1, 2)) }
            val confirmed = EvidenceStore(app.noBackupFilesDir).use { store ->
                val item = store.enqueue(scope, EvidenceKind.PHOTO, source, Instant.now(), null, 0, policy, emptySet())
                store.confirm(item, EvidenceReceipt(item.id, remote, item.sha256))
                store.list(scope).single()
            }
            check(source.delete())
            val file = EvidenceStore(app.noBackupFilesDir).use { it.file(confirmed) }
            val model = EvidenceViewModel(app, MobileEvidenceApi(evidenceAuth(), server.url("/movil/v1").toString()))
            try {
                model.open(scope, policy, setOf(remote))
                await(model)
                assertTrue(evidenceCanRemove(confirmed))
                server.enqueue(MockResponse().setResponseCode(404).setBody("""{"code":"evidencia_no_encontrada"}"""))
                model.remove(confirmed)
                await(model)
                assertEquals(EvidenceError.UNAVAILABLE, model.state.value.error)
                assertTrue(file.exists())
                assertEquals(listOf(confirmed), model.state.value.items)
                assertEquals(setOf(remote), model.state.value.confirmedRemoteIds)
                server.enqueue(MockResponse().setResponseCode(204))
                model.remove(confirmed)
                await(model)
                assertNull(model.state.value.error)
                assertFalse(file.exists())
                assertTrue(model.state.value.items.isEmpty())
                assertTrue(model.state.value.confirmedRemoteIds.isEmpty())
                EvidenceStore(app.noBackupFilesDir).use { assertTrue(it.list(scope).isEmpty()) }
                repeat(2) {
                    val request = server.takeRequest()
                    assertEquals("DELETE", request.method)
                    assertEquals("/movil/v1/siniestros/${scope.claimId}/evidencias/$remote", request.path)
                    assertNotNull(request.getHeader("Authorization"))
                }
            } finally { ViewModelStore().apply { put("evidence", model); clear() } }
        }
    }

    @Test fun remoteOnlyDemoDeletionDoesNotInventALocalFileAndWrongActorDoesNotSend() {
        MockWebServer().use { server ->
            val remote = UUID.randomUUID().toString()
            val model = EvidenceViewModel(app, MobileEvidenceApi(evidenceAuth(), server.url("/movil/v1").toString()))
            try {
                model.open(scope, policy, setOf(remote))
                await(model)
                server.enqueue(MockResponse().setResponseCode(204))
                model.removeDemoConfirmed(remote)
                await(model)
                assertTrue(model.state.value.confirmedRemoteIds.isEmpty())
                assertTrue(model.state.value.items.isEmpty())
                assertEquals(1, server.requestCount)
                model.open(scope.copy(actorId = "other-actor"), policy, setOf(remote))
                await(model)
                model.removeDemoConfirmed(remote)
                await(model)
                assertEquals(EvidenceError.SESSION, model.state.value.error)
                assertEquals(1, server.requestCount)
                assertEquals(setOf(remote), model.state.value.confirmedRemoteIds)
            } finally { ViewModelStore().apply { put("evidence", model); clear() } }
        }
    }
}
