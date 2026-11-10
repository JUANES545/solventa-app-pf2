package co.solventa.mobile.feature.claim

import android.app.Application
import android.os.Looper
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ClaimStatusViewModelTest {
    private fun await(model: ClaimStatusViewModel) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (model.state.value.busy && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertFalse("Query did not finish", model.state.value.busy)
    }

    @Test fun refreshRetainsSameActorDataAndNetworkFailureDoesNotClaimItIsFresh() {
        MockWebServer().use { server ->
            val model = ClaimStatusViewModel(statusApi(server))
            model.refresh()
            server.enqueue(MockResponse().setBody(statusList()))
            model.show("actor-a"); model.refresh(); await(model)
            assertEquals(1, server.requestCount)
            assertEquals(listOf(statusClaim), model.state.value.claims)
            model.select(statusClaim.copy(id = UUID.randomUUID().toString()))
            assertNull(model.state.value.selectedId)
            server.enqueue(MockResponse().setBody(statusResponse().toString()))
            model.select(statusClaim); model.refresh(); await(model)
            assertEquals(statusDate, model.state.value.detail!!.updatedAt)
            server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
            model.refresh()
            assertEquals(statusDate, model.state.value.detail!!.updatedAt)
            await(model)
            assertEquals(ClaimStatusError.NETWORK, model.state.value.error)
            assertEquals(statusDate, model.state.value.detail!!.updatedAt)
            assertEquals(statusClaim.id, model.state.value.selectedId)
            val changed = statusDate.plusSeconds(60)
            server.enqueue(MockResponse().setBody(statusResponse(progress = ClaimProgress.REVIEWING, date = changed).toString()))
            model.refresh(); await(model)
            assertNull(model.state.value.error)
            assertEquals(ClaimProgress.REVIEWING, model.state.value.detail!!.progress)
            assertEquals(changed, model.state.value.detail!!.updatedAt)
            server.enqueue(MockResponse().setBody(statusList(*emptyArray())))
            model.back(); await(model)
            assertNull(model.state.value.selectedId)
            assertTrue(model.state.value.claims.isEmpty())
            model.back()
            assertFalse(model.state.value.open)
            assertEquals(5, server.requestCount)
        }
    }

    @Test fun missingForbiddenAndMalformedResponsesClearPreviouslyVisibleData() {
        MockWebServer().use { server ->
            val model = ClaimStatusViewModel(statusApi(server))
            for ((response, expected) in listOf(
                MockResponse().setResponseCode(404).setBody("""{"code":"siniestro_no_encontrado"}""") to ClaimStatusError.UNAVAILABLE,
                MockResponse().setResponseCode(403).setBody("") to ClaimStatusError.SESSION,
                MockResponse().setBody("{}") to ClaimStatusError.PROTOCOL,
            )) {
                server.enqueue(MockResponse().setBody(statusResponse().toString()))
                model.show("actor-a", statusClaim.id); await(model)
                assertNotNull(model.state.value.detail)
                server.enqueue(response)
                model.refresh(); await(model)
                assertNull(model.state.value.detail)
                assertTrue(model.state.value.claims.isEmpty())
                assertEquals(expected, model.state.value.error)
            }
            model.back()
            assertFalse(model.state.value.open)
        }
    }

    @Test fun actorChangeAndCloseDiscardLateResponses() {
        MockWebServer().use { server ->
            val model = ClaimStatusViewModel(statusApi(server))
            for (logout in listOf(false, true)) {
                server.enqueue(MockResponse().setBody(statusResponse().toString()).setBodyDelay(250, TimeUnit.MILLISECONDS))
                model.show("actor-a", statusClaim.id)
                shadowOf(Looper.getMainLooper()).idle()
                assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                if (logout) model.bindActor(null) else model.back()
                Thread.sleep(300)
                shadowOf(Looper.getMainLooper()).idle()
                assertNull(model.state.value.detail)
                assertNull(model.state.value.error)
                assertFalse(model.state.value.open)
                assertFalse(model.state.value.busy)
            }
            server.enqueue(MockResponse().setBody(statusResponse().toString()).setBodyDelay(250, TimeUnit.MILLISECONDS))
            model.show("actor-a", statusClaim.id)
            shadowOf(Looper.getMainLooper()).idle()
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            model.bindActor("actor-b")
            Thread.sleep(300)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("actor-b", model.state.value.actor)
            assertNull(model.state.value.detail)
            assertFalse(model.state.value.open)
        }
    }

    @Test fun reopeningAnotherClaimWinsOverAnOlderRequestAndResumeReadsAgain() {
        MockWebServer().use { server ->
            val model = ClaimStatusViewModel(statusApi(server))
            server.enqueue(MockResponse().setBody(statusResponse().toString()).setBodyDelay(250, TimeUnit.MILLISECONDS))
            model.show("actor-a", statusClaim.id)
            shadowOf(Looper.getMainLooper()).idle()
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            val nextId = UUID.randomUUID().toString()
            server.enqueue(MockResponse().setBody(statusResponse(nextId, ClaimProgress.PAID).toString()))
            model.show("actor-a", nextId); await(model)
            assertEquals(nextId, model.state.value.detail!!.id)
            model.bindActor("actor-a")
            assertNotNull(model.state.value.detail)
            model.pause()
            assertNull(model.state.value.detail)
            assertTrue(model.state.value.open)
            server.enqueue(MockResponse().setBody(statusResponse(nextId, ClaimProgress.CLOSED).toString()))
            model.refresh(); await(model)
            assertEquals(ClaimProgress.CLOSED, model.state.value.detail!!.progress)
            model.back()
            model.refresh()
            assertFalse(model.state.value.open)
            assertEquals(3, server.requestCount)
        }
    }
}
