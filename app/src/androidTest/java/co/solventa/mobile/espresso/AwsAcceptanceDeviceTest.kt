package co.solventa.mobile.espresso

import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import co.solventa.mobile.BuildConfig
import co.solventa.mobile.MainActivity
import co.solventa.mobile.R
import co.solventa.mobile.feature.auth.AuthHttpError
import co.solventa.mobile.feature.auth.AuthRepository
import co.solventa.mobile.feature.auth.AuthViewModel
import co.solventa.mobile.feature.auth.EncryptedAuthStore
import co.solventa.mobile.feature.auth.MobileAuthApi
import co.solventa.mobile.feature.assistance.AssistanceLocation
import co.solventa.mobile.feature.assistance.AssistanceLocationOrigin
import co.solventa.mobile.feature.assistance.MobileAssistanceApi
import co.solventa.mobile.feature.claim.MobileClaimApi
import co.solventa.mobile.feature.evidence.MobileEvidenceApi
import co.solventa.mobile.feature.notification.MobileNotificationApi
import co.solventa.mobile.feature.notification.NotificationChannel
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.io.File
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@BackendE2e
@HiltAndroidTest
class AwsAcceptanceDeviceTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    @Inject lateinit var auth: AuthRepository

    @Test fun authorizedSyntheticAccountUsesOfficialClientsOverHttps() {
        check(BuildConfig.APPLICATION_ID == "co.solventa.mobile.pf2.awstest")
        check(BuildConfig.SCREEN_CAPTURE_ALLOWED)
        check(InstrumentationRegistry.getArguments().getString("runAwsAcceptance") == "true")
        hilt.inject()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = JSONObject(File(context.filesDir, "aws-account.json").readText())
        val results = JSONArray()
        val store = EncryptedAuthStore(context)
        val api = MobileAuthApi()
        var access: AuthViewModel? = null
        var scenario: ActivityScenario<MainActivity>? = null
        fun record(flow: String, result: String, count: Int? = null) {
            results.put(JSONObject().put("flow", flow).put("result", result).apply {
                if (count != null) put("count", count)
            })
        }
        try {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity {
                it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                assertEquals(0, it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
                access = ViewModelProvider(it)[AuthViewModel::class.java]
            }
            val emailLabel = context.getString(R.string.email)
            val passwordLabel = context.getString(R.string.password)
            compose.waitUntil(30_000) {
                compose.onAllNodesWithContentDescription(emailLabel).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription(emailLabel).performScrollTo().performTextReplacement(fixture.getString("email"))
            compose.onNodeWithContentDescription(passwordLabel).performScrollTo().performTextReplacement(fixture.getString("password"))
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.onNodeWithText(context.getString(R.string.sign_in)).performScrollTo().performClick()
            compose.waitUntil(45_000) { access?.state?.value?.let { !it.loading && (it.user != null || it.error != null) } == true }
            assertNull(access!!.state.value.error)
            val actor = checkNotNull(access!!.state.value.user).id
            assertEquals(fixture.getString("actor"), actor)
            record("login-ui", "OK")
            runBlocking {
                assertEquals(actor, auth.restore()?.id)
                record("session-restore", "OK")
                val old = checkNotNull(store.read())
                store.write(old.copy(expiresAt = 0))
                assertEquals(actor, auth.restore()?.id)
                val rotated = checkNotNull(store.read())
                assertTrue(old.refreshToken != rotated.refreshToken)
                assertEquals(actor, api.current(rotated.accessToken).id)
                record("refresh-rotation", "OK")
                val http = MobileEvidenceApi(auth, BuildConfig.AUTH_BASE_URL)
                val claims = MobileClaimApi(http)
                val policies = claims.policies(actor)
                record("policies", if (policies.isEmpty()) "EMPTY" else "OK", policies.size)
                val history = claims.claims(actor)
                record("claim-history", if (history.isEmpty()) "EMPTY" else "OK", history.size)
                if (policies.isEmpty()) record("claim-report-offline-delivery", "BLOCKED_NO_OWN_POLICY")
                if (history.isEmpty()) {
                    record("claim-status", "BLOCKED_NO_OWN_CLAIM")
                    record("evidence-upload", "BLOCKED_NO_OWN_CLAIM")
                    record("assistance-request", "BLOCKED_NO_OWN_CLAIM")
                }
                assertTrue(http.configuration(actor).maxChunkBytes > 0)
                record("evidence-configuration", "OK")
                val notifications = MobileNotificationApi(auth, BuildConfig.AUTH_BASE_URL)
                val inbox = notifications.notifications(actor)
                record("notifications", if (inbox.isEmpty()) "EMPTY" else "OK", inbox.size)
                val preferences = notifications.preferences(actor)
                val push = preferences.filter { it.channel == NotificationChannel.PUSH }.map { it.enabled }.distinct()
                assertEquals(1, push.size)
                try {
                    assertTrue(notifications.updatePush(actor, !push.single())
                        .filter { it.channel == NotificationChannel.PUSH }.all { it.enabled != push.single() })
                } finally {
                    assertEquals(preferences.toSet(), notifications.updatePush(actor, push.single()).toSet())
                }
                record("notification-preferences-restored", "OK", preferences.size)
                val location = AssistanceLocation(4.656, -74.056, AssistanceLocationOrigin.MANUAL, Instant.now())
                val providers = MobileAssistanceApi(http).providers(actor, location, 10)
                record("provider-query-synthetic-location", if (providers.isEmpty()) "EMPTY" else "OK", providers.size)
                val credentials = api.biometricCredentials(checkNotNull(store.read()).accessToken)
                record("biometric-credential-list", "OK", credentials.size)
                val refreshed = checkNotNull(store.read())
                auth.logout()
                assertNull(store.read())
                record("logout", "OK")
                try {
                    api.refresh(refreshed.refreshToken)
                    fail("Revoked refresh token was accepted")
                } catch (error: AuthHttpError) {
                    assertEquals(401, error.status)
                }
                record("refresh-after-logout", "REJECTED_401")
            }
        } finally {
            scenario?.close()
            try {
                if (store.read() != null) runBlocking { auth.logout() }
            } finally {
                File(context.filesDir, "aws-acceptance-results.json").writeText(results.toString(2))
                File(context.filesDir, "aws-account.json").delete()
            }
        }
    }
}
