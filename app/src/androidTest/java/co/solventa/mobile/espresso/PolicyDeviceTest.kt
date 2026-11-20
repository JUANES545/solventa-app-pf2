package co.solventa.mobile.espresso

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import co.solventa.mobile.BuildConfig
import co.solventa.mobile.R
import co.solventa.mobile.SolventaApp
import co.solventa.mobile.core.designsystem.SolventaTheme
import co.solventa.mobile.feature.auth.AuthViewModel
import co.solventa.mobile.feature.claim.ClaimStore
import co.solventa.mobile.feature.claim.ClaimViewModel
import co.solventa.mobile.feature.claim.MobileClaimApi
import co.solventa.mobile.feature.evidence.MobileEvidenceApi
import co.solventa.mobile.feature.policy.*
import java.io.File
import java.time.LocalDate
import java.util.UUID
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private val syntheticPolicy = Policy("be794f6e-e01d-4d2f-ad25-c9bf118c117a",
    PolicyProduct.AUTO, LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"),
    PolicyStatus.ACTIVE, true, "POL-20260101-BE794F6E", "Daños y hurto")

@ClaimDeviceE2e
@RunWith(AndroidJUnit4::class)
class PolicyDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun click(id: Int) = compose.onNode(hasText(context.getString(id)) and hasClickAction()).performScrollTo().performClick()

    @Test fun accountToPolicyToSelectedReportAndBackPreservesDraftWithoutSending() {
        check(BuildConfig.APPLICATION_ID == "co.solventa.mobile.pf2.claimtest")
        val directory = File(context.noBackupFilesDir, "sol189-ui-${UUID.randomUUID()}").apply { check(mkdir()) }
        val application = object : Application() {
            override fun getNoBackupFilesDir() = directory
            override fun getCacheDir() = directory
        }
        val actor = UUID.randomUUID().toString()
        val repository = syntheticAuth(actor)
        val models = ViewModelStore()
        try {
            MockWebServer().use { server ->
                var mutations = 0
                val data = JSONObject().put("id", syntheticPolicy.id)
                    .put("producto", JSONObject().put("tipo", "automotive").put("nombre", "Seguro de Auto"))
                    .put("vigencia", JSONObject().put("inicio", "2026-01-01").put("fin", "2026-12-31"))
                    .put("estado", "vigente").put("simulada", true)
                    .put("numero", syntheticPolicy.number)
                    .put("coberturaPrincipal", JSONObject().put("codigo", "danos_hurto").put("nombre", syntheticPolicy.mainCoverage))
                val detail = JSONObject(data.toString()).put("suscripcionId", "b77d84cb-6ed8-490b-bcdd-09d147a79e33")
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.method != "GET") { mutations++; return MockResponse().setResponseCode(500) }
                        return when (request.path) {
                            "/movil/v1/polizas" -> MockResponse().setBody("""{"polizas":[$data]}""")
                            "/movil/v1/polizas/${syntheticPolicy.id}" -> MockResponse().setBody(detail.toString())
                            else -> MockResponse().setResponseCode(404)
                        }
                    }
                }
                val http = MobileEvidenceApi(repository, server.url("/movil/v1").toString())
                val auth = AuthViewModel(repository)
                val policies = PolicyViewModel(MobilePolicyApi(http))
                val claims = ClaimViewModel(application, MobileClaimApi(http))
                compose.runOnUiThread {
                    models.put("auth", auth); models.put("policies", policies); models.put("claims", claims)
                    auth.restore()
                }
                compose.setContent { SolventaApp(auth = auth, policies = policies, claims = claims, darkTheme = false) }
                compose.waitUntil(15_000) { auth.state.value.user != null }
                click(R.string.policy_title)
                compose.waitUntil(15_000) { policies.state.value.loaded }
                click(R.string.policy_view_detail)
                compose.waitUntil(15_000) { policies.state.value.detail != null }
                click(R.string.claim_title)
                compose.waitUntil(15_000) { claims.state.value.open && !claims.state.value.busy }
                assertEquals(syntheticPolicy.id, claims.state.value.draft.policyId)
                compose.onNodeWithText(context.getString(R.string.claim_description)).performScrollTo()
                    .performTextInput("Borrador sintético que debe conservarse.")
                val draft = claims.state.value.draft
                compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
                compose.waitUntil(15_000) { policies.state.value.detail != null }
                click(R.string.claim_title)
                compose.waitUntil(15_000) { claims.state.value.open && !claims.state.value.busy }
                assertEquals(draft, claims.state.value.draft)
                assertEquals(0, mutations)
                ClaimStore(directory).use { assertTrue(it.list(actor).isEmpty()) }
                compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
                compose.waitUntil(15_000) { policies.state.value.detail != null }
                click(R.string.policy_back_list)
                compose.waitUntil(15_000) { policies.state.value.loaded }
                click(R.string.policy_back_home)
                compose.onNodeWithContentDescription(context.getString(R.string.account_title)).performClick()
                click(R.string.sign_out)
                compose.waitUntil(15_000) { auth.state.value.user == null }
                assertNull(policies.state.value.actor)
                assertNull(policies.state.value.detail)
                assertEquals("", claims.state.value.draft.description)
            }
        } finally {
            compose.runOnUiThread { models.clear() }
            check(directory.deleteRecursively())
        }
    }

    @Test fun syntheticListAndDetailRenderInBothThemesAndLargeTextWithoutChangingDeviceSettings() {
        check(BuildConfig.APPLICATION_ID == "co.solventa.mobile.pf2.claimtest")
        val dark = mutableStateOf(false)
        val scale = mutableStateOf(1f)
        val detail = mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale.value)) {
                SolventaTheme(dark.value) {
                    PolicyScreen(PolicyState(loaded = true, policies = listOf(syntheticPolicy),
                        selectedId = if (detail.value) syntheticPolicy.id else null,
                        detail = if (detail.value) syntheticPolicy else null), {}, {}, {}, {})
                }
            }
        }
        for (theme in listOf(false, true)) for (fontScale in listOf(1f, 2f)) for (selected in listOf(false, true)) {
            compose.runOnIdle { dark.value = theme; scale.value = fontScale; detail.value = selected }
            val name = "${if (selected) "detail" else "list"}-${if (theme) "dark" else "light"}-$fontScale"
            compose.onNodeWithText(context.getString(if (selected) R.string.policy_detail_title else R.string.policy_title))
                .performScrollTo().assertIsDisplayed()
            capture(name)
            compose.onNode(hasText(context.getString(if (selected) R.string.claim_title else R.string.policy_view_detail))
                and hasClickAction()).performScrollTo().assertHeightIsAtLeast(48.dp)
            capture("$name-action")
            compose.onNodeWithText(context.getString(R.string.policy_simulated)).performScrollTo().assertIsDisplayed()
        }
    }

    private fun capture(name: String) = compose.runOnIdle {
        val view = compose.activity.window.decorView
        val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(image))
            val folder = File(context.noBackupFilesDir, "sol189-synthetic-renders").apply { mkdirs() }
            File(folder, "$name.png").outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { image.recycle() }
    }
}
