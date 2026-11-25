package co.solventa.mobile.feature.policy

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import co.solventa.mobile.R
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
internal fun PolicyActions(actor: String, policy: Policy, api: PolicyActionsApi, download: () -> Unit,
    onBusy: (Boolean) -> Unit) = key(actor, policy.id) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableIntStateOf(0) }
    var eligibility by remember { mutableStateOf<JSONObject?>(null) }
    var confirming by remember { mutableStateOf(false) }
    val reasons = listOf("ya_no_la_necesito" to R.string.cancel_need, "costo" to R.string.cancel_cost,
        "cambio_de_aseguradora" to R.string.cancel_switch, "insatisfaccion" to R.string.cancel_unsatisfied, "otro" to R.string.cancel_other)
    var reason by remember { mutableStateOf("") }
    fun run(block: suspend () -> Unit) {
        if(busy) return
        busy=true;onBusy(true);message=0
        scope.launch { try { block() } catch(e: CancellationException){throw e}
            catch (_: Exception){message=R.string.travel_check_error} finally {busy=false;onBusy(false)} }
    }
    OutlinedButton(enabled=!busy,onClick=download){Text(stringResource(R.string.wallet_download))}
    OutlinedButton(enabled=!busy,onClick={run { eligibility=api.eligibility(actor,policy.id) }}){Text(stringResource(R.string.cancel_check))}
    val data=eligibility
    if(data!=null){
        if(!data.getBoolean("cancelable")) Text(stringResource(when(data.optString("motivo")){
            "producto_no_cancelable" -> R.string.cancel_product
            "poliza_cancelada" -> R.string.cancel_done
            "poliza_programada" -> R.string.cancel_scheduled
            "poliza_vencida","fin_de_vigencia" -> R.string.cancel_ended
            else -> R.string.cancel_unavailable
        }))
        else {
            val settlement=data.getJSONObject("liquidacion")
            fun amount(field:String)=settlement.getJSONObject(field).let{"${it.getString("moneda")} ${it.getString("monto")}"}
            Text(stringResource(R.string.cancel_settlement,settlement.getString("finCobertura"),amount("devolucion"),amount("valorPendiente")))
            Text(stringResource(R.string.cancel_reason))
            reasons.forEach{(value,label)-> FilterChip(selected=reason==value,onClick={reason=value},label={Text(stringResource(label))},enabled=!busy)}
            Button(enabled=!busy && reason.isNotEmpty(),onClick={confirming=true}){Text(stringResource(R.string.cancel_request))}
            if(confirming) AlertDialog(onDismissRequest={confirming=false},title={Text(stringResource(R.string.cancel_request))},
                text={Text(stringResource(R.string.cancel_confirm_text))},confirmButton={TextButton(onClick={
                    confirming=false
                    run {
                        val hash=settlement.getString("liquidacionHash")
                        val prefs=ctx.getSharedPreferences("travel-$actor",0)
                        val logical="${policy.id}-$reason-$hash"
                        val operation=prefs.getString(logical,null) ?: UUID.randomUUID().toString().also{check(prefs.edit().putString(logical,it).commit())}
                        val live=api.eligibility(actor,policy.id)
                        eligibility=live
                        if(live.getBoolean("cancelable") && live.getJSONObject("liquidacion").getString("liquidacionHash")==hash){
                            api.cancel(actor,policy.id,hash,reason,operation)
                            eligibility=api.eligibility(actor,policy.id)
                        }
                    }
                }){Text(stringResource(R.string.cancel_yes))}},dismissButton={TextButton(onClick={confirming=false}){Text(stringResource(R.string.cancel_keep))}})
        }
    }
    if(message!=0) Text(stringResource(message))
}
