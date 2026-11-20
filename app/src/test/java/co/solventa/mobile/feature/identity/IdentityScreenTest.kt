package co.solventa.mobile.feature.identity

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import co.solventa.mobile.R
import co.solventa.mobile.core.designsystem.SolventaTheme
import kotlinx.coroutines.Dispatchers
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
class IdentityScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = RuntimeEnvironment.getApplication()
    private fun text(id: Int) = app.getString(id)

    @Test fun syntheticDisclosureAndAccessibleActionsRemainVisibleInBothThemesAndLargeText() {
        val dark = mutableStateOf(false)
        val scale = mutableStateOf(1f)
        var sends = 0
        var backs = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale.value)) {
                SolventaTheme(dark.value) {
                    IdentityScreen(IdentityState(verification = pendingIdentity), { sends++ }, {}, { backs++ })
                }
            }
        }
        for (theme in listOf(false, true)) {
            for (fontScale in listOf(1f, 2f)) {
                compose.runOnIdle { dark.value = theme; scale.value = fontScale }
                compose.onNodeWithText(text(R.string.identity_title)).performScrollTo().assertIsDisplayed()
                    .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
                compose.onNodeWithText(text(R.string.identity_disclaimer)).performScrollTo().assertIsDisplayed()
                compose.onNodeWithText(text(R.string.identity_fixtures)).performScrollTo().assertIsDisplayed()
                compose.onNodeWithText(text(R.string.identity_pending)).performScrollTo().assertIsDisplayed()
                compose.onNodeWithText(text(R.string.identity_submit)).performScrollTo()
                    .assertHeightIsAtLeast(48.dp).performClick()
                compose.onNodeWithText(text(R.string.identity_back)).performScrollTo().performClick()
            }
        }
        assertEquals(4, sends)
        assertEquals(4, backs)
    }

    @Test fun rejectedRetryBothReasonsAndTerminalStatesDoNotInventAdditionalAttempts() {
        val state = mutableStateOf(IdentityState(verification = rejectedIdentity))
        var sends = 0
        compose.setContent { SolventaTheme { IdentityScreen(state.value, { sends++ }, {}, {}) } }
        compose.onNodeWithText(text(R.string.identity_rejected)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.identity_document_rejected)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.identity_retry)).performScrollTo().performClick()
        compose.runOnIdle { state.value = state.value.copy(verification =
            rejectedIdentity.copy(attempts = 2, reason = IdentityReason.LIVENESS)) }
        compose.onNodeWithText(text(R.string.identity_liveness_rejected)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.identity_retry)).performScrollTo().performClick()
        compose.runOnIdle { state.value = state.value.copy(verification =
            rejectedIdentity.copy(attempts = 3, retryAllowed = false)) }
        compose.onNodeWithText(app.getString(R.string.identity_attempts, 3, 3)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.identity_exhausted)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.identity_retry)).assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(verification = approvedIdentity) }
        compose.onNodeWithText(text(R.string.identity_approved)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.identity_retry)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.identity_submit)).assertDoesNotExist()
        assertEquals(2, sends)
    }

    @Test fun busyDisabledAndOtherErrorsNeverDisplayStaleApprovalOrAnEnabledSubmit() {
        val state = mutableStateOf(IdentityState(busy = true, verification = approvedIdentity))
        var refreshes = 0
        compose.setContent { SolventaTheme { IdentityScreen(state.value, {}, { refreshes++ }, {}) } }
        compose.onNodeWithText(text(R.string.identity_loading)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.identity_refresh)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.identity_approved)).assertDoesNotExist()
        for (error in IdentityError.entries) {
            compose.runOnIdle { state.value = state.value.copy(busy = false, error = error) }
            compose.onNodeWithText(text(identityErrorText(error))).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(text(R.string.identity_approved)).assertDoesNotExist()
            compose.onNodeWithText(text(R.string.identity_submit)).assertDoesNotExist()
            compose.onNodeWithText(text(R.string.identity_refresh)).performScrollTo().performClick()
        }
        assertEquals(IdentityError.entries.size, refreshes)
    }

    @Test fun hostHidesResultOnStopAndConsultsServerAgainOnResume() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle get() = registry
        }
        var current = pendingIdentity
        var reads = 0
        val api = object : IdentityApi {
            override suspend fun start(actor: String) = current
            override suspend fun current(actor: String, id: String): IdentityVerification {
                reads++
                return current
            }
            override suspend fun submit(actor: String, id: String, attemptId: String) = current
        }
        val model = IdentityViewModel(api, MemoryIdentityAttempts(), Dispatchers.Unconfined)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                SolventaTheme { IdentityHost(model) }
            }
        }
        compose.runOnIdle { model.show("actor-a") }
        compose.onNodeWithText(text(R.string.identity_pending)).performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.CREATED
            assertNull(model.state.value.verification)
            current = approvedIdentity
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.onNodeWithText(text(R.string.identity_approved)).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, reads) }
    }
}
