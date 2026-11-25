package co.solventa.mobile.feature.travel

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import co.solventa.mobile.R
import co.solventa.mobile.core.ui.SolventaDateField
import java.time.LocalDate

@Composable
internal fun ProductForm(product: String, busy: Boolean, submit: (Map<String,String>) -> Unit) = key(product) {
    var fields by rememberSaveable { mutableStateOf(hashMapOf<String,String>()) }
    fun change(key: String,value: String) { fields=HashMap(fields).apply { put(key,value) } }
    val today=LocalDate.now()
    @Composable fun input(key: String,label: Int, numeric: Boolean=false) {
        OutlinedTextField(fields[key].orEmpty(),{ change(key,it) },Modifier.fillMaxWidth(),enabled=!busy,singleLine=true,
            label={Text(stringResource(label))},keyboardOptions=KeyboardOptions(keyboardType=if(numeric) KeyboardType.Number else KeyboardType.Text))
    }
    @Composable fun choices(key:String,label:Int,values:List<String>,names:List<Int>) {
        Text(stringResource(label),style=MaterialTheme.typography.titleMedium)
        for ((i,value) in values.withIndex()) FilterChip(selected=fields[key]==value,onClick={change(key,value)},enabled=!busy,
            label={Text(stringResource(names[i]))})
    }
    @Composable fun day(key:String,label:Int,min:LocalDate,max:LocalDate?=null) {
        SolventaDateField(stringResource(label),fields[key].orEmpty(),!busy,min,max) { change(key,it) }
    }
    when(product) {
        "device-protection" -> {input("deviceType",R.string.quote_device);input("brandModel",R.string.quote_model);input("purchaseValue",R.string.quote_price,true)}
        "payment-protection" -> {input("creditType",R.string.quote_credit);input("monthlyPayment",R.string.quote_monthly,true)}
        "parametric" -> {
            input("airline",R.string.quote_airline);input("flightNumber",R.string.quote_flight)
            day("flightDate",R.string.quote_departure,today.plusDays(1))
            choices("coverageType",R.string.quote_cover,flightCovers,listOf(R.string.quote_delay,R.string.quote_connection,R.string.quote_comprehensive))
        }
        "parametric-weather" -> {
            input("location",R.string.quote_location)
            choices("weatherEvent",R.string.quote_weather,weatherEvents,listOf(R.string.quote_rain,R.string.quote_drought))
            day("startDate",R.string.quote_start,today.plusDays(1))
            fields["startDate"]?.let(LocalDate::parse)?.let { day("endDate",R.string.quote_end,it,it.plusDays(365)) }
            input("insuredAmount",R.string.quote_insured,true)
        }
    }
    Button(enabled=!busy && validProductFields(product,fields,today), onClick={submit(fields.toMap())},
        modifier=Modifier.fillMaxWidth()){ Text(stringResource(R.string.catalog_quote)) }
}
