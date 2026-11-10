package co.solventa.mobile.feature.evidence

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import co.solventa.mobile.R
import co.solventa.mobile.core.designsystem.SolventaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class EvidenceGalleryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app = RuntimeEnvironment.getApplication()
    private val policy = EvidencePolicy(4, EvidenceKind.entries.associateWith { 1_000_000L }, 45_000)
    private val state = EvidenceState(open = true, actorId = "actor-a",
        scope = EvidenceScope("actor-a", "claim-a"), policy = policy)
    private fun text(id: Int) = app.getString(id)

    @Test fun idleGalleryHidesCaptureLocationAndRetryUntilPendingEvidenceExists() {
        val current = mutableStateOf(state)
        var retries = 0
        compose.setContent {
            SolventaTheme(false) {
                EvidenceContent(current.value, {}, {}, {}, {}, {}, {}, {}, {}, {},
                    { retries++ }, {}, camera = {},
                    locationChoice = { EvidenceLocationChoice(current.value, false, {}) })
            }
        }
        compose.onNodeWithContentDescription(text(R.string.evidence_location)).assertDoesNotExist()
        compose.onNodeWithTag("continue-with-location").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.evidence_retry)).assertDoesNotExist()
        val pending = EvidenceItem(java.util.UUID.randomUUID().toString(), checkNotNull(state.scope),
            EvidenceKind.PHOTO, java.time.Instant.now(), null, 128, "a".repeat(64), 0)
        compose.runOnIdle { current.value = state.copy(items = listOf(pending)) }
        compose.onNodeWithText(text(R.string.evidence_retry)).performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, retries); current.value = current.value.copy(busy = true, progress = 64L to 128L) }
        compose.onNodeWithText(text(R.string.evidence_retry)).performScrollTo().assertIsNotEnabled()
        compose.runOnIdle {
            current.value = state.copy(items = listOf(pending.copy(status = EvidenceStatus.CONFIRMED, remoteId = "remote")))
        }
        compose.onNodeWithText(text(R.string.evidence_retry)).assertDoesNotExist()
        compose.runOnIdle {
            current.value = state.copy(items = listOf(pending.copy(status = EvidenceStatus.REJECTED,
                rejection = EvidenceError.FORMAT)))
        }
        compose.onNodeWithText(text(R.string.evidence_retry)).assertDoesNotExist()
    }

    @Test fun captureKeepsLocationConsentAndRequiresAnExplicitLocationBeforeContinuing() {
        val current = mutableStateOf(state.copy(step = EvidenceStep.CAMERA))
        var continued = 0
        compose.setContent {
            SolventaTheme(false) {
                EvidenceContent(current.value, {}, {}, {}, {}, {}, {}, {}, {}, {}, null,
                    { continued++ }, camera = {}, locationChoice = {
                        EvidenceLocationChoice(current.value, false) {
                            current.value = current.value.copy(locationEnabled = it)
                        }
                    })
            }
        }
        compose.onNodeWithContentDescription(text(R.string.evidence_location)).performScrollTo().assertIsOff().performClick()
        compose.onNodeWithTag("continue-with-location").assertDoesNotExist()
        compose.runOnIdle {
            current.value = current.value.copy(location = EvidenceLocation(4.6, -74.0, java.time.Instant.now()))
        }
        compose.onNodeWithTag("continue-with-location").performScrollTo().assertHeightIsAtLeast(androidx.compose.ui.unit.Dp(48f))
            .performClick()
        compose.runOnIdle { assertEquals(1, continued); current.value = current.value.copy(busy = true) }
        compose.onNodeWithTag("continue-with-location").assertIsNotEnabled()
        compose.onNodeWithContentDescription(text(R.string.evidence_location)).assertIsNotEnabled()
    }

    @Test fun captureAndReviewKeepTheSameOptionalLocationRequest() {
        val current = mutableStateOf(state.copy(step = EvidenceStep.CAMERA))
        var starts = 0
        var cancellations = 0
        compose.setContent {
            SolventaTheme(false) {
                EvidenceContent(current.value, {}, {}, {}, {}, {}, {}, {}, {}, {}, null, null,
                    camera = {}, locationChoice = {
                        DisposableEffect(Unit) {
                            starts++
                            onDispose { cancellations++ }
                        }
                    })
            }
        }
        compose.runOnIdle { current.value = current.value.copy(step = EvidenceStep.CAPTURING) }
        compose.waitForIdle()
        compose.runOnIdle {
            current.value = current.value.copy(step = EvidenceStep.REVIEW,
                draft = EvidenceDraft(java.io.File(app.cacheDir, "synthetic-location-review.jpg"),
                    EvidenceKind.PHOTO, java.time.Instant.now(), current.value.generation))
        }
        compose.waitForIdle()
        assertEquals(1, starts)
        assertEquals(0, cancellations)
    }

    @Test fun threeActionsOpenOneSourceDialogAndCancelDoesNotCaptureOrImport() {
        val captured = mutableListOf<EvidenceKind>()
        val chosen = mutableListOf<EvidenceKind>()
        compose.setContent {
            SolventaTheme(false) {
                EvidenceContent(state, {}, {}, {}, {}, { captured.add(it) }, {}, {}, {}, {}, null, null, {}, {},
                    onChooseFile = { chosen.add(it) })
            }
        }
        compose.onNodeWithText(text(R.string.evidence_gallery_empty)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.evidence_limit, policy.maxCount)).assertDoesNotExist()
        compose.onNodeWithTag("choose-evidence-photo").assertDoesNotExist()
        val actions = mapOf(EvidenceKind.PHOTO to "evidence-add-photo",
            EvidenceKind.DOCUMENT to "evidence-add-document", EvidenceKind.VIDEO to "evidence-add-video")
        for ((kind, tag) in actions) {
            val chosenBefore = chosen.size
            compose.onNodeWithTag(tag).performScrollTo().performClick()
            compose.onNodeWithTag("evidence-source-camera").assertIsDisplayed()
            compose.onNodeWithTag("evidence-source-file").assertIsDisplayed()
            compose.onNodeWithText(text(R.string.evidence_gallery_cancel)).performClick()
            assertTrue(captured.isEmpty())
            assertEquals(chosenBefore, chosen.size)
            compose.onNodeWithTag(tag).performScrollTo().performClick()
            compose.onNodeWithTag("evidence-source-file").performClick()
            assertEquals(kind, chosen.last())
        }
        compose.onNodeWithTag("evidence-add-photo").performScrollTo().performClick()
        compose.onNodeWithTag("evidence-source-camera").performClick()
        assertEquals(listOf(EvidenceKind.PHOTO), captured)
    }

    @Test fun confirmedTileShowsOnlyUsefulDetailsInsideTheModal() {
        val item = EvidenceItem(java.util.UUID.randomUUID().toString(), checkNotNull(state.scope),
            EvidenceKind.PHOTO, java.time.Instant.parse("2026-11-10T05:44:00Z"), null, 128,
            "a".repeat(64), 0, EvidenceStatus.CONFIRMED, java.util.UUID.randomUUID().toString())
        var date = ""
        compose.setContent {
            SolventaTheme(false) {
                date = app.getString(R.string.evidence_gallery_date,
                    co.solventa.mobile.feature.claim.claimStatusDate(item.capturedAt))
                EvidenceGallery(state.copy(items = listOf(item)), {}, {})
            }
        }
        compose.onNodeWithContentDescription(text(R.string.evidence_kind_photo)).assertExists()
        compose.onNodeWithText(text(R.string.evidence_kind_photo)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.evidence_gallery_saved)).assertDoesNotExist()
        compose.onNodeWithTag("evidence-kind-badge").assertDoesNotExist()
        compose.onNodeWithText(date).assertDoesNotExist()
        compose.onNodeWithTag("evidence-tile-${item.id}").performClick()
        compose.onNodeWithText(text(R.string.evidence_kind_photo)).assertIsDisplayed()
        compose.onNodeWithText(date).assertExists()
        compose.onNodeWithText(text(R.string.evidence_gallery_details)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.evidence_gallery_close)).assertHeightIsAtLeast(androidx.compose.ui.unit.Dp(48f))
        compose.onNodeWithText(date).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.evidence_gallery_saved)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(item.id, substring = true).assertDoesNotExist()
        compose.onNodeWithText(checkNotNull(item.remoteId), substring = true).assertDoesNotExist()
        compose.onNodeWithText(item.sha256, substring = true).assertDoesNotExist()
        compose.onNodeWithText(item.capturedAt.toString(), substring = true).assertDoesNotExist()
        compose.onNodeWithText("SHA", substring = true).assertDoesNotExist()
        compose.onNodeWithText("image/jpeg", substring = true).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.operational_display_zone), substring = true).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.operational_date_device_zone, ""), substring = true).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.evidence_gallery_size,
            android.text.format.Formatter.formatShortFileSize(app, item.bytes))).assertExists()
    }

    @Test fun rejectedTileRequiresConfirmationBeforeRemoval() {
        val item = EvidenceItem(java.util.UUID.randomUUID().toString(), checkNotNull(state.scope),
            EvidenceKind.PHOTO, java.time.Instant.now(), null, 128, "a".repeat(64), 0,
            status = EvidenceStatus.REJECTED, rejection = EvidenceError.FORMAT)
        val removed = mutableListOf<EvidenceItem>()
        compose.setContent {
            SolventaTheme(false) { EvidenceGallery(state.copy(items = listOf(item)), { removed.add(it) }, {}) }
        }
        compose.onNodeWithTag("evidence-tile-${item.id}").performClick()
        compose.onNodeWithText(text(R.string.evidence_gallery_remove)).performScrollTo().performClick()
        val dialog = isDialog() and hasAnyDescendant(hasText(text(R.string.evidence_gallery_remove_question)))
        compose.onNode(dialog).assertIsDisplayed()
        compose.onNode(hasText(text(R.string.evidence_gallery_cancel)) and hasAnyAncestor(dialog)).performClick()
        assertTrue(removed.isEmpty())
        compose.onNodeWithText(text(R.string.evidence_gallery_remove)).performScrollTo().performClick()
        compose.onNode(hasText(text(R.string.evidence_gallery_remove)) and hasClickAction() and hasAnyAncestor(dialog))
            .assertHeightIsAtLeast(androidx.compose.ui.unit.Dp(48f)).performClick()
        assertEquals(listOf(item), removed)
    }

    @Test fun remoteMetadataHasAnHonestPreviewWithoutTechnicalReferences() {
        compose.setContent {
            SolventaTheme(true) {
                EvidenceContent(state.copy(confirmedRemoteIds = setOf("remote-one")), {}, {}, {}, {}, {}, {}, {}, {}, {},
                    null, null, {}, {})
            }
        }
        compose.onNodeWithText(text(R.string.evidence_gallery_empty)).assertDoesNotExist()
        compose.onNodeWithTag("evidence-tile-remote-one").performClick()
        compose.onNodeWithText(text(R.string.evidence_preview_unavailable)).assertIsDisplayed()
        compose.onNodeWithTag("evidence-thumbnail").assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.evidence_claim_reference, "remote-one")).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.evidence_gallery_details)).assertDoesNotExist()
        compose.onNodeWithText("remote-one", substring = true).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.evidence_gallery_close)).performScrollTo().performClick()
        compose.onNodeWithTag("evidence-tile-remote-one").assertIsDisplayed()
    }
}
