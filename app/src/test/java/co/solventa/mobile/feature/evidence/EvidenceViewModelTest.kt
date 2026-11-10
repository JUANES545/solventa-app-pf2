package co.solventa.mobile.feature.evidence

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EvidenceViewModelTest {
    private val app = RuntimeEnvironment.getApplication()
    private val api = MobileEvidenceApi(evidenceAuth(), "http://127.0.0.1:1/movil/v1")
    private val model = EvidenceViewModel(app, api)
    private val scope = EvidenceScope("actor-a", UUID.randomUUID().toString())
    private val policy = EvidencePolicy(4, mapOf(EvidenceKind.PHOTO to 1_000_000L), 0)

    private fun await() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (model.state.value.busy && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertFalse("Operation did not finish", model.state.value.busy)
        assertFalse(model.state.value.loadingClaims)
        assertNull(model.state.value.error)
    }

    private fun capture() {
        model.camera(EvidenceKind.PHOTO)
        val draft = checkNotNull(model.beginCapture())
        val image = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        draft.file.outputStream().use { check(image.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
        image.recycle()
        model.captured(draft)
        assertFalse(model.state.value.loadingClaims)
        await()
        assertEquals(EvidenceStep.REVIEW, model.state.value.step)
    }

    @Test fun consentSurvivesNavigationButUncheckedSecondCaptureCannotAttachLocation() {
        model.open(scope, policy, emptySet())
        await()
        model.enableLocation(true)
        model.location(EvidenceLocation(4.6, -74.0, Instant.now()))
        capture()
        assertTrue(model.state.value.locationEnabled)
        model.accept(true)
        await()
        assertTrue(model.state.value.locationEnabled)
        assertNotNull(model.state.value.items.single().location)
        model.enableLocation(false)
        capture()
        model.accept(true)
        await()
        assertFalse(model.state.value.locationEnabled)
        assertEquals(1, model.state.value.items.count { it.location != null })
        model.close()
        model.open(scope, policy, emptySet())
        await()
        assertEquals(2, model.state.value.items.size)
        assertFalse(model.state.value.locationEnabled)
    }

    @Test fun confirmedReportOpensAuthorizedEvidenceWithoutManualIdentifierEntry() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(
                """{"maxCantidad":7,"maxChunkBytes":1024,"maxVideoSeconds":45,"formatos":[{"mimeType":"image/jpeg","maxBytes":1234}]}"""))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"evidencias":[]}"""))
            val connected = EvidenceViewModel(app, MobileEvidenceApi(evidenceAuth(), server.url("/movil/v1").toString()))
            val claim = EvidenceClaim(scope.claimId, "Choque", "2026-03-01T12:30:00Z", "Descripcion sintetica")
            connected.showClaim(scope.actorId, claim)
            assertTrue(connected.state.value.loadingClaims)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (connected.state.value.busy && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            assertFalse(connected.state.value.busy)
            assertFalse(connected.state.value.loadingClaims)
            assertNull(connected.state.value.error)
            assertEquals(scope, connected.state.value.scope)
            assertEquals(7, connected.state.value.policy?.maxCount)
            assertEquals(listOf(claim), connected.state.value.claims)
            connected.bindActor("actor-b")
            assertNull(connected.state.value.scope)
            assertFalse(connected.state.value.loadingClaims)
            androidx.lifecycle.ViewModelStore().apply { put("evidence", connected); clear() }
        }
    }

    @Test fun revokedPermissionAndChangedActorCannotLeakCoordinatesOrLateCapture() {
        model.open(scope, policy, emptySet())
        await()
        model.enableLocation(true)
        model.location(EvidenceLocation(4.6, -74.0, Instant.now()))
        capture()
        model.accept(false)
        await()
        assertNull(model.state.value.items.single().location)
        model.camera(EvidenceKind.PHOTO)
        val late = checkNotNull(model.beginCapture())
        model.bindActor("actor-b")
        model.captured(late)
        assertNull(model.state.value.scope)
        assertFalse(late.file.exists())
        model.open(scope.copy(actorId = "actor-b"), policy, emptySet())
        await()
        assertTrue(model.state.value.items.isEmpty())
    }

    @Test fun claimRefreshRetainsOnlyTheSameActorsSelectorAndClearsQueryOnFailure() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            val configuration = """{"maxCantidad":7,"maxChunkBytes":1024,"maxVideoSeconds":45,"formatos":[{"mimeType":"image/jpeg","maxBytes":1234}]}"""
            val claims = """{"siniestros":[{"id":"${scope.claimId}","tipoEvento":"Choque","fechaEvento":"2026-03-01T12:30:00Z","descripcion":"Daño en el vehículo."}]}"""
            val connected = EvidenceViewModel(app, MobileEvidenceApi(evidenceAuth(), server.url("/movil/v1").toString()))
            fun awaitQuery() {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (connected.state.value.busy && System.nanoTime() < deadline) {
                    shadowOf(Looper.getMainLooper()).idle()
                    Thread.sleep(10)
                }
                assertFalse(connected.state.value.busy)
                assertFalse(connected.state.value.loadingClaims)
            }
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(claims))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(configuration))
            connected.showClaims(scope.actorId)
            assertTrue(connected.state.value.loadingClaims)
            awaitQuery()
            val previous = connected.state.value.claims
            assertEquals(1, previous.size)
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(503).setBody("{}"))
            connected.showClaims(scope.actorId)
            assertTrue(connected.state.value.loadingClaims)
            assertEquals(previous, connected.state.value.claims)
            awaitQuery()
            assertEquals(EvidenceError.NETWORK, connected.state.value.error)
            assertEquals(503, connected.state.value.errorHttpStatus)
            assertEquals(previous, connected.state.value.claims)
            connected.bindActor("actor-b")
            assertTrue(connected.state.value.claims.isEmpty())
            assertNull(connected.state.value.configuration)
            assertFalse(connected.state.value.loadingClaims)
            androidx.lifecycle.ViewModelStore().apply { put("evidence", connected); clear() }
        }
    }

    @Test fun accountWithoutClaimsDoesNotRequireCaptureConfigurationOrLookLikeAFailure() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"siniestros":[]}"""))
            val connected = EvidenceViewModel(app, MobileEvidenceApi(evidenceAuth(), server.url("/movil/v1").toString()))
            connected.showClaims(scope.actorId)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (connected.state.value.busy && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            assertFalse(connected.state.value.busy)
            assertNull(connected.state.value.error)
            assertTrue(connected.state.value.claims.isEmpty())
            assertNull(connected.state.value.configuration)
            assertEquals(1, server.requestCount)
            assertEquals("/movil/v1/siniestros", server.takeRequest().path)
            androidx.lifecycle.ViewModelStore().apply { put("evidence", connected); clear() }
        }
    }
}
