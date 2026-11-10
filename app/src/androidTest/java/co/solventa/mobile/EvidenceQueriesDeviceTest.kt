package co.solventa.mobile

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import co.solventa.mobile.feature.auth.*
import co.solventa.mobile.feature.evidence.*
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EvidenceQueriesDeviceTest {
    @Test fun emptyAccountServiceFailureAndEmptyEvidenceRemainDifferentStates() {
        check(BuildConfig.DEBUG && BuildConfig.APPLICATION_ID in
            setOf("co.solventa.mobile.pf2", "co.solventa.mobile.pf2.claimtest"))
        val user = AuthUser("synthetic-query-actor", "Cliente", "cliente@example.test")
        val grant = AuthGrant(user, "synthetic-access", "synthetic-refresh", Long.MAX_VALUE - 1, Long.MAX_VALUE)
        val auth = AuthRepository(object : AuthApi {
            override fun register(name: String, email: String, password: String) = error("Not used")
            override fun login(email: String, password: String) = grant
            override fun current(token: String) = user
            override fun refresh(token: String) = grant
            override fun logout(accessToken: String, refreshToken: String) = Unit
        }, object : AuthStore {
            override fun read() = grant
            override fun write(grant: AuthGrant) = Unit
            override fun clear() = Unit
        })
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val api = MobileEvidenceApi(auth, "http://127.0.0.1:${server.port}/movil/v1")
            val model = EvidenceViewModel(ApplicationProvider.getApplicationContext<Application>(), api)
            fun awaitQuery() {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (model.state.value.busy && System.nanoTime() < deadline) Thread.sleep(10)
                assertFalse("Query timed out", model.state.value.busy)
            }
            try {
                server.enqueue(MockResponse().setBody("""{"siniestros":[]}"""))
                instrumentation.runOnMainSync { model.showClaims(user.id) }
                awaitQuery()
                assertNull(model.state.value.error)
                assertTrue(model.state.value.claims.isEmpty())
                assertNull(model.state.value.configuration)
                assertEquals(1, server.requestCount)

                server.enqueue(MockResponse().setResponseCode(503).setBody("""{"code":"servicio_no_disponible"}"""))
                instrumentation.runOnMainSync { model.showClaims(user.id) }
                awaitQuery()
                assertEquals(EvidenceError.NETWORK, model.state.value.error)
                assertEquals(503, model.state.value.errorHttpStatus)

                val id = "11111111-1111-4111-8111-111111111119"
                server.enqueue(MockResponse().setBody("""{"siniestros":[{"id":"$id","tipoEvento":"Choque","fechaEvento":"2026-11-09T15:00:00Z","descripcion":"Registro sintético"}]}"""))
                server.enqueue(MockResponse().setBody("""{"maxCantidad":7,"maxChunkBytes":1024,"maxVideoSeconds":45,"formatos":[{"mimeType":"image/jpeg","maxBytes":1234}]}"""))
                instrumentation.runOnMainSync { model.showClaims(user.id) }
                awaitQuery()
                assertNull(model.state.value.error)
                assertNull(model.state.value.errorHttpStatus)
                val claim = model.state.value.claims.single()

                server.enqueue(MockResponse().setResponseCode(404).setBody("""{"code":"siniestro_no_encontrado"}"""))
                instrumentation.runOnMainSync { model.select(claim) }
                awaitQuery()
                assertEquals(EvidenceError.UNAVAILABLE, model.state.value.error)
                assertNull(model.state.value.scope)

                server.enqueue(MockResponse().setBody("""{"evidencias":[]}"""))
                instrumentation.runOnMainSync { model.select(claim) }
                awaitQuery()
                assertNull(model.state.value.error)
                assertEquals(id, model.state.value.scope?.claimId)
                assertTrue(model.state.value.confirmedRemoteIds.isEmpty())
                assertTrue(model.state.value.items.isEmpty())
                assertEquals(6, server.requestCount)
            } finally {
                instrumentation.runOnMainSync { ViewModelStore().apply { put("evidence", model); clear() } }
            }
        }
    }
}
