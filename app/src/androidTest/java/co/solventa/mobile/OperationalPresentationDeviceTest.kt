package co.solventa.mobile

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import co.solventa.mobile.core.designsystem.SolventaTheme
import co.solventa.mobile.core.ui.SolventaPage
import co.solventa.mobile.feature.assistance.*
import co.solventa.mobile.feature.auth.*
import co.solventa.mobile.feature.claim.*
import co.solventa.mobile.feature.evidence.*
import co.solventa.mobile.feature.notification.*
import java.io.File
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OperationalPresentationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun screensInBothThemes() = captureScenes(1f)

    @Test fun compactViewportAtTwoHundredPercentKeepsActionsReachable() = captureScenes(2f)

    private enum class Scene(val fileName: String, val action: Int) {
        CLAIM("claim", R.string.claim_submit),
        CLAIM_LOADING("claim-loading", R.string.claim_submit),
        HISTORY("claim-history", R.string.evidence_entry),
        STATUS_LIST("status-list", R.string.claim_status_refresh),
        STATUS_LIST_LOADING("status-list-loading", R.string.claim_status_refresh),
        STATUS("status", R.string.claim_status_refresh),
        STATUS_LOADING("status-loading", R.string.claim_status_refresh),
        EVIDENCE_CLAIMS("evidence-claims", R.string.evidence_back),
        EVIDENCE_CLAIMS_LOADING("evidence-claims-loading", R.string.evidence_back),
        EVIDENCE("evidence", R.string.evidence_retry),
        EVIDENCE_REVIEW("evidence-review", R.string.evidence_attach),
        LOCATION("assistance-location", R.string.assistance_use_location),
        MANUAL("assistance-manual", R.string.assistance_search),
        PROVIDERS("assistance-providers", R.string.assistance_retry),
        PROVIDERS_LOADING("assistance-providers-loading", R.string.assistance_retry),
        CONFIRMATION("assistance-confirmation", R.string.assistance_request),
        SUCCESS("assistance-success", R.string.assistance_finish),
        NOTIFICATIONS("notifications", R.string.notification_refresh),
        NOTIFICATIONS_LOADING("notifications-loading", R.string.notification_refresh),
        NOTIFICATION_PERMISSION("notification-permission", R.string.notification_permission_turn_off),
        BIOMETRIC("biometric", R.string.biometric_manage),
    }

    private fun captureScenes(fontScale: Float) {
        check(BuildConfig.APPLICATION_ID == "co.solventa.mobile.pf2.claimtest")
        val scene = mutableStateOf(Scene.CLAIM)
        val dark = mutableStateOf(false)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                SolventaTheme(dark.value) {
                    Box(Modifier.requiredSize(320.dp, 480.dp).testTag("operational-fixture")) {
                        key(scene.value, dark.value) { Fixture(scene.value) }
                    }
                }
            }
        }
        for (theme in listOf(false, true)) {
            for (surface in Scene.entries) {
                compose.runOnIdle { dark.value = theme; scene.value = surface }
                compose.waitForIdle()
                val suffix = (if (theme) "dark" else "light") + if (fontScale == 2f) "-large" else ""
                save("ui-${surface.fileName}-$suffix.png")
                compose.onNode(hasText(compose.activity.getString(surface.action)) and hasClickAction())
                    .performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                save("ui-${surface.fileName}-$suffix-action.png")
            }
        }
    }

    private fun save(name: String) {
        val image = compose.onNodeWithTag("operational-fixture").captureToImage().asAndroidBitmap()
        val directory = compose.activity.filesDir
        File(directory, name).outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    @Composable
    private fun Fixture(scene: Scene) {
        when (scene) {
            Scene.CLAIM -> ClaimScreen(
                ClaimState(open = true, cachedPolicies = false, policies = listOf(policy), draft = draft),
                {}, {}, {}, {}, {}, {},
            )
            Scene.CLAIM_LOADING -> ClaimScreen(
                ClaimState(open = true, busy = true, loadingPolicies = true, cachedPolicies = false),
                {}, {}, {}, {}, {}, {},
            )
            Scene.HISTORY -> ClaimScreen(
                ClaimState(open = true, editing = false, reports = listOf(
                    report.copy(receipt = ClaimReceipt(reference, "registrado"), lastResultAt = date),
                )), {}, {}, {}, {}, {}, {}, status = {},
            )
            Scene.STATUS_LIST -> ClaimStatusScreen(ClaimStatusState(claims = listOf(claim, claim.copy(id = otherReference))), {}, {}, {})
            Scene.STATUS_LIST_LOADING -> ClaimStatusScreen(ClaimStatusState(busy = true), {}, {}, {})
            Scene.STATUS_LOADING -> ClaimStatusScreen(ClaimStatusState(busy = true, selectedId = reference), {}, {}, {})
            Scene.STATUS -> ClaimStatusScreen(
                ClaimStatusState(selectedId = reference,
                    detail = ClaimStatus(reference, ClaimProgress.REVIEWING, claim.description, date)),
                {}, {}, {},
            )
            Scene.EVIDENCE_CLAIMS -> EvidenceFixture(EvidenceState(open = true, claims = listOf(claim, claim.copy(id = otherReference))))
            Scene.EVIDENCE_CLAIMS_LOADING -> EvidenceFixture(EvidenceState(open = true, busy = true, loadingClaims = true))
            Scene.EVIDENCE -> EvidenceFixture(evidenceState)
            Scene.EVIDENCE_REVIEW -> EvidenceFixture(evidenceState.copy(
                step = EvidenceStep.REVIEW,
                draft = EvidenceDraft(File(compose.activity.filesDir, "synthetic-capture.jpg"),
                    EvidenceKind.DOCUMENT, date, 1),
                preview = EvidencePreview(document),
            ))
            Scene.LOCATION -> AssistanceFixture(AssistanceState(open = true))
            Scene.MANUAL -> AssistanceFixture(AssistanceState(open = true, step = AssistanceStep.MANUAL,
                manualLatitude = "4.65", manualLongitude = "-74.1", error = AssistanceError.PERMISSION))
            Scene.PROVIDERS -> AssistanceFixture(AssistanceState(open = true, step = AssistanceStep.PROVIDERS,
                providers = listOf(provider)))
            Scene.PROVIDERS_LOADING -> AssistanceFixture(AssistanceState(open = true, step = AssistanceStep.PROVIDERS, busy = true))
            Scene.CONFIRMATION -> AssistanceFixture(AssistanceState(open = true, step = AssistanceStep.CONFIRMATION,
                claimId = reference, location = location, selected = provider))
            Scene.SUCCESS -> AssistanceFixture(AssistanceState(open = true, step = AssistanceStep.SUCCESS,
                receipt = AssistanceReceipt(reference, "registrada", provider.id, provider.serviceType, date)))
            Scene.NOTIFICATIONS, Scene.NOTIFICATION_PERMISSION -> NotificationScreen(
                NotificationUiState(permissionGranted = true, pushEnabled = true, items = listOf(
                    NotificationItem(MobileNotification(reference, NotificationKind.CLAIMS,
                        "Actualizamos tu siniestro", "Consulta los detalles del reporte en Solventa.",
                        NotificationResource(NotificationResourceKind.CLAIM, reference), date), read = false),
                )), {}, {}, {}, {}, {}, {},
            )
            Scene.NOTIFICATIONS_LOADING -> NotificationScreen(
                NotificationUiState(busy = true, loadingNotifications = true), {}, {}, {}, {}, {}, {},
            )
            Scene.BIOMETRIC -> SolventaPage {
                BiometricAccountSection(BiometricUiState(credentials = listOf(
                    RemoteBiometricCredential(reference, date.toString(), true),
                    RemoteBiometricCredential(otherReference, date.toString(), false),
                )), enabled = true, onEnroll = {}, onLoad = {}, onRevoke = {})
            }
        }
    }

    @Composable
    private fun EvidenceFixture(state: EvidenceState) {
        EvidenceContent(state, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
            camera = {}, locationChoice = { EvidenceLocationChoice(state, false, {}) })
    }

    @Composable
    private fun AssistanceFixture(state: AssistanceState) {
        AssistanceContent(state, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {})
    }

    @Test fun claimRowsKeepTheirCallbacksAtLargeText() {
        check(BuildConfig.APPLICATION_ID == "co.solventa.mobile.pf2.claimtest")
        var selected = ""
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                SolventaTheme(false) {
                    Box(Modifier.requiredSize(320.dp, 480.dp)) {
                        ClaimStatusScreen(ClaimStatusState(claims = listOf(claim)),
                            { selected = it.id }, {}, {})
                    }
                }
            }
        }
        compose.onNodeWithText(claim.type).performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(reference, selected) }
    }

    @Test fun biometricConsentDialogInBothThemesAtLargeText() = captureDialog(biometric = true)

    @Test fun rejectedEvidenceDialogInBothThemesAtLargeText() = captureDialog(biometric = false)

    private fun captureDialog(biometric: Boolean) {
        check(BuildConfig.APPLICATION_ID == "co.solventa.mobile.pf2.claimtest")
        val dark = mutableStateOf(false)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                SolventaTheme(dark.value) {
                    key(dark.value) {
                        if (biometric) SolventaPage {
                            BiometricAccountSection(BiometricUiState(), true, {}, {}, {})
                        } else EvidenceFixture(evidenceState.copy(items = listOf(
                            evidenceState.items.single().copy(status = EvidenceStatus.REJECTED,
                                lastError = null, rejection = EvidenceError.INVALID_REQUEST),
                        )))
                    }
                }
            }
        }
        for (theme in listOf(false, true)) {
            compose.runOnIdle { dark.value = theme }
            compose.onNodeWithText(compose.activity.getString(
                if (biometric) R.string.biometric_enable else R.string.evidence_remove,
            )).performScrollTo().performClick()
            compose.waitForIdle()
            val image = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
            val name = "ui-${if (biometric) "biometric-consent" else "evidence-remove"}-${if (theme) "dark" else "light"}-large.png"
            File(compose.activity.filesDir, name).outputStream().use {
                check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        }
    }

    private val reference = "11111111-1111-4111-8111-111111111111"
    private val otherReference = "22222222-2222-4222-8222-222222222222"
    private val date = Instant.parse("2026-11-09T15:00:00Z")
    private val policy = ClaimPolicy(reference, "Seguro de vehículo",
        LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"))
    private val draft = ClaimDraft(reference, "2026-11-09", "09:30", "Daño en el vehículo",
        "Lugar del evento", "El vehículo presenta un daño en la puerta.")
    private val claim = EvidenceClaim(reference, draft.type, date.toString(), draft.description)
    private val report = ClaimReport(otherReference, "synthetic-actor",
        ClaimPayload(reference, date, draft.type, draft.place, draft.description), queuedAt = date)
    private val scope = EvidenceScope("synthetic-actor", reference)
    private val evidenceState = EvidenceState(open = true, scope = scope,
        policy = EvidencePolicy(3, mapOf(EvidenceKind.PHOTO to 5_000_000L, EvidenceKind.DOCUMENT to 5_000_000L), 0),
        items = listOf(EvidenceItem(otherReference, scope, EvidenceKind.PHOTO, date, null, 1024,
            "a".repeat(64), 0, queuedAt = date, lastError = EvidenceError.NETWORK)))
    private val provider = AssistanceProvider(otherReference, "Asistencia de vehículo",
        "asistencia_vial", 2.4)
    private val location = AssistanceLocation(4.65, -74.1, AssistanceLocationOrigin.MANUAL, date)
    private val document by lazy {
        Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888).apply {
            val canvas = android.graphics.Canvas(this)
            canvas.drawColor(android.graphics.Color.WHITE)
            val paint = android.graphics.Paint().apply { color = android.graphics.Color.BLACK; textSize = 32f }
            canvas.drawText("Documento de ejemplo", 40f, 90f, paint)
            canvas.drawText("Captura sintética", 40f, 150f, paint)
        }
    }
}
