package co.solventa.mobile.feature.notification

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.onNodeWithTag
import co.solventa.mobile.R
import co.solventa.mobile.core.designsystem.SolventaTheme
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w320dp-h640dp")
class NotificationScreenTest {
    @get:Rule
    val compose = createComposeRule()
    private val app get() = RuntimeEnvironment.getApplication()

    @Test
    fun sessionFailureReplacesLoadingWithOneExplicitErrorWithoutAnEmptyResult() {
        val state = mutableStateOf(NotificationUiState(busy = true, loadingNotifications = true))
        var refreshes = 0
        compose.setContent {
            SolventaTheme {
                NotificationScreen(state.value, {}, {}, {}, { refreshes++ }, {})
            }
        }
        compose.onNodeWithContentDescription(app.getString(R.string.notification_loading)).assertExists()
        compose.runOnIdle { state.value = NotificationUiState(error = NotificationError.SESSION) }
        compose.onNodeWithContentDescription(app.getString(R.string.notification_loading)).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.ui_load_notifications_error), useUnmergedTree = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.notification_error_session), useUnmergedTree = true).assertExists()
        compose.onNodeWithText(app.getString(R.string.notification_empty)).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.notification_refresh)).assertDoesNotExist()
        compose.onNodeWithTag("page-refresh")
            .performCustomAccessibilityActionWithLabel(app.getString(R.string.claim_status_refresh))
        compose.runOnIdle { assertEquals(1, refreshes) }
    }

    @Test
    fun displaysMinimizedContentWithoutResourceIdentifiersAndMarksRead() {
        val notification = MobileNotification(
            notificationId,
            NotificationKind.PAYMENTS,
            "Pago paramétrico procesado",
            "Consulta el detalle del pago en Solventa.",
            NotificationResource(NotificationResourceKind.PAYMENT, notificationResourceId),
            Instant.parse("2026-11-05T20:10:00Z"),
        )
        var marked = 0
        var date = ""
        compose.setContent {
            SolventaTheme(false) {
                date = co.solventa.mobile.feature.claim.operationalDate(notification.deliveredAt)
                NotificationScreen(
                    NotificationUiState(
                        items = listOf(NotificationItem(notification, read = false)),
                        permissionGranted = true,
                        pushEnabled = true,
                    ),
                    {},
                    {},
                    { marked++ },
                    {},
                    {},
                )
            }
        }

        compose.onNodeWithText(app.getString(R.string.notification_unread))
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText(notification.title).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(notification.content).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(notification.id).assertDoesNotExist()
        compose.onNodeWithText(notificationResourceId).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.notification_permission_title)).assertDoesNotExist()
        compose.onNodeWithText(date).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.notification_details_show)).performScrollTo().performClick()
        compose.onNodeWithText(date).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.notification_open)).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.ui_show_details)).assertDoesNotExist()
        compose.onNodeWithText(notification.deliveredAt.toString()).assertDoesNotExist()
        compose.onNode(hasText(app.getString(R.string.notification_mark_read)) and hasClickAction())
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.notification_details_hide)).performScrollTo().performClick()
        compose.onNodeWithText(date).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.notification_mark_all_read))
            .performScrollTo()
            .performClick()
        compose.runOnIdle {
            assertEquals(1, marked)
        }
    }

    @Test
    fun compactCardsExposeReadAndDeleteWithoutRequiringSwipes() {
        val notification = MobileNotification(notificationId, NotificationKind.CLAIMS, "Reporte recibido",
            "Consulta el avance.", NotificationResource(NotificationResourceKind.CLAIM, notificationResourceId),
            Instant.parse("2026-11-05T20:10:00Z"))
        var removed: NotificationItem? = null
        compose.setContent {
            SolventaTheme {
                NotificationScreen(
                    NotificationUiState(items = listOf(NotificationItem(notification, false)),
                        permissionGranted = true, pushEnabled = true),
                    {}, {}, {}, {}, {}, discard = { removed = it },
                )
            }
        }
        compose.onNodeWithText(app.getString(R.string.notification_remove_local)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(notificationId, removed?.notification?.id) }
    }

    @Test
    fun accessibilityReadActionAndSwipeDeleteRemainAvailableWithoutOpeningDetails() {
        val notification = MobileNotification(notificationId, NotificationKind.CLAIMS, "Reporte recibido",
            "Consulta el avance.", NotificationResource(NotificationResourceKind.CLAIM, notificationResourceId),
            Instant.parse("2026-11-05T20:10:00Z"))
        var marked = 0
        var removed = 0
        compose.setContent {
            SolventaTheme {
                NotificationScreen(
                    NotificationUiState(items = listOf(NotificationItem(notification, false)),
                        permissionGranted = true, pushEnabled = true),
                    {}, {}, {}, {}, {}, markRead = { marked++ }, discard = { removed++ },
                )
            }
        }
        val readLabel = app.getString(R.string.notification_mark_read)
        val card = compose.onNode(SemanticsMatcher("Acción para marcar aviso leído") { node ->
            node.config.getOrNull(SemanticsActions.CustomActions)?.any { it.label == readLabel } == true
        })
        card.performScrollTo()
            .performCustomAccessibilityActionWithLabel(app.getString(R.string.notification_mark_read))
        card.performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, marked); assertEquals(1, removed) }
    }

    @Test
    fun permissionErrorsAndLoadingRemainExplicit() {
        compose.setContent {
            SolventaTheme(false) {
                NotificationScreen(
                    NotificationUiState(
                        busy = true,
                        loadingNotifications = true,
                        permissionGranted = false,
                        message = NotificationMessage.PERMISSION_DENIED,
                    ),
                    {},
                    {},
                    {},
                    {},
                    {},
                )
            }
        }
        compose.onNodeWithText(app.getString(R.string.notification_permission_allow))
            .performScrollTo()
            .assertIsNotEnabled()
        compose.onNodeWithText(app.getString(R.string.notification_permission_denied))
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithContentDescription(app.getString(R.string.notification_loading))
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.notification_empty)).assertDoesNotExist()
    }

    @Test
    fun grantedPermissionNeverShowsACardWhilePreferencesLoadOrAfterReturning() {
        val state = mutableStateOf(NotificationUiState(permissionGranted = true, busy = true))
        compose.setContent {
            SolventaTheme { NotificationScreen(state.value, {}, {}, {}, {}, {}) }
        }
        fun assertNoPermissionCard() {
            compose.onNodeWithText(app.getString(R.string.notification_permission_allow)).assertDoesNotExist()
            compose.onNodeWithText(app.getString(R.string.notification_permission_turn_on)).assertDoesNotExist()
            compose.onNodeWithText(app.getString(R.string.notification_permission_disabled)).assertDoesNotExist()
            compose.onNodeWithText(app.getString(R.string.notification_permission_title)).assertDoesNotExist()
        }
        assertNoPermissionCard()
        compose.onNodeWithText(app.getString(R.string.notification_title))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.runOnIdle {
            state.value = state.value.copy(busy = false, pushPreferencesKnown = true, pushEnabled = true)
        }
        assertNoPermissionCard()
        compose.runOnIdle { state.value = NotificationUiState(permissionGranted = true, busy = true) }
        assertNoPermissionCard()
    }

    @Test
    fun disabledPreferenceOffersActivationInsteadOfAndroidPermissionAndDisappearsAfterEnabling() {
        val state = mutableStateOf(NotificationUiState(permissionGranted = true, pushPreferencesKnown = true))
        var requests = 0
        var activations = 0
        compose.setContent {
            SolventaTheme(true) {
                NotificationScreen(state.value, { requests++ }, {
                    activations++
                    state.value = state.value.copy(pushEnabled = true)
                }, {}, {}, {})
            }
        }
        compose.onNodeWithText(app.getString(R.string.notification_permission_allow)).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.notification_permission_turn_on))
            .performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText(app.getString(R.string.notification_permission_disabled)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, requests); assertEquals(1, activations) }
    }

    @Test
    fun blockedChannelOffersSettingsWithoutAskingForExistingAndroidPermission() {
        var settings = 0
        compose.setContent {
            SolventaTheme {
                NotificationScreen(NotificationUiState(permissionGranted = false),
                    { settings++ }, {}, {}, {}, {}, settingsRequired = true)
            }
        }
        compose.onNodeWithText(app.getString(R.string.notification_permission_allow)).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.notification_settings))
            .performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(1, settings) }
    }

    @Test
    fun permissionRequestDisappearsImmediatelyAfterGrantWithoutASuccessMessage() {
        val state = mutableStateOf(NotificationUiState(permissionGranted = false))
        var requests = 0
        compose.setContent {
            SolventaTheme {
                NotificationScreen(state.value, { requests++ }, {}, {}, {}, {})
            }
        }
        compose.onNodeWithText(app.getString(R.string.notification_permission_allow))
            .performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle {
            assertEquals(1, requests)
            state.value = state.value.copy(permissionGranted = true)
        }
        compose.onNodeWithText(app.getString(R.string.notification_permission_allow)).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.notification_permission_disabled)).assertDoesNotExist()
    }
}
