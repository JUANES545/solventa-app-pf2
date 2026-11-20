package co.solventa.mobile.feature.policy

import co.solventa.mobile.feature.claim.ClaimPolicy
import co.solventa.mobile.feature.claim.claimUuid
import co.solventa.mobile.feature.evidence.MobileEvidenceApi
import java.time.LocalDate
import javax.inject.Inject
import org.json.JSONObject

internal enum class PolicyStatus(val code: String) {
    SCHEDULED("programada"), ACTIVE("vigente"), EXPIRED("vencida"),
}

internal enum class PolicyProduct(val code: String) {
    AUTO("automotive"), TRAVEL("travel"), LIFE("life"), DEVICE("device-protection"),
    PARAMETRIC("parametric"), WEATHER("parametric-weather"), PAYMENT("payment-protection"),
    MORTGAGE("mortgage-life"),
}

internal data class Policy(
    val id: String,
    val product: PolicyProduct,
    val start: LocalDate,
    val end: LocalDate,
    val status: PolicyStatus,
    val simulated: Boolean,
    val number: String,
    val mainCoverage: String,
) {
    fun claimPolicy() = ClaimPolicy(id, product.code, start, end)
}

internal class MobilePolicyApi @Inject constructor(private val http: MobileEvidenceApi) {
    suspend fun list(actor: String): List<Policy> = http.session(actor) { grant ->
        val entries = http.request("GET", "/polizas", grant.accessToken).getJSONArray("polizas")
        (0 until entries.length()).map { parse(entries.getJSONObject(it)) }.also {
            require(it.map(Policy::id).distinct().size == it.size)
        }
    }

    suspend fun detail(actor: String, id: String): Policy = http.session(actor) { grant ->
        parse(http.request("GET", "/polizas/${claimUuid(id)}", grant.accessToken), detail = true).also {
            require(it.id == id)
        }
    }

    private fun parse(data: JSONObject, detail: Boolean = false): Policy {
        fun JSONObject.text(key: String) =
            requireNotNull(get(key) as? String).also { require(it.isNotBlank()) }
        val product = data.getJSONObject("producto")
        val validity = data.getJSONObject("vigencia")
        val coverage = data.getJSONObject("coberturaPrincipal")
        product.text("nombre")
        coverage.text("codigo")
        if (detail) claimUuid(data.text("suscripcionId"))
        val start = LocalDate.parse(validity.text("inicio"))
        val end = LocalDate.parse(validity.text("fin"))
        require(!end.isBefore(start))
        return Policy(
            claimUuid(data.text("id")),
            requireNotNull(PolicyProduct.entries.find { it.code == product.text("tipo") }),
            start, end, requireNotNull(PolicyStatus.entries.find { it.code == data.text("estado") }),
            requireNotNull(data.get("simulada") as? Boolean),
            data.text("numero"), coverage.text("nombre"),
        )
    }
}
