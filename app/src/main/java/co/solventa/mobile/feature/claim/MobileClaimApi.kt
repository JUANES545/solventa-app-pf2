package co.solventa.mobile.feature.claim

import co.solventa.mobile.feature.evidence.MobileEvidenceApi
import co.solventa.mobile.feature.evidence.EvidenceClaim
import co.solventa.mobile.core.SyncResult
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject

internal class MobileClaimApi @Inject constructor(private val http: MobileEvidenceApi) {
    suspend fun claims(actor: String): List<EvidenceClaim> = http.claims(actor).also { claims ->
        require(claims.map(EvidenceClaim::id).distinct().size == claims.size)
        claims.forEach {
            require(it.type.isNotBlank() && it.description.isNotBlank())
            Instant.parse(it.date)
        }
    }

    suspend fun status(actor: String, id: String): ClaimStatus = http.session(actor) { grant ->
        val data = http.request("GET", "/siniestros/${claimUuid(id)}/estado", grant.accessToken)
        require(data.getString("id") == id)
        val progress = requireNotNull(ClaimProgress.entries.find { it.code == data.getString("estado") })
        val description = requireNotNull(data.get("descripcion") as? String).also { require(it.isNotBlank()) }
        ClaimStatus(id, progress, description, Instant.parse(data.getString("actualizadoEn")))
    }

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
        val result = SyncResult.fromResponse(response, report.operation)
        require(result == null || result.httpStatus in setOf(200, 201))
        ClaimReceipt(claimUuid(response.getString("id")), response.getString("estado"),
            Instant.parse(response.getString("registradoEn")), result)
    }
}
