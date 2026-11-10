package co.solventa.mobile.feature.claim

import android.Manifest
import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import co.solventa.mobile.core.designsystem.SolventaTheme
import co.solventa.mobile.core.location.DeviceLocation
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
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
class ClaimLocationFieldTest {
    @get:Rule val compose = createComposeRule()

    @Test fun revisingPlaceDoesNotRecreateTheFocusedTextField() {
        val state = mutableStateOf(ClaimState())
        compose.setContent {
            SolventaTheme(false) {
                ClaimLocationField(state.value, {
                    state.value = state.value.copy(draft = it, locationRevision = state.value.locationRevision + 1)
                }, {}, readState = { state.value })
            }
        }
        compose.onNodeWithTag("claim-place").performClick().performTextInput("Calle")
        compose.onNodeWithTag("claim-place").assertIsFocused().performTextInput(" sintética")
        assertEquals("Calle sintética", state.value.draft.place)
    }

    @Test fun leavingCancelsPendingSearchWithoutApplyingItsResult() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        val visible = mutableStateOf(true)
        var started = false
        var cancelled = false
        val result = CompletableDeferred<DeviceLocation?>()
        compose.setContent {
            SolventaTheme(false) {
                if (visible.value) ClaimLocationField(ClaimState(), { fail("Unexpected edit") }, { fail(it) }) {
                    started = true
                    try { result.await() } finally { cancelled = true }
                }
            }
        }
        compose.onNodeWithTag("claim-current-location").performClick()
        compose.waitUntil { started }
        compose.runOnIdle { visible.value = false }
        compose.onNodeWithTag("claim-current-location").assertDoesNotExist()
        compose.waitForIdle()
        compose.waitUntil { cancelled }
        compose.runOnIdle { result.complete(DeviceLocation(4.5, -74.1, Instant.now())) }
    }

    @Test fun geocoderTimeoutKeepsAnExistingAddress() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        val state = mutableStateOf(ClaimState(draft = ClaimDraft(place = "Original")))
        var message: String? = null
        compose.setContent {
            SolventaTheme(false) {
                ClaimLocationField(state.value, { state.value = state.value.copy(draft = it) }, { message = it },
                    readState = { state.value }, address = { kotlinx.coroutines.delay(5_000); "Tardía" }) {
                    DeviceLocation(4.5, -74.1, Instant.now())
                }
            }
        }
        compose.onNodeWithTag("claim-current-location").performClick()
        compose.mainClock.advanceTimeBy(5_000)
        compose.waitUntil(6_000) { message != null }
        assertEquals("Original", state.value.draft.place)
        assertEquals("Sin dirección disponible. Conservamos tu texto.", message)
    }

    @Test fun locationIsExplicitAndKeepsOtherDraftEdits() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        val state = mutableStateOf(ClaimState(actor = "a"))
        var calls = 0
        val result = CompletableDeferred<DeviceLocation?>()
        compose.setContent {
            SolventaTheme(false) { Column {
                ClaimLocationField(state.value, { state.value = state.value.copy(draft = it) }, { fail(it) },
                    readState = { state.value }, address = { "Calle sintética 10" }) {
                    calls++; result.await()
                }
            } }
        }
        compose.runOnIdle { assertEquals(0, calls) }
        compose.onNodeWithTag("claim-current-location").performClick()
        compose.onNodeWithTag("claim-current-location").assertIsNotEnabled()
        compose.runOnIdle {
            state.value = state.value.copy(draft = state.value.draft.copy(description = "Otro texto"))
            result.complete(DeviceLocation(4.5, -74.1, Instant.now()))
        }
        compose.waitUntil { state.value.draft.place.isNotEmpty() }
        compose.runOnIdle {
            assertEquals("Calle sintética 10", state.value.draft.place)
            assertEquals("Otro texto", state.value.draft.description)
            assertEquals(1, calls)
        }
    }

    @Test fun delayedLocationNeverOverwritesManualPlaceOrAnotherActor() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        val state = mutableStateOf(ClaimState(actor = "a"))
        var result = CompletableDeferred<DeviceLocation?>()
        compose.setContent {
            SolventaTheme(false) { Column {
                ClaimLocationField(state.value, { state.value = state.value.copy(draft = it) }, { fail(it) },
                    readState = { state.value }) { result.await() }
            } }
        }
        compose.onNodeWithTag("claim-current-location").performClick()
        compose.onNodeWithText("Ubicación del evento").performTextReplacement("Calle elegida")
        compose.runOnIdle { result.complete(DeviceLocation(4.5, -74.1, Instant.now())) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("Calle elegida", state.value.draft.place)
            result = CompletableDeferred()
        }
        compose.onNodeWithTag("claim-current-location").performClick()
        compose.runOnIdle {
            state.value = ClaimState(actor = "b")
            result.complete(DeviceLocation(4.5, -74.1, Instant.now()))
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("", state.value.draft.place) }
    }

    @Test fun cancelledSearchCannotReleaseTheNextSearch() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        val state = mutableStateOf(ClaimState(actor = "a"))
        val firstCleanup = CompletableDeferred<Unit>()
        val secondResult = CompletableDeferred<DeviceLocation?>()
        var calls = 0
        var active = false
        compose.setContent {
            SolventaTheme(false) {
                ClaimLocationField(state.value, {
                    state.value = state.value.copy(draft = it,
                        locationRevision = state.value.locationRevision + 1)
                }, { fail(it) }, readState = { state.value }, address = { "Calle sintética 10" },
                    onActiveChange = { active = it }) {
                    calls++
                    if (calls == 1) {
                        try { awaitCancellation() } finally { withContext(NonCancellable) { firstCleanup.await() } }
                    }
                    secondResult.await()
                }
            }
        }
        compose.onNodeWithTag("claim-current-location").performClick()
        compose.waitUntil { calls == 1 && active }
        compose.onNodeWithTag("claim-place").performTextReplacement("Lugar manual")
        compose.waitForIdle()
        compose.onNodeWithTag("claim-current-location").performClick()
        compose.waitUntil { calls == 2 && active }
        compose.runOnIdle { firstCleanup.complete(Unit) }
        compose.waitForIdle()
        compose.onNodeWithTag("claim-current-location").assertIsNotEnabled()
        assertTrue(active)
        compose.runOnIdle { secondResult.complete(DeviceLocation(4.5, -74.1, Instant.now())) }
        compose.waitUntil { state.value.draft.place == "Calle sintética 10" && !active }
    }

    @Test fun unavailableLocationShowsShortFeedbackAndAllowsManualEntry() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        val state = mutableStateOf(ClaimState(actor = "a"))
        var message: String? = null
        compose.setContent {
            SolventaTheme(false) { Column {
                ClaimLocationField(state.value, { state.value = state.value.copy(draft = it) }, { message = it },
                    readState = { state.value }) { null }
            } }
        }
        compose.onNodeWithTag("claim-current-location").performClick()
        compose.waitUntil { message != null }
        compose.onNodeWithText("Ubicación del evento").performTextReplacement("Lugar manual")
        compose.runOnIdle {
            assertEquals("No encontramos tu ubicación. Revisa el GPS o escribe la dirección.", message)
            assertEquals("Lugar manual", state.value.draft.place)
        }
    }

    @Test fun locationCanArriveAfterTenSeconds() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        val state = mutableStateOf(ClaimState())
        var message: String? = null
        compose.setContent {
            SolventaTheme(false) {
                ClaimLocationField(state.value, { state.value = state.value.copy(draft = it) }, { message = it },
                    readState = { state.value }, address = { "Calle sintética 10" }) {
                    kotlinx.coroutines.delay(12_000)
                    DeviceLocation(4.5, -74.1, Instant.now())
                }
            }
        }
        compose.onNodeWithTag("claim-current-location").performClick()
        compose.mainClock.advanceTimeBy(12_500)
        compose.waitUntil(2_000) { state.value.draft.place.isNotBlank() || message != null }
        assertEquals("Calle sintética 10", state.value.draft.place)
        assertNull(message)
    }

        @Test fun circularControlIsInsideTheFieldAtTheRightWithoutTextButtonOrDuplicateLoader() {
            shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
            val result = CompletableDeferred<DeviceLocation?>()
            compose.setContent {
                SolventaTheme(false) { ClaimLocationField(ClaimState(), {}, {}, locate = { result.await() }) }
            }
            val control = compose.onNodeWithTag("claim-current-location")
            control.assertContentDescriptionEquals("Usar ubicación actual").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            val field = compose.onNodeWithTag("claim-place").getUnclippedBoundsInRoot()
            val button = control.getUnclippedBoundsInRoot()
            assertTrue(button.left > (field.left + field.right) / 2)
            assertTrue(button.top >= field.top && button.bottom <= field.bottom)
            compose.onNodeWithText("Usar ubicación actual").assertDoesNotExist()
            control.performClick()
            control.assertIsNotEnabled()
            control.assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,
                "Buscando ubicación"))
            compose.onNodeWithText("Buscando ubicación").assertDoesNotExist()
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(
                androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)
        }

        @Test fun fallbackIsExplicitAndPayloadContainsExactlyTheCoordinatesOrAddress() {
            shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
            val state = mutableStateOf(ClaimState(draft = draft.copy(place = "")))
            var resolve: String? = null
            val messages = mutableListOf<String>()
            compose.setContent {
                SolventaTheme(false) {
                    ClaimLocationField(state.value, { state.value = state.value.copy(draft = it) }, { messages.add(it) },
                        readState = { state.value }, address = { resolve }) { DeviceLocation(4.5, -74.1, Instant.now()) }
                }
            }
            compose.onNodeWithTag("claim-current-location").performClick()
            compose.waitUntil { state.value.draft.place.isNotBlank() }
            compose.runOnIdle {
                assertEquals("Coordenadas: 4.500000, -74.100000", state.value.draft.place)
                assertEquals(state.value.draft.place, state.value.draft.payload(listOf(policy),
                    Instant.parse("2026-11-10T18:00:00Z")).json().getString("lugar"))
                assertEquals(1, messages.size)
                resolve = "Carrera sintética 25"
            }
            compose.onNodeWithTag("claim-current-location").performClick()
            compose.waitUntil { state.value.draft.place == resolve }
            compose.runOnIdle {
                val payload = state.value.draft.payload(listOf(policy), Instant.parse("2026-11-10T18:00:00Z"))
                assertEquals("Carrera sintética 25", payload.place)
                assertEquals("Carrera sintética 25", payload.json().getString("lugar"))
                assertFalse(payload.place.contains("Ubicación actual"))
            }
        }

        @Test fun geocoderUnavailableKeepsExistingManualPlace() {
            shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
            val state = mutableStateOf(ClaimState(draft = ClaimDraft(place = "Mi lugar")))
            var message: String? = null
            compose.setContent {
                SolventaTheme(false) {
                    ClaimLocationField(state.value, { state.value = state.value.copy(draft = it) }, { message = it },
                        readState = { state.value }, address = { null }) { DeviceLocation(4.5, -74.1, Instant.now()) }
                }
            }
            compose.onNodeWithTag("claim-current-location").performClick()
            compose.waitUntil { message != null }
            assertEquals("Mi lugar", state.value.draft.place)
        }

        @Test fun lateGeocodingNeverOverwritesManualEditsEvenIfTextIsRestored() {
            shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
            val state = mutableStateOf(ClaimState(draft = ClaimDraft(place = "Original")))
            val resolved = CompletableDeferred<String?>()
            var geocoding = false
            compose.setContent {
                SolventaTheme(false) {
                    ClaimLocationField(state.value, { state.value = state.value.copy(draft = it) }, { fail(it) },
                        readState = { state.value }, address = { geocoding = true; resolved.await() }) {
                        DeviceLocation(4.5, -74.1, Instant.now())
                    }
                }
            }
            compose.onNodeWithTag("claim-current-location").performClick()
            compose.waitUntil { geocoding }
            compose.onNodeWithTag("claim-place").performTextReplacement("Modificado")
            compose.onNodeWithTag("claim-place").performTextReplacement("Original")
            compose.runOnIdle { resolved.complete("Dirección tardía") }
            compose.waitForIdle()
            assertEquals("Original", state.value.draft.place)
        }

        @Test fun refreshNewDraftCorrectionAndActorInvalidatePendingResults() {
            shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
            val state = mutableStateOf(ClaimState(actor = "a"))
            var result = CompletableDeferred<DeviceLocation?>()
            compose.setContent {
                SolventaTheme(false) {
                    ClaimLocationField(state.value, { state.value = state.value.copy(draft = it) }, { fail(it) },
                        readState = { state.value }, address = { "Resultado obsoleto" }) { result.await() }
                }
            }
            for (next in listOf(ClaimState(actor = "b"), ClaimState(actor = "b", correction = "c"),
                ClaimState(actor = "b", correction = "c", locationRevision = 1),
                ClaimState(actor = "b", correction = "c", locationRevision = 2, busy = true))) {
                compose.onNodeWithTag("claim-current-location").performClick()
                compose.runOnIdle { state.value = next }
                compose.waitForIdle()
                compose.runOnIdle { result.complete(DeviceLocation(4.5, -74.1, Instant.now())) }
                compose.waitForIdle()
                assertEquals("", state.value.draft.place)
                result = CompletableDeferred()
            }
        }

        @Test fun locationTimeoutAndProviderErrorPreserveManualPlace() {
            shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
            val state = mutableStateOf(ClaimState(draft = ClaimDraft(place = "Manual")))
            var timeout = true
            val messages = mutableListOf<String>()
            compose.setContent {
                SolventaTheme(false) {
                    ClaimLocationField(state.value, { state.value = state.value.copy(draft = it) }, { messages.add(it) },
                        readState = { state.value }) {
                        if (timeout) { kotlinx.coroutines.delay(31_000); null }
                        else throw IllegalStateException("synthetic provider unavailable")
                    }
                }
            }
            compose.onNodeWithTag("claim-current-location").performClick()
            compose.mainClock.advanceTimeBy(31_000)
            compose.waitUntil(12_000) { messages.isNotEmpty() }
            compose.runOnIdle { timeout = false }
            compose.onNodeWithTag("claim-current-location").performClick()
            compose.waitUntil { messages.size == 2 }
            assertEquals(listOf("No encontramos tu ubicación. Revisa el GPS o escribe la dirección.",
                "No encontramos tu ubicación. Revisa el GPS o escribe la dirección."), messages)
            assertEquals("Manual", state.value.draft.place)
        }
}
