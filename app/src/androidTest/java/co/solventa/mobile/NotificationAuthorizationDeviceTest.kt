package co.solventa.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import co.solventa.mobile.feature.auth.*
import co.solventa.mobile.feature.notification.*
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationAuthorizationDeviceTest {
    @Test fun queriesAndPreferencesUseTheSessionAndItsRotatedToken() = runBlocking {
        check(BuildConfig.APPLICATION_ID == "co.solventa.mobile.pf2.claimtest")
        val user = AuthUser("synthetic-actor", "Cliente", "cliente@example.test")
        var stored: AuthGrant? = AuthGrant(user, "synthetic-access", "synthetic-refresh",
            Long.MAX_VALUE - 1, Long.MAX_VALUE)
        var refreshes = 0
        val auth = AuthRepository(object : AuthApi {
            override fun register(name: String, email: String, password: String) = error("Not used")
            override fun login(email: String, password: String) = error("Not used")
            override fun current(token: String) = user
            override fun refresh(token: String): AuthGrant {
                refreshes++
                return checkNotNull(stored).copy(accessToken = "synthetic-rotated")
            }
            override fun logout(accessToken: String, refreshToken: String) = Unit
        }, object : AuthStore {
            override fun read() = stored
            override fun write(grant: AuthGrant) { stored = grant }
            override fun clear() { stored = null }
        })
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val api = MobileNotificationApi(auth, "http://127.0.0.1:${server.port}/movil/v1")
            fun request(path: String, token: String, method: String = "GET") {
                val value = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                assertEquals("/movil/v1$path", value.path)
                assertEquals(method, value.method)
                assertEquals("Bearer $token", value.getHeader("Authorization"))
            }
            val preferences = JSONObject().put("preferencias", JSONArray(
                NotificationKind.entries.flatMap { kind -> NotificationChannel.entries.map { channel ->
                    JSONObject().put("tipo", kind.code).put("canal", channel.code).put("habilitada", true)
                } },
            )).toString()
            server.enqueue(MockResponse().setBody("""{"notificaciones":[]}"""))
            assertTrue(api.notifications(user.id).isEmpty())
            request("/notificaciones", "synthetic-access")
            server.enqueue(MockResponse().setBody(preferences))
            assertEquals(NotificationKind.entries.size * NotificationChannel.entries.size, api.preferences(user.id).size)
            request("/notificaciones/preferencias", "synthetic-access")
            server.enqueue(MockResponse().setBody(preferences))
            assertEquals(NotificationKind.entries.size * NotificationChannel.entries.size, api.updatePush(user.id, true).size)
            request("/notificaciones/preferencias", "synthetic-access", "PUT")
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setBody("""{"notificaciones":[]}"""))
            assertTrue(api.notifications(user.id).isEmpty())
            request("/notificaciones", "synthetic-access")
            request("/notificaciones", "synthetic-rotated")
            assertEquals(1, refreshes)
            repeat(2) { server.enqueue(MockResponse().setResponseCode(401)) }
            try {
                api.notifications(user.id)
                fail("A rejected session must remain an error")
            } catch (problem: NotificationProblem) {
                assertEquals(NotificationError.SESSION, problem.reason)
            }
            request("/notificaciones", "synthetic-rotated")
            request("/notificaciones", "synthetic-rotated")
            assertEquals(2, refreshes)
            assertEquals(7, server.requestCount)
        }
    }
}
