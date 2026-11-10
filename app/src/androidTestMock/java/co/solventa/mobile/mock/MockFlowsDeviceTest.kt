package co.solventa.mobile.mock

import android.content.Intent
import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso
import co.solventa.mobile.BuildConfig
import co.solventa.mobile.R
import co.solventa.mobile.espresso.localBackendUrl
import co.solventa.mobile.feature.auth.*
import co.solventa.mobile.feature.claim.MobileClaimApi
import co.solventa.mobile.feature.claim.ClaimViewModel
import co.solventa.mobile.MainActivity
import androidx.lifecycle.ViewModelProvider
import co.solventa.mobile.feature.evidence.*
import co.solventa.mobile.feature.notification.MobileNotificationApi
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class MockFlowsDeviceTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @javax.inject.Inject lateinit var auth: AuthRepository

    private fun capture(name: String) {
        android.os.SystemClock.sleep(500)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        checkNotNull(bitmap).useBitmap {
            java.io.File(context.filesDir, name).outputStream().use { out ->
                check(it.compress(Bitmap.CompressFormat.PNG, 100, out))
            }
        }
    }

    private inline fun Bitmap.useBitmap(block: (Bitmap) -> Unit) {
        try { block(this) } finally { recycle() }
    }

    private fun ready(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.waitUntil(15_000) {
            compose.onAllNodes(matcher and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        return compose.onNode(matcher and isEnabled())
    }

    @Before fun prepare() {
        hilt.inject()
        check(BuildConfig.APPLICATION_ID == "co.solventa.mobile.pf2.mock")
        check(localBackendUrl() == BuildConfig.AUTH_BASE_URL)
        MockInitializer.backend.choose(MockScenario.DATA)
    }

    @Test fun installedAppUsesOnlyLocalFixturesForListsEmptyErrorsAndRecovery() = runBlocking {
        val login = MobileAuthApi(BuildConfig.AUTH_BASE_URL)
        var stored: AuthGrant? = login.login(MockBackend.EMAIL, MockBackend.PASSWORD)
        val auth = AuthRepository(login, object : AuthStore {
            override fun read() = stored
            override fun write(grant: AuthGrant) { stored = grant }
            override fun clear() { stored = null }
        })
        val api = MobileEvidenceApi(auth, BuildConfig.AUTH_BASE_URL)
        assertTrue(api.claims(MockBackend.ACTOR).isNotEmpty())
        assertTrue(MobileClaimApi(api).policies(MockBackend.ACTOR).isNotEmpty())
        assertTrue(MobileNotificationApi(auth, BuildConfig.AUTH_BASE_URL).notifications(MockBackend.ACTOR).isNotEmpty())
        MockInitializer.backend.choose(MockScenario.EMPTY)
        assertTrue(api.claims(MockBackend.ACTOR).isEmpty())
        MockInitializer.backend.choose(MockScenario.SERVICE)
        try { api.claims(MockBackend.ACTOR); fail("503 must remain an error") }
        catch (error: EvidenceHttpProblem) { assertEquals(503, error.status) }
        MockInitializer.backend.choose(MockScenario.REFRESH)
        assertTrue(api.claims(MockBackend.ACTOR).isNotEmpty())
        MockInitializer.backend.choose(MockScenario.DATA)
    }

    @Test fun panelLaunchesTheRealLoginAndHome() {
        ActivityScenario.launch<MockControlActivity>(Intent(context, MockControlActivity::class.java)).use {
            compose.onNodeWithText("Solventa · Flujos simulados").assertIsDisplayed()
            compose.onNodeWithText("Abrir Solventa").performScrollTo().performClick()
            val email = context.getString(R.string.email)
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasContentDescription(email) and isEnabled()).fetchSemanticsNodes().isNotEmpty() ||
                    compose.onAllNodesWithText("Mis siniestros").fetchSemanticsNodes().isNotEmpty()
            }
            if (compose.onAllNodes(hasContentDescription(email) and isEnabled()).fetchSemanticsNodes().isNotEmpty()) {
                ready(hasContentDescription(email)).performScrollTo().performTextReplacement(MockBackend.EMAIL)
                ready(hasContentDescription(context.getString(R.string.password))).performScrollTo().performTextReplacement(MockBackend.PASSWORD)
                Espresso.closeSoftKeyboard()
                compose.onNodeWithText(context.getString(R.string.sign_in)).performScrollTo().performClick()
            }
            try {
                compose.waitUntil(10_000) { compose.onAllNodesWithText("Mis siniestros").fetchSemanticsNodes().isNotEmpty() }
            } catch (error: ComposeTimeoutException) {
                throw AssertionError(compose.onRoot().printToString(), error)
            }
            compose.onNodeWithText("Mis siniestros").assertIsDisplayed()
            compose.onNodeWithText("Mis siniestros").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Choque").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Choque").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Recibimos tu reporte.").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Recibimos tu reporte.").performScrollTo().assertIsDisplayed()
            capture("status-detail.png")
            Espresso.pressBack()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("claim-${MockBackend.CLAIM}").fetchSemanticsNodes().isNotEmpty() }
            capture("status-list.png")
            Espresso.pressBack()
            compose.onNodeWithText(context.getString(R.string.home_evidence_title)).performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("claim-${MockBackend.CLAIM}").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("claim-${MockBackend.CLAIM}").performScrollTo().performClick()
            compose.waitForIdle()
            capture("gallery-mosaic.png")
            val choose = context.getString(R.string.evidence_gallery_photo)
            compose.waitUntil(10_000) { compose.onAllNodesWithText(choose).fetchSemanticsNodes().isNotEmpty() }
            capture("gallery-mosaic.png")
            compose.onNodeWithTag("evidence-add-photo").performScrollTo().performClick()
            compose.onNodeWithTag("evidence-source-file").assertIsDisplayed()
            compose.onNodeWithTag("evidence-source-camera").assertIsDisplayed()
            capture("gallery-source.png")
            compose.onNodeWithTag("evidence-source-file").performClick()
            android.os.SystemClock.sleep(700)
            check(InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK,
            ))
            ready(hasTestTag("evidence-add-photo")).performScrollTo().assertIsEnabled()
            val photoTile = SemanticsMatcher("local photo tile") { node ->
                node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)
                    ?.startsWith("evidence-tile-") == true
            } and hasContentDescription(context.getString(R.string.evidence_kind_photo))
            compose.onAllNodes(photoTile)[0].performScrollTo().performClick()
            compose.onNodeWithText(context.getString(R.string.evidence_gallery_details)).assertDoesNotExist()
            compose.onNodeWithText("SHA", substring = true).assertDoesNotExist()
            compose.onNodeWithText("image/jpeg", substring = true).assertDoesNotExist()
            capture("gallery-details-polished.png")
            compose.onNodeWithText(context.getString(R.string.evidence_gallery_close)).performScrollTo().performClick()
            // This device keeps CAMERA denied by the user. Do not accept a system permission prompt.
            assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,
                androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA))
            for (kind in listOf("photo", "video", "document")) {
                ready(hasTestTag("evidence-add-$kind")).performScrollTo().performClick()
                compose.onNodeWithTag("evidence-source-camera").performClick()
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithText(context.getString(R.string.camera_open_settings)).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText(context.getString(R.string.camera_permission_settings)).assertIsDisplayed()
                compose.onNodeWithText(context.getString(R.string.evidence_error_camera)).assertDoesNotExist()
                capture("camera-permission-$kind.png")
                compose.onNodeWithText(context.getString(R.string.camera_permission_close)).performClick()
                ready(hasTestTag("evidence-add-$kind")).performScrollTo().assertIsEnabled()
            }
            Espresso.pressBack()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("Mis siniestros").fetchSemanticsNodes().isNotEmpty()
            }
            val expectedNotice = runBlocking {
                val discarded = co.solventa.mobile.feature.notification.AndroidNotificationStore(context)
                    .discarded(MockBackend.ACTOR)
                MobileNotificationApi(auth, BuildConfig.AUTH_BASE_URL).notifications(MockBackend.ACTOR)
                    .first { it.id !in discarded }.title
            }
            compose.onNodeWithContentDescription(context.getString(R.string.home_notifications))
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
            try {
                compose.waitUntil(10_000) { compose.onAllNodesWithText(expectedNotice).fetchSemanticsNodes().isNotEmpty() }
            } catch (error: ComposeTimeoutException) {
                throw AssertionError(compose.onRoot().printToString(maxDepth = 20), error)
            }
            compose.onNodeWithText(expectedNotice).performScrollTo().assertIsDisplayed()
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasText("Eliminar aviso") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            capture("notification-inbox.png")
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun claimFormUsesCalendarClockSampleDescriptionAndRealLocalSubmission() {
        runBlocking { auth.login(MockBackend.EMAIL, MockBackend.PASSWORD) }
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            lateinit var model: ClaimViewModel
            activity.onActivity { model = ViewModelProvider(it)[ClaimViewModel::class.java] }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText(context.getString(R.string.claim_title)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(context.getString(R.string.claim_title)).performScrollTo().performClick()
            compose.waitUntil(10_000) { model.state.value.policies.isNotEmpty() && !model.state.value.busy }
            assertEquals(BuildConfig.CLAIM_INITIAL_DATE, model.state.value.draft.date)
            val previousDelay = MockInitializer.backend.delayMillis
            try {
                MockInitializer.backend.delayMillis = 2_500
                compose.runOnIdle { model.refreshPolicies() }
                compose.waitUntil(10_000) { model.state.value.loadingPolicies }
                compose.onNodeWithTag("content-refresh-indicator").assertIsDisplayed()
                compose.onNodeWithTag("page-refresh-indicator").assertDoesNotExist()
                capture("claim-automatic-refresh.png")
                compose.waitUntil(15_000) { !model.state.value.busy }
                val before = compose.onNodeWithTag("claim-policy-${MockBackend.POLICY}").getUnclippedBoundsInRoot()
                compose.onNodeWithTag("page-refresh").performCustomAccessibilityActionWithLabel("Actualizar")
                compose.waitUntil(10_000) { model.state.value.loadingPolicies }
                compose.onNodeWithTag("page-refresh-indicator").assertIsDisplayed()
                compose.onNodeWithTag("content-refresh-indicator").assertDoesNotExist()
                assertEquals(before, compose.onNodeWithTag("claim-policy-${MockBackend.POLICY}").getUnclippedBoundsInRoot())
                capture("claim-gesture-refresh.png")
                compose.waitUntil(15_000) { !model.state.value.busy }
            } finally { MockInitializer.backend.delayMillis = previousDelay }
            capture("claim-form.png")
            ready(hasTestTag("claim-policy-${MockBackend.POLICY}")).performScrollTo().performClick()
            compose.waitUntil(10_000) { model.state.value.draft.policyId == MockBackend.POLICY }
            ready(hasText(context.getString(R.string.claim_type))).performScrollTo().performTextReplacement("Choque de prueba")
            Espresso.closeSoftKeyboard()
            ready(hasText(context.getString(R.string.claim_date))).performScrollTo()
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
            val day = java.time.LocalDate.now().dayOfMonth.toString()
            val dateNode = SemanticsMatcher("calendar day $day") { node ->
                node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)
                    ?.any { Regex("\\b$day\\b").containsMatchIn(it.text) } == true
            }
            try {
                compose.waitUntil(10_000) {
                    compose.onAllNodes(dateNode and hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                }
            } catch (error: ComposeTimeoutException) {
                throw AssertionError("Draft: ${model.state.value.draft}; error: ${model.state.value.error}\n" +
                    compose.onAllNodes(isRoot(), useUnmergedTree = true).printToString(maxDepth = 20), error)
            }
            compose.onNode(dateNode and hasClickAction(), useUnmergedTree = true).performClick()
            ready(hasText(context.getString(R.string.ui_choose_confirm))).performClick()
            ready(hasText(context.getString(R.string.claim_time))).performScrollTo()
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
            compose.onNodeWithTag("time-picker").assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.ui_time_keyboard)).performScrollTo().performClick()
            ready(hasText(context.getString(R.string.ui_choose_confirm))).performClick()
            ready(hasTestTag("claim-current-location")).performScrollTo()
                .assertContentDescriptionEquals(context.getString(R.string.claim_use_location))
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
            compose.onNodeWithText(context.getString(R.string.claim_use_location)).assertDoesNotExist()
            val locationBefore = model.state.value.draft.place
            capture("claim-location-icon.png")
            for (permission in listOf(android.Manifest.permission.ACCESS_COARSE_LOCATION,
                android.Manifest.permission.ACCESS_FINE_LOCATION)) {
                assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,
                    androidx.core.content.ContextCompat.checkSelfPermission(context, permission))
            }
            compose.onNodeWithTag("claim-current-location").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText(context.getString(R.string.location_permission_open_settings)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(context.getString(R.string.location_permission_settings)).assertIsDisplayed()
            capture("claim-location-permanent-denial.png")
            compose.onNodeWithText(context.getString(R.string.ui_choose_cancel)).performClick()
            compose.runOnIdle { assertEquals(locationBefore, model.state.value.draft.place) }
            ready(hasText(context.getString(R.string.claim_place))).performScrollTo().performTextReplacement("Lugar de prueba")
            ready(hasText(context.getString(R.string.claim_description))).performScrollTo().performClick()
            compose.waitUntil {
                model.state.value.draft.description == context.getString(R.string.claim_sample_description)
            }
            Espresso.closeSoftKeyboard()
            ready(hasText(context.getString(R.string.claim_submit))).performScrollTo()
            capture("claim-form-bottom.png")
            ready(hasText(context.getString(R.string.claim_submit))).performClick()
            compose.waitUntil(15_000) {
                !model.state.value.busy && (!model.state.value.editing || model.state.value.error != null)
            }
            compose.runOnIdle {
                assertNull("Invalid form: ${model.state.value.draft}", model.state.value.error)
                assertNotNull(model.state.value.reports.last { it.payload.type == "Choque de prueba" }.receipt)
            }
            compose.onNodeWithText(context.getString(R.string.operational_report_sent)).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.claim_add_evidence)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.claim_status_view)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.claim_title)).performScrollTo()
            capture("claim-sent.png")
        }
    }

    @Test fun syntheticLocationLoadingUsesTheSameInlineControlWithoutRequestingDeviceLocation() {
        val loading = androidx.compose.runtime.mutableStateOf(false)
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            activity.onActivity {
                it.setContent {
                    co.solventa.mobile.core.designsystem.SolventaTheme(false) {
                        androidx.compose.material3.Surface {
                            androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier
                                .fillMaxWidth().padding(horizontal = 16.dp, vertical = 64.dp)) {
                                co.solventa.mobile.feature.claim.ClaimLocationInput(
                                    "Lugar sintético de prueba", true, loading.value,
                                    onPlaceChange = {}, onLocate = { loading.value = true })
                            }
                        }
                    }
                }
            }
            compose.onNodeWithTag("claim-current-location").assertIsEnabled()
            compose.mainClock.autoAdvance = false
            compose.onNodeWithTag("claim-current-location").performClick()
            compose.mainClock.advanceTimeBy(600)
            compose.onNodeWithTag("claim-current-location").assertIsNotEnabled()
                .assertContentDescriptionEquals(context.getString(R.string.claim_use_location))
                .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            compose.onNodeWithText(context.getString(R.string.claim_locating)).assertDoesNotExist()
            compose.onNodeWithText(context.getString(R.string.claim_use_location)).assertDoesNotExist()
            capture("claim-location-loading-synthetic.png")
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun selectedVideoIsReviewedConfirmedAndDeletedWithoutAffectingAnotherClaim() = runBlocking {
        auth.login(MockBackend.EMAIL, MockBackend.PASSWORD)
        val http = MobileEvidenceApi(auth, BuildConfig.AUTH_BASE_URL)
        val model = EvidenceViewModel(context.applicationContext as android.app.Application, http)
        val report = co.solventa.mobile.feature.claim.ClaimReport(
            java.util.UUID.randomUUID().toString(), MockBackend.ACTOR,
            co.solventa.mobile.feature.claim.ClaimPayload(MockBackend.POLICY, java.time.Instant.now().minusSeconds(60),
                "Prueba de vídeo", "Lugar de prueba", "Clip de tráfico de dominio público."),
        )
        val receipt = MobileClaimApi(http).send(report)
        val claim = http.claims(MockBackend.ACTOR).first { it.id == receipt.id }
        val video = checkNotNull(context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Video.Media._ID),
            "${MediaStore.Video.Media.DISPLAY_NAME} = ?", arrayOf("trafico.mp4"), null,
        )).use { cursor ->
            org.junit.Assume.assumeTrue("Clip de práctica opcional no preparado en este dispositivo.", cursor.moveToFirst())
            android.content.ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0))
        }
        fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
        fun await() {
            val deadline = android.os.SystemClock.elapsedRealtime() + 60_000
            while (model.state.value.busy && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(25)
            assertFalse("Evidence operation did not finish", model.state.value.busy)
            assertNull("Evidence operation failed", model.state.value.error)
        }
        try {
            onMain { model.showClaim(MockBackend.ACTOR, claim) }; await()
            val scope = checkNotNull(model.state.value.scope)
            onMain { model.importFile(video, EvidenceKind.VIDEO, scope, model.state.value.generation) }; await()
            assertEquals(EvidenceStep.REVIEW, model.state.value.step)
            assertNotNull(model.state.value.preview?.bitmap)
            onMain { model.accept(false) }; await()
            val item = model.state.value.items.single()
            onMain { model.retry() }; await()
            val confirmed = model.state.value.items.single()
            assertEquals(EvidenceStatus.CONFIRMED, confirmed.status)
            val other = EvidenceScope(MockBackend.ACTOR, MockBackend.CLAIM)
            val beforeOther = http.confirmedIds(other)
            EvidenceStore(context.noBackupFilesDir).use { store ->
                val file = store.file(store.list(scope).single())
                android.media.MediaExtractor().let { extractor ->
                    try {
                        extractor.setDataSource(file.path)
                        assertTrue((0 until extractor.trackCount).none {
                            extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                        })
                    } finally { extractor.release() }
                }
                onMain { model.remove(confirmed) }; await()
                assertFalse(file.exists())
                assertTrue(store.list(scope).isEmpty())
            }
            assertFalse(http.confirmedIds(scope).contains(confirmed.remoteId))
            assertEquals(beforeOther, http.confirmedIds(other))
            onMain { model.showClaim(MockBackend.ACTOR, claim) }; await()
            assertTrue(model.state.value.items.isEmpty())
            assertEquals(EvidenceKind.VIDEO, item.kind)
        } finally {
            onMain { model.close() }
            MockInitializer.backend.removeEmptyTestClaims(setOf(claim.id), emptySet())
        }
    }

    @Test fun selectedPhotoIsReviewedUploadedAndRestoredWithItsLocalImage() = runBlocking {
        val login = MobileAuthApi(BuildConfig.AUTH_BASE_URL)
        var stored: AuthGrant? = login.login(MockBackend.EMAIL, MockBackend.PASSWORD)
        val auth = AuthRepository(login, object : AuthStore {
            override fun read() = stored
            override fun write(grant: AuthGrant) { stored = grant }
            override fun clear() { stored = null }
        })
        val http = MobileEvidenceApi(auth, BuildConfig.AUTH_BASE_URL)
        val model = EvidenceViewModel(context.applicationContext as android.app.Application, http)
        val report = co.solventa.mobile.feature.claim.ClaimReport(
            java.util.UUID.randomUUID().toString(), MockBackend.ACTOR,
            co.solventa.mobile.feature.claim.ClaimPayload(MockBackend.POLICY, java.time.Instant.now().minusSeconds(60),
                "Prueba de fotografía", "Lugar de prueba", "Fotografía de práctica para comprobar carga y restauración."),
        )
        val resolver = context.contentResolver
        val uri = checkNotNull(resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DISPLAY_NAME} = ?", arrayOf("choque-frontal.jpg"), null,
        )).use { cursor ->
            org.junit.Assume.assumeTrue(
                "Prepare an app-owned choque-frontal.jpg in the demo device's MediaStore first",
                cursor.moveToFirst(),
            )
            android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0))
        }
        val receipt = MobileClaimApi(http).send(report)
        val claim = http.claims(MockBackend.ACTOR).first { it.id == receipt.id }
        var ownItemId: String? = null
        fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
        fun await() {
            val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
            while (model.state.value.busy && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(25)
            assertFalse("Evidence operation did not finish", model.state.value.busy)
            assertNull("Evidence operation failed", model.state.value.error)
        }
        try {
            onMain { model.showClaim(MockBackend.ACTOR, claim) }
            await()
            val scope = checkNotNull(model.state.value.scope)
            onMain { model.importPhoto(uri, scope, model.state.value.generation) }
            await()
            assertEquals(EvidenceStep.REVIEW, model.state.value.step)
            assertNotNull(model.state.value.preview?.bitmap)
            onMain { model.accept(false) }
            await()
            val item = model.state.value.items.last()
            ownItemId = item.id
            assertEquals(EvidenceStatus.PENDING, item.status)
            assertNull(item.location)
            onMain { model.retry() }
            await()
            val confirmed = model.state.value.items.first { it.id == item.id }
            assertEquals(EvidenceStatus.CONFIRMED, confirmed.status)
            assertTrue(http.confirmedIds(scope).contains(confirmed.remoteId))
            onMain { model.showClaim(MockBackend.ACTOR, claim) }
            await()
            EvidenceStore(context.noBackupFilesDir).use { store ->
                val restored = store.list(scope).first { it.id == item.id }
                assertEquals(confirmed.remoteId, restored.remoteId)
                val photo = android.graphics.BitmapFactory.decodeFile(store.file(restored).path)
                assertNotNull("Confirmed evidence must keep a usable local photo", photo)
                photo.recycle()
            }
        } finally {
            try {
                model.state.value.items.firstOrNull { it.id == ownItemId }?.let {
                    onMain { model.remove(it) }
                    await()
                    assertFalse(http.confirmedIds(it.scope).contains(it.remoteId))
                }
            } finally {
                onMain { model.close() }
                MockInitializer.backend.removeEmptyTestClaims(setOf(claim.id), emptySet())
            }
        }
    }
}
