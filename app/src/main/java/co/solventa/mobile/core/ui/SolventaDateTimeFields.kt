package co.solventa.mobile.core.ui

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import co.solventa.mobile.R
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle

internal fun calendarMillis(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
internal fun calendarDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC).toLocalDate()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SolventaDateField(label: String, value: String, enabled: Boolean, change: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val date = remember(value) {
        try { LocalDate.parse(value) } catch (_: DateTimeParseException) { null }
    }
    SolventaActionRow(title = label,
        supportingText = date?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
            ?: stringResource(R.string.ui_choose_date),
        enabled = enabled, onClick = { open = true })
    if (open) {
        val today = LocalDate.now()
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = date?.let(::calendarMillis),
            initialDisplayedMonthMillis = calendarMillis(date ?: today),
            selectableDates = remember(today) { object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = calendarDate(utcTimeMillis) <= today
                override fun isSelectableYear(year: Int) = year <= today.year
            } },
        )
        DatePickerDialog(onDismissRequest = { open = false },
            confirmButton = {
                TextButton(enabled = enabled && picker.selectedDateMillis?.let { calendarDate(it) <= today } == true, onClick = {
                    picker.selectedDateMillis?.let { change(calendarDate(it).toString()) }
                    open = false
                }) { Text(stringResource(R.string.ui_choose_confirm)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.ui_choose_cancel)) } },
        ) {
            DatePicker(picker, modifier = Modifier.testTag("date-picker"))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SolventaTimeField(label: String, value: String, enabled: Boolean, change: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val time = remember(value) {
        try { LocalTime.parse(value) } catch (_: DateTimeParseException) { null }
    }
    val use24Hours = DateFormat.is24HourFormat(LocalContext.current)
    SolventaActionRow(title = label,
        supportingText = time?.format(DateTimeFormatter.ofPattern(if (use24Hours) "HH:mm" else "h:mm a"))
            ?: stringResource(R.string.ui_choose_time),
        enabled = enabled, onClick = { open = true })
    if (open) {
        val initial = time ?: LocalTime.now()
        val picker = rememberTimePickerState(initial.hour, initial.minute, use24Hours)
        var keyboard by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { open = false }, title = { Text(label) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()).testTag("time-picker")) {
                    if (keyboard) TimeInput(picker) else TimePicker(picker)
                    TextButton(onClick = { keyboard = !keyboard }) {
                        Text(stringResource(if (keyboard) R.string.ui_time_dial else R.string.ui_time_keyboard))
                    }
                }
            },
            confirmButton = { TextButton(enabled = enabled, onClick = {
                change(LocalTime.of(picker.hour, picker.minute).format(DateTimeFormatter.ofPattern("HH:mm")))
                open = false
            }) { Text(stringResource(R.string.ui_choose_confirm)) } },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.ui_choose_cancel)) } },
        )
    }
}
