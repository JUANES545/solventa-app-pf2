package co.solventa.mobile.feature.travel

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import co.solventa.mobile.R
import co.solventa.mobile.core.ui.SolventaDateField
import java.time.LocalDate

internal data class TravelDestination(val code: String, val label: String)
internal data class TravelDates(val country: String, val departure: LocalDate, val arrival: LocalDate)

internal fun travelDatesAllowed(today: LocalDate, departure: LocalDate?, arrival: LocalDate?) =
    departure != null && arrival != null && departure >= today && arrival >= departure

@Composable
internal fun TravelForm(destinations: List<TravelDestination>, busy: Boolean, submit: (TravelDates) -> Unit) {
    val today = LocalDate.now()
    var code by rememberSaveable { mutableStateOf("") }
    var departure by rememberSaveable { mutableStateOf("") }
    var arrival by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    val destination = destinations.singleOrNull { it.code == code }
    val first = departure.takeIf { it.isNotEmpty() }?.let(LocalDate::parse)
    val last = arrival.takeIf { it.isNotEmpty() }?.let(LocalDate::parse)
    Box {
        OutlinedButton(onClick = { menu = true }, enabled = !busy && destinations.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()) {
            Text(destination?.label ?: stringResource(R.string.travel_country))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            destinations.forEach { option ->
                DropdownMenuItem(text = { Text(option.label) }, onClick = { code = option.code; menu = false })
            }
        }
    }
    SolventaDateField(stringResource(R.string.travel_departure), departure, !busy, earliest = today, latest = null) {
        departure = it
        if (last != null && last < LocalDate.parse(it)) arrival = ""
    }
    SolventaDateField(stringResource(R.string.travel_return), arrival, !busy && first != null, earliest = first, latest = null) {
        arrival = it
    }
    Button(onClick = { if (destination != null && first != null && last != null) submit(TravelDates(code, first, last)) },
        enabled = !busy && destination != null && travelDatesAllowed(today, first, last),
        modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.travel_quote)) }
}
