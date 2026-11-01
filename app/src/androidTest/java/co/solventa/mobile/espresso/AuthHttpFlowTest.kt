package co.solventa.mobile.espresso

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.Espresso
import co.solventa.mobile.R
import co.solventa.mobile.feature.auth.AuthGrant
import co.solventa.mobile.feature.auth.AuthUser
import dagger.hilt.android.testing.HiltAndroidTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@HiltAndroidTest
class AuthHttpFlowTest : AuthJourney() {
    private val server = MockWebServer()
    private val user = AuthUser("11111111-1111-4111-8111-111111111111", "Cliente Espresso", EMAIL)

    @Before fun startServer() {
        check(localBackendUrl() == "http://127.0.0.1:18080/movil/v1") {
            "Controlled HTTP tests must not target a real backend."
        }
        server.start(InetAddress.getByName("127.0.0.1"), 18080)
    }

    @After fun stopServer() {
        scenario?.close()
        scenario = null
        server.shutdown()
    }

    private fun userJson() = JSONObject().put("usuario", JSONObject()
        .put("id", user.id).put("nombre", user.name).put("correo", user.email).put("rol", "cliente"))

    private fun grant(access: String = "access-one", refresh: String = "refresh-one",
        expired: Boolean = false) = AuthGrant(user, access, refresh,
        System.currentTimeMillis() + if (expired) -60_000 else 600_000,
        System.currentTimeMillis() + 3_600_000)

    private fun grantJson(value: AuthGrant = grant()) = userJson()
        .put("tokenType", "Bearer").put("accessToken", value.accessToken)
        .put("refreshToken", value.refreshToken)
        .put("expiraEn", Instant.ofEpochMilli(value.expiresAt).toString())
        .put("refreshExpiraEn", Instant.ofEpochMilli(value.refreshExpiresAt).toString())

