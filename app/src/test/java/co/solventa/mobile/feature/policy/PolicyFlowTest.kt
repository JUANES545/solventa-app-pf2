package co.solventa.mobile.feature.policy

import android.app.Application
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import co.solventa.mobile.R
import co.solventa.mobile.SolventaApp
import co.solventa.mobile.feature.auth.AuthViewModel
import co.solventa.mobile.feature.claim.ClaimStore
import co.solventa.mobile.feature.claim.ClaimViewModel
import co.solventa.mobile.feature.claim.MobileClaimApi
import co.solventa.mobile.feature.evidence.MobileEvidenceApi
import co.solventa.mobile.feature.evidence.evidenceAuth
import java.util.UUID
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class PolicyFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = RuntimeEnvironment.getApplication()
    private fun click(id: Int) = compose.onNode(hasText(app.getString(id)) and hasClickAction()).performScrollTo().performClick()
    private fun await(condition: () -> Boolean) = compose.waitUntil(10_000) {
        shadowOf(Looper.getMainLooper()).idle()
        condition()
    }

    @Test fun accountPoliciesDetailSelectedReportAndBackKeepDraftWithoutPostingAndLogoutClearsData() {
        MockWebServer().use { server ->
            val other = policy.copy(id = UUID.randomUUID().toString(), product = PolicyProduct.TRAVEL)
            var mutations = 0
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method != "GET") { mutations++; return MockResponse().setResponseCode(500) }
                    return MockResponse().setBody(when (request.path) {
                        "/movil/v1/polizas" -> policyList(policy, other)
                        "/movil/v1/polizas/${policy.id}" -> policyJson().toString()
                        "/movil/v1/polizas/${other.id}" -> policyJson(other).toString()
                        else -> error("Unexpected navigation request: ${request.path}")
                    })
                }
            }
            val repository = evidenceAuth()
            val http = MobileEvidenceApi(repository, server.url("/movil/v1").toString())
            val auth = AuthViewModel(repository)
            val policies = PolicyViewModel(MobilePolicyApi(http))
            val claims = ClaimViewModel(app, MobileClaimApi(http))
            val models = ViewModelStore().apply { put("auth", auth); put("policies", policies); put("claims", claims) }
            try {
                compose.runOnUiThread { auth.restore() }
                compose.setContent { SolventaApp(auth = auth, policies = policies, claims = claims, darkTheme = false) }
                await { auth.state.value.user != null }
                click(R.string.policy_title)
                await { policies.state.value.loaded }
                compose.onAllNodesWithText(app.getString(R.string.policy_view_detail))[0].performScrollTo().performClick()
                await { policies.state.value.detail != null }
                compose.runOnIdle {
                    assertTrue(compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                }
                click(R.string.claim_title)
                await { claims.state.value.open && !claims.state.value.busy }
                assertEquals(policy.id, claims.state.value.draft.policyId)
                compose.onNodeWithText(app.getString(R.string.policy_back_detail)).performScrollTo().assertIsDisplayed()
                compose.onNodeWithText(app.getString(R.string.claim_description)).performScrollTo()
                    .performTextInput("Borrador conservado al volver a la póliza.")
                val saved = claims.state.value.draft
                compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
                await { policies.state.value.detail != null }
                assertEquals(saved, claims.state.value.draft)
                click(R.string.claim_title)
                await { claims.state.value.open && !claims.state.value.busy }
                assertEquals(saved, claims.state.value.draft)
                compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
                await { policies.state.value.detail != null }
                click(R.string.policy_back_list)
                await { policies.state.value.loaded && policies.state.value.selectedId == null }
                compose.onAllNodesWithText(app.getString(R.string.policy_view_detail))[1].performScrollTo().performClick()
                await { policies.state.value.detail?.id == other.id }
                click(R.string.claim_title)
                compose.onNodeWithText(app.getString(R.string.policy_draft_title)).assertIsDisplayed()
                compose.onNodeWithText(app.getString(R.string.policy_keep_detail)).performClick()
                assertEquals(saved, claims.state.value.draft)
                click(R.string.claim_title)
                compose.onNodeWithText(app.getString(R.string.policy_continue_draft)).performClick()
                await { claims.state.value.open }
                assertEquals(saved, claims.state.value.draft)
                assertEquals(0, mutations)
                ClaimStore(app.noBackupFilesDir).use { assertTrue(it.list("actor-a").isEmpty()) }
                compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
                await { policies.state.value.detail != null }
                click(R.string.policy_back_list)
                await { policies.state.value.loaded }
                click(R.string.policy_back_home)
                compose.onNodeWithContentDescription(app.getString(R.string.account_title)).performClick()
                click(R.string.sign_out)
                await { auth.state.value.user == null }
                assertNull(policies.state.value.actor)
                assertTrue(policies.state.value.policies.isEmpty())
                assertNull(policies.state.value.detail)
                assertNull(claims.state.value.actor)
                assertEquals("", claims.state.value.draft.description)
            } finally {
                compose.runOnUiThread { models.clear() }
            }
        }
    }
}
