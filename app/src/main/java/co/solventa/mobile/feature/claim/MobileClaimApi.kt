package co.solventa.mobile.feature.claim

import co.solventa.mobile.feature.evidence.MobileEvidenceApi
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject

internal class MobileClaimApi @Inject constructor(private val http: MobileEvidenceApi) {
    suspend fun policies(actor: String): List<ClaimPolicy> = http.session(actor) { grant ->
        val entries = http.request("GET", "/siniestros/polizas", grant.accessToken).getJSONArray("polizas")
        (0 until entries.length()).map { index ->
            val item = entries.getJSONObject(index)
            require(item.getString("estado") == "vigente")
            ClaimPolicy(claimUuid(item.getString("id")), item.getString("tipo"),
                LocalDate.parse(item.getString("fechaInicio")), LocalDate.parse(item.getString("fechaFin"))).also {
                require(!it.end.isBefore(it.start))
            }
        }.also { require(it.map(ClaimPolicy::id).distinct().size == it.size) }
    }

    suspend fun send(report: ClaimReport): ClaimReceipt = http.session(report.actor) { grant ->
        val payload = report.payload
        val response = http.request("POST", "/siniestros", grant.accessToken,
            idempotencyKey = claimUuid(report.operation), body = payload.json())
        require(response.getString("polizaId") == payload.policyId &&
            Instant.parse(response.getString("fechaEvento")) == payload.date &&
            response.getString("tipoEvento") == payload.type && response.getString("lugar") == payload.place &&
            response.getString("descripcion") == payload.description && response.getString("estado") == "registrado")
        Instant.parse(response.getString("registradoEn"))
        ClaimReceipt(claimUuid(response.getString("id")), response.getString("estado"))
    }
}
