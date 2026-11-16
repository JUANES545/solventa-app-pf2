package co.solventa.mobile.feature.policy

import android.app.Application
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.LocalLifecycleOwner
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class PolicyHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun stoppingClearsDataResumingRefreshesAndDisposalPreservesExistingSecureFlag() {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody(policyList())
            }
            val model = PolicyViewModel(policyApi(server))
            val owner = object : LifecycleOwner { override val lifecycle = LifecycleRegistry(this) }
            val visible = mutableStateOf(true)
            compose.runOnUiThread {
                owner.lifecycle.currentState = Lifecycle.State.RESUMED
                compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                model.show("actor-a")
            }
            compose.setContent {
                if (visible.value) CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    PolicyHost(model, null, {})
                }
            }
            fun awaitLoaded() = compose.waitUntil(10_000) {
                shadowOf(Looper.getMainLooper()).idle()
                model.state.value.loaded
            }
            try {
                awaitLoaded()
                val count = server.requestCount
                compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.CREATED }
                assertFalse(model.state.value.loaded)
                assertTrue(model.state.value.policies.isEmpty())
                compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
                awaitLoaded()
                assertTrue(server.requestCount > count)
                compose.runOnIdle { visible.value = false }
                compose.runOnIdle {
                    assertTrue(model.state.value.policies.isEmpty())
                    assertTrue(compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                }
            } finally {
                compose.runOnUiThread { ViewModelStore().apply { put("policy", model); clear() } }
            }
        }
    }
}
