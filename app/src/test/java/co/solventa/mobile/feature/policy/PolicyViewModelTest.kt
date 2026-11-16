package co.solventa.mobile.feature.policy

import android.app.Application
import android.os.Looper
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
class PolicyViewModelTest {
    private fun await(model: PolicyViewModel) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (model.state.value.busy && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertFalse(model.state.value.busy)
    }

    @Test fun listDetailRefreshRetryAndBackDoNotRetainStaleProtectedData() {
        MockWebServer().use { server ->
            val model = PolicyViewModel(policyApi(server))
            model.refresh()
            server.enqueue(MockResponse().setBody(policyList()))
            model.show("actor-a"); model.refresh(); await(model)
            assertEquals(1, server.requestCount)
            assertTrue(model.state.value.loaded)
            model.select(policy.copy(number = "not in list"))
            assertNull(model.state.value.selectedId)
            server.enqueue(MockResponse().setBody(policyJson().toString()))
            model.select(policy); await(model)
            assertEquals(policy, model.state.value.detail)
            for (status in listOf(503, 403, 404)) {
                server.enqueue(MockResponse().setResponseCode(status).setBody("""{"code":"poliza_no_encontrada"}"""))
                model.refresh()
                assertNull(model.state.value.detail)
                await(model)
                assertFalse(model.state.value.loaded)
                assertNotNull(model.state.value.error)
                assertTrue(model.state.value.policies.isEmpty())
            }
            val changed = policy.copy(status = PolicyStatus.EXPIRED)
            server.enqueue(MockResponse().setBody(policyJson(changed).toString()))
            model.refresh(); await(model)
            assertEquals(changed, model.state.value.detail)
            model.pause()
            assertNull(model.state.value.detail)
            server.enqueue(MockResponse().setBody(policyJson().toString()))
            model.refresh(); await(model)
            assertEquals(policy, model.state.value.detail)
            server.enqueue(MockResponse().setBody(policyList(*emptyArray())))
            model.back(); await(model)
            assertTrue(model.state.value.loaded)
            assertTrue(model.state.value.policies.isEmpty())
            model.back()
            assertFalse(model.state.value.open)
        }
    }

    @Test fun logoutActorChangePauseAndCloseRejectLateResults() {
        MockWebServer().use { server ->
            val model = PolicyViewModel(policyApi(server))
            for (leave in listOf<() -> Unit>(
                { model.bindActor(null) }, { model.bindActor("actor-b") }, { model.pause() }, { model.back() },
            )) {
                server.enqueue(MockResponse().setBody(policyList()).setBodyDelay(250, TimeUnit.MILLISECONDS))
                model.show("actor-a")
                shadowOf(Looper.getMainLooper()).idle()
                assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                leave()
                Thread.sleep(300)
                shadowOf(Looper.getMainLooper()).idle()
                assertFalse(model.state.value.loaded)
                assertFalse(model.state.value.busy)
                assertTrue(model.state.value.policies.isEmpty())
                assertNull(model.state.value.detail)
                assertNull(model.state.value.error)
            }
        }
    }
}
