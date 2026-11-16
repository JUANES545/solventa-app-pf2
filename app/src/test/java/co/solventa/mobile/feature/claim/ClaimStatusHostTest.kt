package co.solventa.mobile.feature.claim

import android.app.Application
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.*
import co.solventa.mobile.R
import co.solventa.mobile.core.designsystem.SolventaTheme
import co.solventa.mobile.core.ui.SolventaPage
import co.solventa.mobile.feature.evidence.EvidenceScope
import co.solventa.mobile.feature.evidence.EvidenceViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import co.solventa.mobile.feature.evidence.MobileEvidenceApi
import co.solventa.mobile.feature.evidence.evidenceAuth
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ClaimStatusHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun confirmedCaseAttachmentCannotBeDeletedWithoutServerSupport() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            val app = RuntimeEnvironment.getApplication()
            val model = EvidenceViewModel(app, MobileEvidenceApi(evidenceAuth(), server.url("/movil/v1").toString()))
            val scope = EvidenceScope("actor-a", statusClaim.id)
            val remote = java.util.UUID.randomUUID().toString()
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(
                """{"maxCantidad":7,"maxChunkBytes":1024,"maxVideoSeconds":45,"formatos":[{"mimeType":"image/jpeg","maxBytes":1234}]}"""))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"evidencias":[{"id":"$remote"}]}"""))
            compose.setContent {
                SolventaTheme(false) { SolventaPage { ClaimCaseAttachments(model, scope, statusClaim) } }
            }
            compose.waitUntil(10_000) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                model.state.value.actorId == scope.actorId && !model.state.value.busy
            }
            assertNull(model.state.value.error)
            assertEquals(scope, model.state.value.scope)
            compose.onNodeWithTag("evidence-tile-$remote").performScrollTo().performClick()
            compose.onNodeWithText(app.getString(R.string.evidence_gallery_remove)).assertDoesNotExist()
            compose.onNodeWithText(app.getString(R.string.evidence_gallery_file_unavailable)).assertExists()
            compose.onNodeWithText(app.getString(R.string.evidence_gallery_close)).performScrollTo().performClick()
            assertEquals(2, server.requestCount)
            compose.onNodeWithTag("evidence-tile-$remote").performScrollTo().assertIsDisplayed()
            assertEquals(setOf(remote), model.state.value.confirmedRemoteIds)
            assertNull(model.state.value.error)
            repeat(2) {
                assertEquals("GET", server.takeRequest().method)
            }
            androidx.lifecycle.ViewModelStore().apply { put("case-evidence", model); clear() }
        }
    }

    @Test fun lifecycleBackAndDisposalKeepTheOriginalWindowPrivacy() {
        val app = RuntimeEnvironment.getApplication()
        val model = ClaimStatusViewModel(MobileClaimApi(MobileEvidenceApi(evidenceAuth(), "http://127.0.0.1:1")))
        val visible = mutableStateOf(true)
        val useApplication = mutableStateOf(false)
        val owner = object : LifecycleOwner { override val lifecycle = LifecycleRegistry(this) }
        compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            if (visible.value) {
                CompositionLocalProvider(LocalLifecycleOwner provides owner,
                    LocalContext provides if (useApplication.value) app else compose.activity) {
                    ClaimStatusHost(model)
                }
            }
        }
        compose.runOnIdle {
            assertEquals(!co.solventa.mobile.BuildConfig.SCREEN_CAPTURE_ALLOWED,
                compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            owner.lifecycle.currentState = Lifecycle.State.CREATED
        }
        compose.runOnIdle {
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
            compose.activity.onBackPressedDispatcher.onBackPressed()
            assertFalse(model.state.value.open)
            visible.value = false
        }
        compose.runOnIdle {
            assertEquals(0, compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
            compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            visible.value = true
        }
        compose.runOnIdle { visible.value = false }
        compose.runOnIdle {
            assertTrue(compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            useApplication.value = true
            visible.value = true
        }
        compose.runOnIdle { visible.value = false }
    }
}
