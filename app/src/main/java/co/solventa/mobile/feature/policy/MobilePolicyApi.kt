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
    val subscriptionId: String,
    val product: PolicyProduct,
    val start: LocalDate,
    val end: LocalDate,
    val status: PolicyStatus,
    val simulated: Boolean,
    val number: String?,
    val mainCoverage: String?,
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
        parse(http.request("GET", "/polizas/${claimUuid(id)}", grant.accessToken)).also {
            require(it.id == id)
        }
    }

    private fun parse(data: JSONObject): Policy {
        fun text(key: String) = requireNotNull(data.get(key) as? String).also { require(it.isNotBlank()) }
        fun nullableText(key: String): String? =
            if (data.get(key) == JSONObject.NULL) null else text(key)
        val start = LocalDate.parse(text("fechaInicio"))
        val end = LocalDate.parse(text("fechaFin"))
        require(!end.isBefore(start))
        return Policy(
            claimUuid(text("id")), claimUuid(text("suscripcionId")),
            requireNotNull(PolicyProduct.entries.find { it.code == text("tipo") }),
            start, end, requireNotNull(PolicyStatus.entries.find { it.code == text("estado") }),
            requireNotNull(data.get("simulada") as? Boolean).also { require(it) },
            nullableText("numero"), nullableText("coberturaPrincipal"),
        )
    }
}
