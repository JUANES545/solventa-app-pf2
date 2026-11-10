package co.solventa.mobile.feature.notification

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
                NotificationScreen(state.value, {}, {}, {}, {}, { refreshes++ }, {})
            }
        }
        compose.onNodeWithContentDescription(app.getString(R.string.notification_loading)).assertExists()
        compose.runOnIdle { state.value = NotificationUiState(error = NotificationError.SESSION) }
        compose.onNodeWithContentDescription(app.getString(R.string.notification_loading)).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.ui_load_notifications_error), useUnmergedTree = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.notification_error_session), useUnmergedTree = true).assertExists()
        compose.onNodeWithText(app.getString(R.string.notification_empty)).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.notification_refresh)).performScrollTo().performClick()
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
        var opened: NotificationItem? = null
        var marked = 0
        compose.setContent {
            SolventaTheme(false) {
                NotificationScreen(
                    NotificationUiState(
                        items = listOf(NotificationItem(notification, read = false)),
                        permissionGranted = true,
                        pushEnabled = true,
                    ),
                    {},
                    {},
                    { marked++ },
                    { opened = it },
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
        compose.onNodeWithText(app.getString(R.string.notification_open))
            .performScrollTo()
            .performClick()
        compose.onNodeWithText(app.getString(R.string.notification_mark_all_read))
            .performScrollTo()
            .performClick()
        compose.runOnIdle {
            assertEquals(notification, opened?.notification)
            assertEquals(1, marked)
        }
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
}