    private fun respond(body: JSONObject, status: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(status)
            .setHeader("Content-Type", "application/json").setBody(body.toString()))
    }

    private fun status(code: Int) {
        server.enqueue(MockResponse().setResponseCode(code))
    }

    private fun request(method: String, path: String, token: String? = null): RecordedRequest {
        val request = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Missing $method $path" }
        assertEquals(method, request.method)
        assertEquals("/movil/v1$path", request.path)
        assertEquals(token?.let { "Bearer $it" }, request.getHeader("Authorization"))
        return request
    }

    @Test fun registrationLoginColdActivityRestoreAndLogoutUseHttpAndEncryptedStorage() {
        respond(userJson(), 201)
        respond(grantJson())
        respond(userJson())
        status(204)
        launch()
        register()
        awaitText(R.string.registration_success)
        val registration = JSONObject(request("POST", "/usuarios").body.readUtf8())
        assertEquals("Cliente Espresso", registration.getString("nombre"))
        assertEquals(EMAIL, registration.getString("correo"))
        assertEquals(PASSWORD, registration.getString("contrasena"))
        assertNull(store.read())
        click(R.string.go_to_login)
        assertInput(R.string.email, EMAIL)
        assertInput(R.string.password, "")
        login()
        awaitText(R.string.account_title)
        val login = JSONObject(request("POST", "/sesiones").body.readUtf8())
        assertEquals(EMAIL, login.getString("correo"))
        assertEquals(PASSWORD, login.getString("contrasena"))
        assertEquals(user, store.read()?.user)
        val raw = context.getSharedPreferences("mobile-auth", 0).getString("grant", "")!!
        assertFalse(raw.contains("access-one"))
        assertFalse(raw.contains("refresh-one"))
        assertFalse(raw.contains(EMAIL))

        scenario!!.close()
        launch()
        awaitText(R.string.account_title)
        awaitText(R.string.sign_out)
        request("GET", "/sesiones/actual", "access-one")
        compose.onNodeWithText(EMAIL).performScrollTo()
        click(R.string.sign_out)
        awaitText(R.string.sign_in)
        assertEquals("refresh-one", JSONObject(
            request("DELETE", "/sesiones/actual", "access-one").body.readUtf8()).getString("refreshToken"))
        assertNull(store.read())
        scenario!!.close()
        launch()
        awaitText(R.string.sign_in)
        assertEquals(4, server.requestCount)
    }

    @Test fun invalidFormsNeverReachTheNetworkAndSystemBackReturnsToLogin() {
        launch()
        login("not-an-email")
        awaitText(R.string.auth_email)
        login(password = "short")
        awaitText(R.string.auth_password)
        register(confirmation = "different-password")
        awaitText(R.string.auth_password_mismatch)
        Espresso.pressBack()
        awaitText(R.string.sign_in)
        assertInput(R.string.password, "")
        assertNull(store.read())
        assertEquals(0, server.requestCount)
    }

    @Test fun duplicateRegistrationStaysOnFormWithoutPersistingSession() {
        status(409)
        launch()
        register()
        awaitText(R.string.auth_email_exists)
        request("POST", "/usuarios")
        assertNull(store.read())
        click(R.string.back_to_login)
        assertInput(R.string.email, EMAIL)
        assertInput(R.string.password, "")
    }

    @Test fun failedRegistrationDoesNotClaimTheAccountWasCreated() {
        status(503)
        launch()
        register()
        awaitText(R.string.registration_unavailable)
        assertNull(store.read())
        assertEquals(1, server.requestCount)
    }

    @Test fun disconnectedRegistrationCanReturnToExplicitLoginWithoutResubmitting() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        launch()
        register()
        awaitText(R.string.registration_unavailable)
        request("POST", "/usuarios")
        assertNull(store.read())
        assertEquals(1, server.requestCount)
        click(R.string.back_to_login)
        assertInput(R.string.email, EMAIL)
        assertInput(R.string.password, "")
        assertEquals(1, server.requestCount)
        respond(grantJson())
        login()
        awaitText(R.string.account_title)
        request("POST", "/sesiones")
        assertEquals(2, server.requestCount)
    }

    @Test fun registrationRateLimitRequiresExplicitRetryAndNeverCreatesASession() {
        status(429)
        launch()
        register()
        awaitText(R.string.auth_rate_limit)
        request("POST", "/usuarios")
        assertNull(store.read())
        assertEquals(1, server.requestCount)
        respond(userJson(), 201)
        click(R.string.back_to_login)
        register()
        awaitText(R.string.registration_success)
        request("POST", "/usuarios")
        assertNull(store.read())
        assertEquals(2, server.requestCount)
        click(R.string.go_to_login)
        assertInput(R.string.email, EMAIL)
        assertInput(R.string.password, "")
        assertEquals(2, server.requestCount)
    }

    @Test fun malformedRegistrationResponseNeverAnnouncesSuccessOrStartsLogin() {
        respond(JSONObject().put("usuario", "invalid"), 201)
        launch()
        register()
        awaitText(R.string.registration_unavailable)
        request("POST", "/usuarios")
        assertNull(store.read())
        assertEquals(1, server.requestCount)
        click(R.string.back_to_login)
        assertInput(R.string.email, EMAIL)
        assertInput(R.string.password, "")
    }

    @Test fun registrationInFlightDisablesInputsAndPreventsDuplicateSubmission() {
        val releaseResponse = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                check(releaseResponse.await(20, TimeUnit.SECONDS)) { "Test did not release registration response" }
                return MockResponse().setResponseCode(201).setHeader("Content-Type", "application/json")
                    .setBody(userJson().toString())
            }
        }
        try {
            launch()
            register()
            request("POST", "/usuarios")
            awaitText(R.string.registering)
            for (field in listOf(R.string.name, R.string.email, R.string.password, R.string.confirm_password)) {
                compose.onNodeWithContentDescription(text(field)).performScrollTo().assertIsNotEnabled()
            }
            compose.onNodeWithText(text(R.string.registering)).performScrollTo().assertIsNotEnabled().performClick()
            compose.onNodeWithText(text(R.string.back_to_login)).performScrollTo().assertIsNotEnabled()
            Espresso.pressBack()
            assertNull(store.read())
            assertEquals(1, server.requestCount)
        } finally {
            releaseResponse.countDown()
        }
        awaitText(R.string.registration_success)
        assertNull(store.read())
        assertEquals(1, server.requestCount)
        click(R.string.go_to_login)
        assertInput(R.string.email, EMAIL)
        assertInput(R.string.password, "")
    }

    @Test fun invalidCredentialsCanBeCorrectedWithoutLeavingLogin() {
        status(401)
        respond(grantJson())
        launch()
        login()
        awaitText(R.string.auth_invalid)
        assertNull(store.read())
        login()
        awaitText(R.string.account_title)
        assertEquals(2, server.requestCount)
    }

    @Test fun rateLimitIsShownWithoutAutomaticallyRetrying() {
        status(429)
        launch()
        login()
        awaitText(R.string.auth_rate_limit)
        assertNull(store.read())
        assertEquals(1, server.requestCount)
    }

    @Test fun unavailableServiceDoesNotCreateALocalSession() {
        status(503)
        launch()
        login()
        awaitText(R.string.auth_unavailable)
        assertNull(store.read())
        assertEquals(1, server.requestCount)
    }

    @Test fun malformedSuccessfulResponseIsNotAnAuthenticatedSession() {
        respond(JSONObject().put("usuario", "invalid"))
        launch()
        login()
        awaitText(R.string.auth_unavailable)
        assertNull(store.read())
    }

    @Test fun disconnectedResponseSurfacesConnectionError() {
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START))
        launch()
        login()
        awaitText(R.string.auth_unavailable)
        assertNull(store.read())
    }

    @Test fun loginInFlightDisablesInputsAndPreventsDuplicateSubmission() {
        val releaseResponse = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                check(releaseResponse.await(20, TimeUnit.SECONDS)) { "Test did not release login response" }
                return MockResponse().setHeader("Content-Type", "application/json").setBody(grantJson().toString())
            }
        }
        try {
            launch()
            login()
            request("POST", "/sesiones")
            awaitText(R.string.signing_in)
            compose.onNodeWithContentDescription(text(R.string.email)).assertIsNotEnabled()
            compose.onNodeWithContentDescription(text(R.string.password)).assertIsNotEnabled()
            compose.onNodeWithText(text(R.string.signing_in)).assertIsNotEnabled().performClick()
            compose.onNodeWithText(text(R.string.create_account)).performScrollTo().assertIsNotEnabled()
            assertEquals(1, server.requestCount)
        } finally {
            releaseResponse.countDown()
        }
        awaitText(R.string.account_title)
        assertEquals(1, server.requestCount)
    }

    @Test fun expiredAccessRotatesTokensBeforeProtectedRequestAndPersistsThem() {
        store.write(grant(expired = true))
        respond(grantJson(grant("access-two", "refresh-two")))
        respond(userJson())
        launch()
        awaitText(R.string.account_title)
        assertEquals("refresh-one", JSONObject(request("POST", "/sesiones/renovar")
            .body.readUtf8()).getString("refreshToken"))
        request("GET", "/sesiones/actual", "access-two")
        assertEquals("access-two", store.read()?.accessToken)
        assertEquals("refresh-two", store.read()?.refreshToken)
    }

    @Test fun unauthorizedCurrentSessionRenewsOnceAndRetriesWithRotatedAccess() {
        store.write(grant())
        status(401)
        respond(grantJson(grant("access-two", "refresh-two")))
        respond(userJson())
        launch()
        awaitText(R.string.account_title)
        request("GET", "/sesiones/actual", "access-one")
        request("POST", "/sesiones/renovar")
        request("GET", "/sesiones/actual", "access-two")
        assertEquals(3, server.requestCount)
    }

    @Test fun revokedRefreshClearsStorageAndShowsSessionExpired() {
        store.write(grant(expired = true))
        status(401)
        launch()
        awaitText(R.string.auth_expired)
        assertNull(store.read())
        assertEquals(1, server.requestCount)
    }

    @Test fun expiredRefreshIsRemovedWithoutNetworkTraffic() {
        store.write(grant().copy(refreshExpiresAt = System.currentTimeMillis() - 1_000))
        launch()
        awaitText(R.string.sign_in)
        compose.waitUntil(5_000) { store.read() == null }
        assertEquals(0, server.requestCount)
    }

    @Test fun logoutFailureStillRemovesLocalSessionAndReportsRemoteUncertainty() {
        respond(grantJson())
        status(503)
        launch()
        login()
        awaitText(R.string.account_title)
        click(R.string.sign_out)
        awaitText(R.string.auth_logout_remote)
        assertNull(store.read())
        request("POST", "/sesiones")
        request("DELETE", "/sesiones/actual", "access-one")
        scenario!!.close()
        launch()
        awaitText(R.string.sign_in)
        assertEquals(2, server.requestCount)
    }

    @Test fun corruptedStoredGrantShowsStorageErrorInsteadOfRestoringIdentity() {
        assertTrue(context.getSharedPreferences("mobile-auth", 0).edit()
            .putString("grant", "corrupt-test-value").commit())
        launch()
        awaitText(R.string.auth_storage)
        assertNull(store.read())
        assertEquals(0, server.requestCount)
    }

    @Test fun registrationPasswordVisibilityTogglesAndBackDoesNotRetainPassword() {
        launch()
        click(R.string.create_account)
        input(R.string.password, PASSWORD)
        compose.onNodeWithContentDescription(text(R.string.password))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.onNodeWithContentDescription(text(R.string.show_password)).performScrollTo().performClick()
        assertInput(R.string.password, PASSWORD)
        compose.onNodeWithContentDescription(text(R.string.hide_password)).performScrollTo().performClick()
        compose.onNodeWithContentDescription(text(R.string.password))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        Espresso.closeSoftKeyboard()
        Espresso.pressBack()
        awaitText(R.string.sign_in)
        assertInput(R.string.password, "")
        assertEquals(0, server.requestCount)
    }
}
