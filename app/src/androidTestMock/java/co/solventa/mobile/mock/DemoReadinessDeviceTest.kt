package co.solventa.mobile.mock

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import co.solventa.mobile.BuildConfig
import co.solventa.mobile.MainActivity
import co.solventa.mobile.R
import co.solventa.mobile.espresso.localBackendUrl
import co.solventa.mobile.feature.auth.AuthRepository
import co.solventa.mobile.feature.claim.ClaimViewModel
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

@HiltAndroidTest
class DemoReadinessDeviceTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    @javax.inject.Inject lateinit var auth: AuthRepository
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun ready(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.waitUntil(15_000) {
            compose.onAllNodes(matcher and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        return compose.onAllNodes(matcher and isEnabled()).onFirst()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            File(context.filesDir, name).outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }

    @Test fun demoKeepsPermissionsPendingAndShowsTheConfiguredInitialDate() {
        hilt.inject()
        check(BuildConfig.APPLICATION_ID == "co.solventa.mobile.pf2.mock")
        check(localBackendUrl() == BuildConfig.AUTH_BASE_URL)
        val permissions = listOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)
        permissions.forEach { assertEquals(PackageManager.PERMISSION_DENIED, ContextCompat.checkSelfPermission(context, it)) }
        MockInitializer.backend.choose(MockScenario.DATA)
        runBlocking { auth.login(MockBackend.EMAIL, MockBackend.PASSWORD) }
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            lateinit var claims: ClaimViewModel
            activity.onActivity { claims = ViewModelProvider(it)[ClaimViewModel::class.java] }
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText(context.getString(R.string.claim_title)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(context.getString(R.string.claim_title)).performScrollTo().performClick()
            compose.waitUntil(15_000) { claims.state.value.open && !claims.state.value.busy }
            assertEquals("2026-11-03", claims.state.value.draft.date)
            assertEquals("", claims.state.value.draft.time)
            ready(hasText(context.getString(R.string.claim_date))).performScrollTo().assertIsDisplayed()
            capture("demo-initial-date.png")
            Espresso.pressBack()
            compose.onNodeWithContentDescription(context.getString(R.string.home_notifications))
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
            ready(hasText("Permitir avisos")).performScrollTo().assertIsDisplayed()
            ready(hasText("Ver detalle")).assertExists()
            compose.onNodeWithText("Avisos en este teléfono").assertDoesNotExist()
            compose.onNodeWithText("Avisos").assertDoesNotExist()
            capture("notification-permission-info.png")
            ready(hasText("Ver detalle")).performScrollTo().performClick()
            ready(hasText("Ocultar detalle")).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Abrir aviso").assertDoesNotExist()
            compose.onNodeWithText("Ver detalles").assertDoesNotExist()
            capture("notification-details-simple.png")
        }
        permissions.forEach { assertEquals(PackageManager.PERMISSION_DENIED, ContextCompat.checkSelfPermission(context, it)) }
    }
}
