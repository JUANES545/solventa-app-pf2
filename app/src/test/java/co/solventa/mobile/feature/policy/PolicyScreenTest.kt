package co.solventa.mobile.feature.policy

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import co.solventa.mobile.R
import co.solventa.mobile.core.designsystem.SolventaTheme
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PolicyScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = RuntimeEnvironment.getApplication()
    private fun text(id: Int) = app.getString(id)

    @Test fun contractNumberCoverageAndValidityAreVisibleInSummaryAndDetailForBothSimulationFlags() = runBlocking {
        val state = mutableStateOf(PolicyState(loaded = true))
        compose.setContent { SolventaTheme(false) { PolicyScreen(state.value, {}, {}, {}) } }
        MockWebServer().use { server ->
            val api = policyApi(server)
            for (simulated in listOf(true, false)) {
                server.enqueue(MockResponse().setBody(JSONObject().put("polizas",
                    JSONArray().put(contractPolicyJson(simulated, detail = false))).toString()))
                val listed = api.list("actor-a").single()
                server.enqueue(MockResponse().setBody(contractPolicyJson(simulated, detail = true).toString()))
                val detail = api.detail("actor-a", listed.id)
                for (selected in listOf(false, true)) {
                    compose.runOnIdle {
                        state.value = if (selected) PolicyState(loaded = true, selectedId = detail.id, detail = detail)
                        else PolicyState(loaded = true, policies = listOf(listed))
                    }
                    compose.onNodeWithText(text(R.string.policy_travel)).performScrollTo().assertIsDisplayed()
                    if (!selected) {
                        compose.onNodeWithText(app.getString(R.string.wallet_reference, listed.number.takeLast(4)))
                            .performScrollTo().assertIsDisplayed()
                        compose.onNodeWithText(listed.number, substring = true).assertDoesNotExist()
                        continue
                    }
                    compose.onNodeWithText(text(R.string.wallet_show_number)).performScrollTo().performClick()
                    compose.onNodeWithText(app.getString(R.string.policy_number, "POL-20261119-00000000"))
                        .performScrollTo().assertIsDisplayed()
                    compose.onNodeWithText(app.getString(R.string.policy_coverage, "Asistencia médica"))
                        .performScrollTo().assertIsDisplayed()
                    compose.onNodeWithText(app.getString(R.string.policy_validity, "19/11/2026", "29/11/2026"))
                        .performScrollTo().assertIsDisplayed()
                    if (simulated) compose.onNodeWithText(text(R.string.policy_simulated)).performScrollTo().assertIsDisplayed()
                    else compose.onNodeWithText(text(R.string.policy_simulated)).assertDoesNotExist()
                    compose.onNodeWithText(text(R.string.policy_not_available), substring = true).assertDoesNotExist()
                }
            }
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun loadingEmptyAndFailuresAreDistinctAndDoNotShowStaleData() {
        val state = mutableStateOf(PolicyState(busy = true, policies = listOf(policy)))
        var retries = 0
        compose.setContent { SolventaTheme(false) { PolicyScreen(state.value, {}, { retries++ }, {}) } }
        compose.onNodeWithContentDescription(text(R.string.policy_loading)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.policy_refresh)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.policy_empty)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.policy_auto)).assertDoesNotExist()
        for (error in PolicyError.entries) {
            compose.runOnIdle { state.value = state.value.copy(busy = false, error = error) }
            compose.onNodeWithText(text(policyErrorText(error))).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(text(R.string.policy_auto)).assertDoesNotExist()
            compose.onNodeWithTag("page-refresh").performCustomAccessibilityActionWithLabel("Actualizar")
        }
        compose.runOnIdle { state.value = PolicyState(loaded = true) }
        compose.onNodeWithText(text(R.string.policy_empty)).performScrollTo().assertIsDisplayed()
        assertEquals(5, retries)
    }

    @Test fun listAndDetailUseHonestFieldsThemesLargeTextAndFortyEightDpActions() {
        val dark = mutableStateOf(false)
        val scale = mutableStateOf(1f)
        val state = mutableStateOf(PolicyState(loaded = true, policies = listOf(policy)))
        var selected: Policy? = null
        var reported: Policy? = null
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale.value)) {
                SolventaTheme(dark.value) { PolicyScreen(state.value, { selected = it }, {}, {}, { reported = it }) }
            }
        }
        for (theme in listOf(false, true)) {
            for (fontScale in listOf(1f, 2f)) {
                compose.runOnIdle {
                    dark.value = theme
                    scale.value = fontScale
                    state.value = PolicyState(loaded = true, policies = listOf(policy))
                }
                compose.onNodeWithText(text(R.string.policy_title)).performScrollTo().assertIsDisplayed()
                    .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
                capture("list-${if (theme) "dark" else "light"}-$fontScale")
                compose.onNodeWithText(app.getString(R.string.wallet_reference, policy.number.takeLast(4)))
                    .performScrollTo().assertIsDisplayed()
                compose.onNodeWithText(policy.id).assertDoesNotExist()
                compose.onNode(hasText(text(policyProductText(policy.product))) and hasClickAction()).performScrollTo()
                    .assertHeightIsAtLeast(48.dp).performClick()
                assertEquals(policy, selected)
                compose.runOnIdle { state.value = PolicyState(loaded = true, selectedId = policy.id, detail = policy) }
                compose.onNodeWithText(text(R.string.policy_detail_title)).performScrollTo().assertIsDisplayed()
                capture("detail-${if (theme) "dark" else "light"}-$fontScale")
                compose.onNode(hasText(text(R.string.claim_title)) and hasClickAction()).performScrollTo()
                    .assertHeightIsAtLeast(48.dp).performClick()
                assertEquals(policy, reported)
                capture("detail-action-${if (theme) "dark" else "light"}-$fontScale")
                for (status in listOf(PolicyStatus.EXPIRED, PolicyStatus.SCHEDULED)) {
                    compose.runOnIdle { state.value = state.value.copy(detail = policy.copy(status = status)) }
                    compose.onNodeWithText(text(R.string.policy_report_unavailable)).performScrollTo().assertIsDisplayed()
                    compose.onNodeWithText(text(R.string.claim_title)).assertDoesNotExist()
                }
            }
        }
    }

    private fun capture(name: String) = compose.runOnIdle {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            val folder = File("build", "reports${File.separator}policy-synthetic-renders").apply { mkdirs() }
            File(folder, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
