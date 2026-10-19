package co.solventa.mobile.feature.auth

import android.app.Application
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class MobileAuthApiTest {
    private val userJson = """{"id":"11111111-1111-4111-8111-111111111111","nombre":"Cliente","correo":"cliente@example.org","rol":"cliente"}"""
    private val userResponse get() = """{"usuario":$userJson}"""
    private val grantResponse get() = """{"usuario":$userJson,"tokenType":"Bearer","accessToken":"access","refreshToken":"refresh","expiraEn":"2030-01-01T00:00:00Z","refreshExpiraEn":"2030-02-01T00:00:00Z"}"""
    private lateinit var server: MockWebServer
    private lateinit var api: MobileAuthApi

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        api = MobileAuthApi(server.url("/movil/v1").toString())
    }
    @After fun tearDown() { server.shutdown() }

    @Test fun registrationUsesMobileContractWithoutSendingConfirmationOrAuthorization() {
        server.enqueue(MockResponse().setResponseCode(201).setBody(userResponse))
        val user = api.register("Cliente", "cliente@example.org", " password ")
        assertEquals("Cliente", user.name)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/movil/v1/usuarios", request.path)
        val json = JSONObject(request.body.readUtf8())
        assertEquals(3, json.length())
        assertEquals("Cliente", json.getString("nombre"))
        assertEquals(user.email, json.getString("correo"))
        assertEquals(" password ", json.getString("contrasena"))
        assertNull(request.getHeader("Authorization"))
        assertEquals("no-store", request.getHeader("Cache-Control"))
        assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"))
    }

    @Test fun loginRefreshCurrentAndLogoutUseExpectedPayloadsAndTokens() {
        server.enqueue(MockResponse().setBody(grantResponse))
        val grant = api.login("cliente@example.org", "password")
        val login = server.takeRequest()
        assertEquals("/movil/v1/sesiones", login.path)
        assertEquals("password", JSONObject(login.body.readUtf8()).getString("contrasena"))
        assertEquals(Instant.parse("2030-01-01T00:00:00Z").toEpochMilli(), grant.expiresAt)
        server.enqueue(MockResponse().setBody(grantResponse))
        assertEquals(grant, api.refresh("refresh"))
        val refresh = server.takeRequest()
        assertEquals("/movil/v1/sesiones/renovar", refresh.path)
        assertEquals("refresh", JSONObject(refresh.body.readUtf8()).getString("refreshToken"))
        server.enqueue(MockResponse().setBody(userResponse))
        assertEquals(grant.user, api.current("access"))
        val current = server.takeRequest()
        assertEquals("GET", current.method)
        assertEquals("Bearer access", current.getHeader("Authorization"))
        server.enqueue(MockResponse().setResponseCode(204))
        api.logout("access", "refresh")
        val logout = server.takeRequest()
        assertEquals("DELETE", logout.method)
        assertEquals("/movil/v1/sesiones/actual", logout.path)
        assertEquals("Bearer access", logout.getHeader("Authorization"))
        assertEquals("refresh", JSONObject(logout.body.readUtf8()).getString("refreshToken"))
    }

    @Test fun httpFailuresAreNotDecodedAsSuccessfulSessionsOrFollowedAsRedirects() {
        for (code in listOf(302, 400, 401, 409, 429, 503)) {
            server.enqueue(MockResponse().setResponseCode(code).setBody("{}").addHeader("Location", server.url("/unexpected")))
            try { api.login("cliente@example.org", "password"); fail("Expected HTTP rejection") }
            catch (error: AuthHttpError) { assertEquals(code, error.status) }
        }
    }

    @Test fun oversizedOrMalformedResponsesAreRejected() {
        for (invalid in listOf("x".repeat(65_537), "not-json", "{}")) {
            server.enqueue(MockResponse().setBody(invalid))
            try { api.current("access"); fail("Expected invalid response") }
            catch (_: Exception) { }
        }
    }

    @Test fun grantDecoderRejectsWrongRoleTokensDatesAndIdentifiers() {
        for ((field, value) in listOf("tokenType" to "Basic", "accessToken" to "", "refreshToken" to "", "expiraEn" to "yesterday")) {
            val json = JSONObject(grantResponse).put(field, value)
            try { decodeGrant(json); fail("Expected invalid grant") }
            catch (_: Exception) { }
        }
        for ((field, value) in listOf("rol" to "admin", "id" to "invalid")) {
            val json = JSONObject(userResponse)
            json.getJSONObject("usuario").put(field, value)
            try { decodeUser(json); fail("Expected invalid user") }
            catch (_: Exception) { }
        }
    }
}
