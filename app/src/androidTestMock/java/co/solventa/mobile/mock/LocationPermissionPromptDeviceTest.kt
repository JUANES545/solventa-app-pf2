package co.solventa.mobile.mock

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import co.solventa.mobile.BuildConfig
import co.solventa.mobile.MainActivity
import co.solventa.mobile.R
import co.solventa.mobile.core.designsystem.SolventaTheme
import co.solventa.mobile.espresso.localBackendUrl
import co.solventa.mobile.feature.assistance.AssistanceViewModel
import co.solventa.mobile.feature.auth.AuthRepository
import co.solventa.mobile.feature.claim.ClaimViewModel
import co.solventa.mobile.feature.evidence.EvidenceKind
import co.solventa.mobile.feature.evidence.EvidencePolicy
import co.solventa.mobile.feature.evidence.EvidenceScope
import co.solventa.mobile.feature.evidence.EvidenceScreen
import co.solventa.mobile.feature.evidence.EvidenceStep
import co.solventa.mobile.feature.evidence.EvidenceViewModel
import co.solventa.mobile.feature.evidence.MobileEvidenceApi
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID

@HiltAndroidTest
class LocationPermissionPromptDeviceTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    @javax.inject.Inject lateinit var auth: AuthRepository
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun prepare() {
        hilt.inject()
        check(BuildConfig.APPLICATION_ID == "co.solventa.mobile.pf2.mock")
        check(localBackendUrl() == BuildConfig.AUTH_BASE_URL)
        assertLocationDenied()
        runBlocking { auth.login(MockBackend.EMAIL, MockBackend.PASSWORD) }
    }

    @Test fun claimRequestsLocationOnlyAfterExplicitTap() {
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            lateinit var model: ClaimViewModel
            activity.onActivity { model = ViewModelProvider(it)[ClaimViewModel::class.java] }
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText(context.getString(R.string.claim_title)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(context.getString(R.string.claim_title)).performScrollTo().performClick()
            var revision = -1L
            var stableSince = SystemClock.elapsedRealtime()
            compose.waitUntil(15_000) {
                val state = model.state.value
                if (state.locationRevision != revision || state.busy) {
                    revision = state.locationRevision
                    stableSince = SystemClock.elapsedRealtime()
                }
                state.open && state.policies.isNotEmpty() && !state.busy &&
                    SystemClock.elapsedRealtime() - stableSince >= 500
            }
            assertSystemPrompt {
                compose.onNodeWithTag("claim-current-location").performScrollTo().performClick()
            }
        }
        assertLocationDenied()
    }

    @Test fun assistanceRequestsLocationOnlyAfterExplicitTap() {
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            lateinit var model: AssistanceViewModel
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText(context.getString(R.string.claim_title)).fetchSemanticsNodes().isNotEmpty()
            }
            activity.onActivity {
                model = ViewModelProvider(it)[AssistanceViewModel::class.java]
                model.show(MockBackend.ACTOR, MockBackend.CLAIM)
            }
            compose.waitUntil(15_000) {
                compose.onAllNodesWithTag("assistance-use-gps").fetchSemanticsNodes().isNotEmpty()
            }
            assertNull(model.state.value.location)
            assertSystemPrompt {
                compose.onNodeWithTag("assistance-use-gps").performScrollTo().performClick()
            }
            assertNull(model.state.value.location)
        }
        assertLocationDenied()
    }

    @Test fun evidenceRequestsLocationOnlyAfterExplicitChoice() {
        val model = EvidenceViewModel(context.applicationContext as Application,
            MobileEvidenceApi(auth, BuildConfig.AUTH_BASE_URL))
        val store = ViewModelStore().apply { put("location-prompt-evidence", model) }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        try {
            instrumentation.runOnMainSync {
                model.open(EvidenceScope(MockBackend.ACTOR, UUID.randomUUID().toString()),
                    EvidencePolicy(4, mapOf(EvidenceKind.PHOTO to 1_000_000L), 0), emptySet())
            }
            await { !model.state.value.busy }
            lateinit var draft: co.solventa.mobile.feature.evidence.EvidenceDraft
            instrumentation.runOnMainSync {
                model.camera(EvidenceKind.PHOTO)
                draft = checkNotNull(model.beginCapture())
            }
            val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
            try {
                draft.file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            } finally { bitmap.recycle() }
            instrumentation.runOnMainSync { model.captured(draft) }
            await { !model.state.value.busy && model.state.value.step == EvidenceStep.REVIEW }
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                activity.onActivity {
                    it.setContent { SolventaTheme(false) { EvidenceScreen(model, model::close) } }
                }
                compose.onNodeWithContentDescription(context.getString(R.string.evidence_location))
                    .performScrollTo().assertIsOff()
                assertSystemPrompt {
                    compose.onNodeWithContentDescription(context.getString(R.string.evidence_location))
                        .performScrollTo().performClick()
                }
                assertFalse(model.state.value.locationEnabled)
                assertNull(model.state.value.location)
            }
        } finally {
            instrumentation.runOnMainSync { model.close(); store.clear() }
        }
        assertLocationDenied()
    }

    private fun assertSystemPrompt(action: () -> Unit) {
        assertLocationDenied()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        assertFalse(automation.rootInActiveWindow?.packageName?.toString()?.contains("permissioncontroller") == true)
        action()
        var shown = false
        try {
            val observed = mutableSetOf<String>()
            try {
                compose.waitUntil(10_000) {
                    val root = automation.rootInActiveWindow ?: return@waitUntil false
                    val packageName = root.packageName?.toString().orEmpty()
                    observed += packageName
                    packageName.contains("permissioncontroller") &&
                        (root.findAccessibilityNodeInfosByText("ubicación").isNotEmpty() ||
                            root.findAccessibilityNodeInfosByText("location").isNotEmpty())
                }
            } catch (_: ComposeTimeoutException) {
                fail("Android did not show a location permission prompt: windows=$observed")
            }
            shown = true
        } finally {
            if (shown || automation.rootInActiveWindow?.packageName?.toString()?.contains("permissioncontroller") == true) {
                check(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            }
        }
        assertLocationDenied()
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(25)
        assertTrue("The synthetic evidence did not reach the expected state", condition())
    }

    private fun assertLocationDenied() {
        for (permission in listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)) {
            assertEquals(PackageManager.PERMISSION_DENIED, ContextCompat.checkSelfPermission(context, permission))
        }
    }
}
