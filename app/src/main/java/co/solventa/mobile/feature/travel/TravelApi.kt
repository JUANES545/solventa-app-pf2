package co.solventa.mobile.feature.travel

import co.solventa.mobile.feature.claim.claimUuid
import co.solventa.mobile.BuildConfig
import co.solventa.mobile.feature.evidence.EvidenceHttpProblem
import co.solventa.mobile.feature.evidence.MobileEvidenceApi
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import javax.inject.Inject

internal class TravelApi @Inject constructor(private val http: MobileEvidenceApi) {
    private val base = "/solicitudes-cotizacion"
    private fun path(ref: String) = "$base/${ref.also { require(it.matches(Regex("AQT-[0-9]{4}-[A-Z0-9]{6,12}"))) }}"
    suspend fun ongoing(actor: String): List<Pair<String,String>> {
        val rows = get(actor,"$base/en-curso").getJSONArray("solicitudes")
        return List(rows.length()) { i ->
            val row=rows.getJSONObject(i)
            row.getString("solicitudId").also { path(it) } to row.getString("productoId")
        }.filter { it.second in quoteProducts }
    }
    private suspend fun get(actor: String, path: String) = http.session(actor) {
        http.request("GET", path, it.accessToken)
    }
    private suspend fun mutate(actor: String, method: String, path: String, body: JSONObject?, key: String) =
        http.session(actor) { http.request(method, path, it.accessToken, body, claimUuid(key), operationId = null) }

    suspend fun destinations(actor: String): List<TravelDestination> {
        get(actor, "/productos-seguro/travel")
        val rows = get(actor, "/destinos-viaje").getJSONArray("destinos")
        return List(rows.length()) { rows.getJSONObject(it).let { d -> TravelDestination(d.getString("codigo"), d.getString("nombre")) } }
    }
    suspend fun available(actor: String): Map<String,String> {
        val rows=get(actor,"/productos-seguro").getJSONArray("productos")
        return buildMap {
            for(i in 0 until rows.length()) {
                val row=rows.getJSONObject(i); val id=row.getString("id")
                if(id in quoteProducts && row.getBoolean("disponible")) put(id,row.getString("nombre"))
            }
        }
    }
    suspend fun catalog(actor: String, product: String) {
        require(product in quoteProducts)
        val data=get(actor,"/productos-seguro/$product")
        require(data.getString("id")==product && data.getBoolean("disponible"))
    }
    suspend fun newQuote(actor: String, product: String, fields: Map<String,String>, key: String): String {
        require(validProductFields(product,fields,java.time.LocalDate.now()) && validConsent(consent(actor)))
        catalog(actor,product)
        return mutate(actor,"POST",base,JSONObject().put("productoId",product)
            .put("datosProducto",JSONObject(fields)),key).getString("id").also { path(it) }
    }
    suspend fun auto(actor: String, plate: String, key: String): String {
        require(plate.matches(Regex("[A-Z]{3}[0-9]{3}")) && validConsent(consent(actor)))
        return mutate(actor,"POST",base,JSONObject().put("productoId","automotive")
            .put("datosProducto",JSONObject().put("vehiclePlate",plate)),key).getString("id").also { path(it) }
    }
    suspend fun consent(actor: String): JSONObject? = try {
        get(actor, "/consentimientos/perfilamiento").also { require(it.getString("clienteId") == actor) }
    } catch (e: EvidenceHttpProblem) { if (e.status == 404) null else throw e }
    suspend fun decide(actor: String, body: JSONObject, key: String) =
        mutate(actor, "PUT", "/consentimientos/perfilamiento", body, key).also { require(it.getString("clienteId") == actor) }
    suspend fun quote(actor: String, dates: TravelDates, key: String): String {
        require(validConsent(consent(actor))) { "consentimiento" }
        return mutate(actor, "POST", base, JSONObject().put("productoId","travel").put("datosProducto", JSONObject()
            .put("destinationCountryCode",dates.country).put("departureDate",dates.departure.toString())
            .put("returnDate", dates.arrival.toString())), key).getString("id").also { path(it) }
    }
    suspend fun request(actor: String, ref: String) = get(actor, path(ref)).also {
        require(it.getString("id") == ref && it.getString("productoId") in quoteProducts)
    }
    suspend fun offers(actor: String, ref: String) = get(actor, "${path(ref)}/ofertas").also { require(it.getString("solicitudId") == ref) }.getJSONArray("ofertas")
    suspend fun provisional(actor: String, ref: String, id: String, version: Int, previous: Int, key: String) =
        mutate(actor, "PUT", "${path(ref)}/seleccion-provisional", JSONObject().put("ofertaId",claimUuid(id))
            .put("version",version).put("revisionEsperada",previous), key).also { require(it.getString("solicitudId") == ref) }
    suspend fun accept(actor: String, ref: String, selection: JSONObject, key: String) =
        mutate(actor,"PUT","${path(ref)}/seleccion",JSONObject().put("ofertaId",selection.getString("ofertaId"))
            .put("version",selection.getInt("version")).put("revisionSeleccion",selection.getInt("revision")),key)
    suspend fun subscribe(actor: String, ref: String, key: String) = mutate(actor,"POST","${path(ref)}/suscripcion",null,key)
    val demoPayment = BuildConfig.BUILD_TYPE in setOf("travelTest", "autoTest", "videoTest")
    suspend fun pay(actor: String, ref: String, key: String): JSONObject {
        check(demoPayment)
        return mutate(actor,"POST","${path(ref)}/pago-simulado",JSONObject(),key)
    }
    suspend fun status(actor: String, ref: String, step: String): JSONObject? {
        require(step in setOf("seleccion-provisional","seleccion","suscripcion","pago-simulado"))
        check(step != "pago-simulado" || demoPayment)
        return try { get(actor,"${path(ref)}/$step") } catch (e: EvidenceHttpProblem) { if(e.status==404) null else throw e }
    }
    suspend fun issued(actor: String, ref: String, sub: JSONObject, payment: JSONObject?): String? {
        if (sub.getJSONObject("saga").getString("estado") != "emitida") return null
        val id = claimUuid(sub.getString("id"))
        require(sub.getString("solicitudId")==ref)
        if (demoPayment) {
            if (payment?.getString("estado") != "confirmado") return null
            require(payment.getString("solicitudId")==ref && payment.getString("suscripcionId")==id)
        }
        val rows = get(actor,"/polizas").getJSONArray("polizas")
        for(i in 0 until rows.length()) {
            val policy = claimUuid(rows.getJSONObject(i).getString("id"))
            val detail = get(actor,"/polizas/$policy")
            if(detail.getString("id")==policy && detail.getString("suscripcionId")==id) return policy
        }
        return null
    }
}

internal fun validConsent(data: JSONObject?) = data != null && data.getString("estado") == "autorizado" &&
    Instant.parse(data.getString("vigenteHasta")).isAfter(Instant.now())

internal fun consentDecision(version: Int, accepted: Boolean, until: Instant): JSONObject {
    val data = JSONObject().put("autorizado",accepted).put("version",version)
    if (accepted) data.put("solicitud",JSONObject().put("finalidad","cotizacion")
        .put("alcances",JSONArray(listOf("open_finance","open_data"))).put("fuentes",JSONArray(listOf("open_finance","open_data")))
        .put("datos",JSONArray(listOf("indicador_riesgo"))).put("versionTerminos","1.0").put("vigenteHasta",until.toString()))
    return data
}
