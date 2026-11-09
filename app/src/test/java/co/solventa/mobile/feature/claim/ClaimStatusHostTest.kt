package co.solventa.mobile.feature.claim

import android.app.Application
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
            assertTrue(compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
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
