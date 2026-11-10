package co.solventa.mobile.core.ui

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import co.solventa.mobile.core.designsystem.SolventaTheme
import java.time.LocalDate
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SolventaDateTimeFieldsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun calendarUsesUtcDaysRegardlessOfDeviceZone() {
        val previous = TimeZone.getDefault()
        try {
            for (zone in listOf("America/Bogota", "Pacific/Kiritimati", "America/Los_Angeles")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                val day = LocalDate.of(2026, 11, 1)
                assertEquals(day, calendarDate(calendarMillis(day)))
                assertEquals(1793491200000L, calendarMillis(day))
            }
        } finally { TimeZone.setDefault(previous) }
    }

    @Test fun calendarCommitsOnlyOnAcceptAndCanBeCancelled() {
        val date = mutableStateOf("2020-03-16")
        val enabled = mutableStateOf(true)
        compose.setContent {
            SolventaTheme(false) { SolventaDateField("Fecha", date.value, enabled.value) { date.value = it } }
        }
        compose.onNodeWithText("Fecha").performClick()
        compose.onNodeWithTag("date-picker").assertIsDisplayed()
        compose.onNode(hasText("17", substring = true), useUnmergedTree = true).performClick()
        compose.runOnIdle { assertEquals("2020-03-16", date.value) }
        compose.onNodeWithText("Cancelar").performClick()
        compose.runOnIdle { assertEquals("2020-03-16", date.value) }
        compose.onNodeWithText("Fecha").performClick()
        compose.onNode(hasText("17", substring = true), useUnmergedTree = true).performClick()
        compose.onNodeWithText("Aceptar").performClick()
        compose.runOnIdle { assertEquals("2020-03-17", date.value); enabled.value = false }
        compose.onNodeWithText("Fecha").assertIsNotEnabled()
    }

    @Test fun timeHasDialAndKeyboardAndDoesNotSaveOnCancel() {
        val time = mutableStateOf("13:24")
        compose.setContent {
            SolventaTheme(false) { SolventaTimeField("Hora", time.value, true) { time.value = it } }
        }
        compose.onNodeWithText("Hora").performClick()
        compose.onNodeWithTag("time-picker").assertIsDisplayed()
        compose.onNodeWithText("Escribir hora").performScrollTo().performClick()
        compose.onAllNodes(hasSetTextAction()).onLast().performTextReplacement("35")
        compose.onNodeWithText("Cancelar").performClick()
        compose.runOnIdle { assertEquals("13:24", time.value) }
        compose.onNodeWithText("Hora").performClick()
        compose.onNodeWithText("Escribir hora").performScrollTo().performClick()
        compose.onAllNodes(hasSetTextAction()).onLast().performTextReplacement("35")
        compose.onNodeWithText("Aceptar").performClick()
        compose.runOnIdle { assertEquals("13:35", time.value) }
    }

    @Test fun refreshingDoesNotDismissTheCalendarOrLoseAnUncommittedSelection() {
        val date = mutableStateOf("2020-03-16")
        val enabled = mutableStateOf(true)
        compose.setContent {
            SolventaTheme(false) { SolventaDateField("Fecha", date.value, enabled.value) { date.value = it } }
        }
        compose.onNodeWithText("Fecha").performClick()
        compose.onNode(hasText("17", substring = true), useUnmergedTree = true).performClick()
        compose.runOnIdle { enabled.value = false }
        compose.onNodeWithTag("date-picker").assertIsDisplayed()
        compose.onNodeWithText("Aceptar").assertIsNotEnabled()
        compose.runOnIdle { enabled.value = true }
        compose.onNodeWithText("Aceptar").performClick()
        compose.runOnIdle { assertEquals("2020-03-17", date.value) }
    }
}
