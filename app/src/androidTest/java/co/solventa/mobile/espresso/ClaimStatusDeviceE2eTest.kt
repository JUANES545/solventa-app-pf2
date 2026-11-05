package co.solventa.mobile.espresso

import android.view.WindowManager
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import co.solventa.mobile.BuildConfig
import co.solventa.mobile.MainActivity
import co.solventa.mobile.R
import co.solventa.mobile.core.designsystem.SolventaTheme
import co.solventa.mobile.feature.auth.AuthRepository
import co.solventa.mobile.feature.auth.AuthViewModel
import co.solventa.mobile.feature.claim.ClaimProgress
import co.solventa.mobile.feature.claim.ClaimStatusError
import co.solventa.mobile.feature.claim.ClaimStatusViewModel
import co.solventa.mobile.feature.claim.ClaimStatusScreen
import co.solventa.mobile.feature.claim.ClaimStatusState
import co.solventa.mobile.feature.claim.MobileClaimApi
import co.solventa.mobile.feature.claim.claimStatusDate
import co.solventa.mobile.feature.evidence.MobileEvidenceApi
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@ClaimDeviceE2e
@HiltAndroidTest
class ClaimStatusDeviceE2eTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    @Inject lateinit var auth: AuthRepository
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var fixture: JSONObject
    private lateinit var model: ClaimStatusViewModel
    private lateinit var access: AuthViewModel
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun prepare() {
        check(BuildConfig.APPLICATION_ID == "co.solventa.mobile.pf2.claimtest")
        check(BuildConfig.AUTH_BASE_URL == "http://127.0.0.1:18721/movil/v1")
        check(localBackendUrl() == BuildConfig.AUTH_BASE_URL)
        hilt.inject()
        fixture = JSONObject(File(context.filesDir, "claim-status-fixture.json").readText())
        runBlocking { auth.login(fixture.getString("email"), fixture.getString("password")) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity {
            it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            model = ViewModelProvider(it)[ClaimStatusViewModel::class.java]
            access = ViewModelProvider(it)[AuthViewModel::class.java]
        }
        compose.waitUntil(30_000) { access.state.value.user != null && !access.state.value.loading }
    }

    @After fun finish() {
        scenario?.close()
        if (::fixture.isInitialized) runBlocking { auth.logout() }
    }

    private fun text(id: Int) = context.getString(id)
    private fun settle() = compose.waitUntil(45_000) { !model.state.value.busy }
    private fun control(mode: String): JSONObject {
        val connection = URL("http://127.0.0.1:18721/__control/$mode").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            assertEquals(200, connection.responseCode)
            return connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        } finally { connection.disconnect() }
    }

    @Test fun authorizedStatusUpdatesFromBackendFailureRetryAndAccountIsolation() {
        val id = fixture.getString("claimId")
        val otherId = fixture.getString("otherClaimId")
        compose.onNodeWithText(text(R.string.claim_status_title)).performScrollTo().performClick()
        settle()
        assertTrue(model.state.value.open)
        assertEquals(listOf(id), model.state.value.claims.map { it.id })
        compose.onNode(hasText(id, substring = true)).performScrollTo().performClick()
        settle()
        assertEquals(ClaimProgress.REPORTED, model.state.value.detail!!.progress)
        val initialDate = Instant.parse(fixture.getString("updatedAt"))
        assertEquals(initialDate, model.state.value.detail!!.updatedAt)
        compose.onNodeWithText(text(R.string.claim_status_reported)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.claim_status_updated, claimStatusDate(initialDate)))
            .performScrollTo().assertIsDisplayed()
        scenario!!.onActivity {
            assertTrue(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        }
        val changedDate = Instant.parse(control("reviewing").getString("actualizadoEn"))
        compose.onNodeWithText(text(R.string.claim_status_refresh)).performScrollTo().performClick()
        settle()
        assertEquals(ClaimProgress.REVIEWING, model.state.value.detail!!.progress)
        assertEquals(changedDate, model.state.value.detail!!.updatedAt)
        compose.onNodeWithText(text(R.string.claim_status_reviewing)).performScrollTo().assertIsDisplayed()

        control("unavailable")
        compose.onNodeWithText(text(R.string.claim_status_refresh)).performScrollTo().performClick()
        settle()
        assertEquals(ClaimStatusError.NETWORK, model.state.value.error)
        assertNull(model.state.value.detail)
        compose.onNodeWithText(text(R.string.claim_status_reviewing)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.claim_status_updated, claimStatusDate(changedDate))).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.claim_status_network)).performScrollTo().assertIsDisplayed()
        control("online")
        compose.onNodeWithText(text(R.string.claim_status_retry)).performScrollTo().performClick()
        compose.waitUntil(30_000) { !model.state.value.busy && model.state.value.detail?.id == id }
        assertEquals(changedDate, model.state.value.detail!!.updatedAt)
        assertNull(model.state.value.error)
        scenario!!.recreate()
        scenario!!.onActivity {
            model = ViewModelProvider(it)[ClaimStatusViewModel::class.java]
            access = ViewModelProvider(it)[AuthViewModel::class.java]
        }
        compose.waitUntil(30_000) { !model.state.value.busy && model.state.value.detail?.id == id }
        assertEquals(changedDate, model.state.value.detail!!.updatedAt)

        for (unavailableId in listOf(otherId, UUID.randomUUID().toString())) {
            compose.runOnIdle { model.show(fixture.getString("actor"), unavailableId) }
            settle()
            assertEquals(ClaimStatusError.UNAVAILABLE, model.state.value.error)
            assertNull(model.state.value.detail)
            compose.onNodeWithText(text(R.string.claim_status_unavailable)).performScrollTo().assertIsDisplayed()
            compose.onAllNodes(hasText(unavailableId, substring = true)).assertCountEquals(0)
        }
        runBlocking { auth.login(fixture.getString("otherEmail"), fixture.getString("otherPassword")) }
        compose.runOnIdle { access.restore() }
        compose.waitUntil(30_000) {
            access.state.value.user?.id == fixture.getString("otherActor") && !access.state.value.loading
        }
        compose.waitForIdle()
        assertFalse(model.state.value.open)
        assertNull(model.state.value.detail)
        assertTrue(model.state.value.claims.isEmpty())
        compose.onNodeWithText(text(R.string.claim_status_title)).performScrollTo().performClick()
        settle()
        assertEquals(listOf(otherId), model.state.value.claims.map { it.id })
        compose.onAllNodes(hasText(id, substring = true)).assertCountEquals(0)
    }

    @Test fun statusPresentationInBothThemesAndLargeText() {
        val detail = runBlocking(Dispatchers.IO) {
            MobileClaimApi(MobileEvidenceApi(auth, BuildConfig.AUTH_BASE_URL))
                .status(fixture.getString("actor"), fixture.getString("claimId"))
        }
        val state = ClaimStatusState(selectedId = detail.id, detail = detail)
        for ((dark, scale, name) in listOf(
            Triple(false, 1f, "claim-status-light.png"), Triple(true, 2f, "claim-status-dark-large.png"),
        )) {
            // Only synthetic fixture data is rendered outside the secure host for these screenshots.
            scenario!!.onActivity { activity ->
                activity.setContent {
                    SolventaTheme(dark) {
                        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                            ClaimStatusScreen(state, {}, {}, {})
                        }
                    }
                }
            }
            compose.waitForIdle()
            compose.onRoot().captureToImage().asAndroidBitmap().let { image ->
                File(context.filesDir, name).outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            }
            compose.onNodeWithText(context.getString(R.string.claim_status_updated, claimStatusDate(detail.updatedAt)))
                .performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(text(R.string.claim_status_refresh)).performScrollTo().assertIsDisplayed()
        }
    }
}
