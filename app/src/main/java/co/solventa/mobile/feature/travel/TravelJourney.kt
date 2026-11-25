package co.solventa.mobile.feature.travel

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import co.solventa.mobile.R
import co.solventa.mobile.core.ui.SolventaPage
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
internal fun TravelJourney(actor: String, api: TravelApi, settings: Boolean, back: () -> Unit) = key(actor) {
    val ctx = LocalContext.current
    val prefs = remember(actor) { ctx.getSharedPreferences("travel-$actor", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    val draftHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    var stage by remember { mutableStateOf("loading") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var profileIncomplete by remember { mutableStateOf(false) }
    var destinations by remember { mutableStateOf(emptyList<TravelDestination>()) }
    var consent by remember { mutableStateOf<JSONObject?>(null) }
    var ref by remember { mutableStateOf<String?>(null) }
    var product by remember { mutableStateOf<String?>(null) }
    var plate by remember { mutableStateOf("") }
    var ongoing by remember { mutableStateOf(emptyList<Pair<String,String>>()) }
    var catalog by remember { mutableStateOf(emptyMap<String,String>()) }
    var offers by remember { mutableStateOf(emptyList<JSONObject>()) }
    var selection by remember { mutableStateOf<JSONObject?>(null) }
    var result by remember { mutableStateOf("") }
    fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = false; profileIncomplete = false
        scope.launch {
            try { block() } catch (e: CancellationException) { throw e }
            catch (e: co.solventa.mobile.feature.evidence.EvidenceHttpProblem) {
                profileIncomplete = e.status == 422 && e.code == "datos_cliente_incompletos"
                error = true
            }
            catch (_: Exception) { error = true } finally { busy = false }
        }
    }
    fun op(name: String): String {
        prefs.getString(name,null)?.let { return it }
        return UUID.randomUUID().toString().also { check(prefs.edit().putString(name,it).commit()) }
    }
    suspend fun refresh() {
        consent = api.consent(actor)
        val id = ref
        if(!settings && id == null && product == null) { catalog=api.available(actor);stage="catalog"; return }
        if (settings || !validConsent(consent)) { stage = "consent"; return }
        if (id == null) {
            if(product=="automotive"){api.catalog(actor,"automotive");stage="auto"}
            else if(product=="travel") {destinations = api.destinations(actor); stage = "dates"}
            else {api.catalog(actor,checkNotNull(product));stage="product"}
            return
        }
        val request = api.request(actor,id)
        val sub = api.status(actor,id,"suscripcion")
        if (sub != null) {
            if (sub.getString("estado") != "aprobada") { result = sub.getString("estado"); stage="state"; return }
            val paid = if (api.demoPayment) api.status(actor,id,"pago-simulado") else null
            if (api.demoPayment && paid == null) { stage="payment"; return }
            val policy = api.issued(actor,id,sub,paid)
            result = if (policy != null) ctx.getString(R.string.travel_issued) else when (sub.getJSONObject("saga").getString("estado")) {
                "fallida" -> ctx.getString(R.string.travel_failed)
                else -> ctx.getString(R.string.travel_reconciling)
            }
            stage="state"; return
        }
        if(api.status(actor,id,"seleccion") != null) { stage="subscribe"; return }
        selection = api.status(actor,id,"seleccion-provisional")?.optJSONObject("seleccion")
        if(selection!=null) { stage="accept"; return }
        if(request.getString("estado") in setOf("fallida","sin_ofertas")) { result=request.getString("estado");stage="state";return }
        val rows=api.offers(actor,id)
        offers=List(rows.length()){rows.getJSONObject(it)}; stage="offers"
    }
    LaunchedEffect(actor) { run { refresh() } }
    fun previous() {
        if(settings || stage=="catalog") {
            prefs.edit().remove("registration-opt-in").apply()
            back()
        }
        else {ref=null;product=null;stage="catalog";selection=null;error=false}
    }
    BackHandler { if(!busy) previous() }
    SolventaPage {
        co.solventa.mobile.core.ui.SolventaScreenHeader(stringResource(if(settings) R.string.travel_consent else if(stage=="catalog") R.string.catalog_title else R.string.catalog_quote),
            stringResource(R.string.catalog_back), ::previous, !busy)
        if(stage=="catalog"){
            if(catalog.isEmpty()) Text(stringResource(R.string.quote_unavailable))
            for((value,label) in catalog){
                OutlinedButton(enabled=!busy,onClick={product=value;run{refresh()}},modifier=Modifier.fillMaxWidth()){
                    val art=when(value){
                        "device-protection" -> R.drawable.quote_device
                        "payment-protection" -> R.drawable.quote_payment
                        "parametric" -> R.drawable.quote_flight
                        "parametric-weather" -> R.drawable.quote_weather
                        else -> null
                    }
                    if(art!=null) androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(art),null)
                    Text(label)
                }
            }
            TextButton(enabled=!busy,onClick={run {
                ongoing=api.ongoing(actor);stage="ongoing"
            }}){Text(stringResource(R.string.catalog_ongoing))}
        } else if(stage=="ongoing") {
            if(ongoing.isEmpty()) Text(stringResource(R.string.catalog_empty))
            ongoing.forEach { (id,type) ->
                OutlinedButton(enabled=!busy,onClick={run {ref=id;product=type;refresh()}},modifier=Modifier.fillMaxWidth()) {
                    val kind=co.solventa.mobile.feature.policy.PolicyProduct.entries.first { it.code==type }
                    Text("${stringResource(co.solventa.mobile.feature.policy.policyProductText(kind))} · $id")
                }
            }
        } else if(stage=="consent") {
            Text(stringResource(if(validConsent(consent)) R.string.travel_authorized else R.string.travel_not_authorized))
            Text(stringResource(R.string.travel_terms))
            val accepted = !validConsent(consent)
            Button(onClick={run {
                val version = consent?.getInt("version") ?: 0
                val key = "consent-$version-$accepted"
                val stored = prefs.getString("$key-body",null)
                val body = stored?.let(::JSONObject) ?: consentDecision(version,accepted,Instant.now().plusSeconds(29*86400L)).also {
                    check(prefs.edit().putString("$key-body",it.toString()).commit())
                }
                consent=api.decide(actor,body,op(key))
                prefs.edit().remove("registration-opt-in").apply()
                refresh()
            }},enabled=!busy){ Text(stringResource(if(accepted) R.string.travel_authorize else R.string.travel_revoke)) }
        } else if(stage=="auto"){
            OutlinedTextField(plate,{plate=it.uppercase(java.util.Locale.ROOT)},enabled=!busy,singleLine=true,
                label={Text(stringResource(R.string.catalog_plate))})
            Button(enabled=!busy && plate.matches(Regex("[A-Z]{3}[0-9]{3}")),onClick={run{
                val id=api.auto(actor,plate,op("auto-$plate"))
                check(prefs.edit().putString("ref",id).commit());ref=id;refresh()
            }}){Text(stringResource(R.string.catalog_quote))}
        } else if(stage=="dates") {
            draftHolder.SaveableStateProvider("viaje") { TravelForm(destinations,busy){dates->run {
                val id=api.quote(actor,dates,op("quote-${dates.country}-${dates.departure}-${dates.arrival}"))
                check(prefs.edit().putString("ref",id).commit());ref=id;refresh()
            }} }
        } else if(stage=="product") {
            val selected=checkNotNull(product)
            Text(checkNotNull(catalog[selected]),style=MaterialTheme.typography.titleLarge)
            draftHolder.SaveableStateProvider(selected) { ProductForm(selected,busy){fields->run{
                val body=org.json.JSONObject(fields.toSortedMap()).toString()
                val digest=co.solventa.mobile.feature.auth.sha256Base64(body.toByteArray())
                ref=api.newQuote(actor,selected,fields,op("quote-$selected-$digest"))
                refresh()
            }} }
        } else if(stage=="offers") {
            if(offers.isEmpty()) Text(stringResource(R.string.travel_no_offers))
            offers.forEach { offer ->
                val price=offer.getJSONObject("prima")
                Text("${offer.getString("nombre")} · ${price.getString("moneda")} ${price.getString("monto")}")
                Text(offer.getString("coberturaPrincipal"))
                OutlinedButton(enabled=!busy && offer.getString("estado")=="disponible",onClick={run {
                    val id=checkNotNull(ref);val offerId=offer.getString("id");val v=offer.getInt("version")
                    selection=api.provisional(actor,id,offerId,v,0,op("$id-$offerId-$v"));stage="accept"
                }}) { Text(stringResource(R.string.travel_choose)) }
            }
        } else if(stage in setOf("accept","subscribe","payment")) {
            selection?.let { Text(it.getString("nombreOferta")); val price=it.getJSONObject("prima");Text("${price.getString("moneda")} ${price.getString("monto")}") }
            Button(enabled=!busy,onClick={run {
                val id=checkNotNull(ref)
                when(stage) {
                    "accept" -> api.accept(actor,id,checkNotNull(selection),op("$id-accept"))
                    "subscribe" -> api.subscribe(actor,id,op("$id-sub"))
                    "payment" -> api.pay(actor,id,op("$id-pay"))
                }
                refresh()
            }}) { Text(stringResource(when(stage){"accept"->R.string.travel_accept;"subscribe"->R.string.travel_subscribe;else->R.string.travel_pay})) }
        } else if(stage=="state") Text(result)
        if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if(error) Text(stringResource(if(profileIncomplete) R.string.quote_profile_incomplete else R.string.travel_check_error),color=MaterialTheme.colorScheme.error)
        if(!busy && (error || stage=="state")) OutlinedButton(onClick={run { refresh() }}){Text(stringResource(R.string.policy_refresh))}
    }
}
