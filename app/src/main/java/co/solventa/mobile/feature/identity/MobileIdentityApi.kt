package co.solventa.mobile.feature.identity

import co.solventa.mobile.feature.evidence.MobileEvidenceApi
import java.util.UUID
import javax.inject.Inject
import org.json.JSONObject

internal enum class IdentityStatus(val code: String) {
    PENDING("pendiente"), APPROVED("aprobado"), REJECTED("rechazado"),
}

internal enum class IdentityReason(val code: String) {
    DOCUMENT("documento_no_valido"), LIVENESS("prueba_vida_no_valida"),
}

internal data class IdentityVerification(
    val id: String,
    val status: IdentityStatus,
    val attempts: Int,
    val maxAttempts: Int,
    val retryAllowed: Boolean,
    val reason: IdentityReason?,
) {
    val canSubmit get() = status == IdentityStatus.PENDING || retryAllowed
}

internal interface IdentityApi {
    suspend fun start(actor: String): IdentityVerification
    suspend fun current(actor: String, id: String): IdentityVerification
    suspend fun submit(actor: String, id: String, attemptId: String): IdentityVerification
}

internal class MobileIdentityApi @Inject constructor(private val http: MobileEvidenceApi) : IdentityApi {
    override suspend fun start(actor: String): IdentityVerification = http.session(actor) { grant ->
        parse(http.request("POST", "/verificaciones-identidad", grant.accessToken, body = JSONObject()))
    }

    override suspend fun current(actor: String, id: String): IdentityVerification = http.session(actor) { grant ->
        parse(http.request("GET", "/verificaciones-identidad/${identityUuid(id)}", grant.accessToken), id)
    }

    override suspend fun submit(actor: String, id: String, attemptId: String): IdentityVerification =
        http.session(actor) { grant ->
            parse(http.request("POST", "/verificaciones-identidad/${identityUuid(id)}/intentos",
                grant.accessToken, body = JSONObject()
                    .put("intentoId", identityUuid(attemptId))
                    .put("documentoRef", "fixture:documento-v1")
                    .put("selfieRef", "fixture:selfie-v1")), id)
        }

    private fun parse(data: JSONObject, expectedId: String? = null): IdentityVerification {
        fun text(key: String) = requireNotNull(data.get(key) as? String)
        fun integer(key: String) = requireNotNull(data.get(key) as? Int)
        val id = identityUuid(text("verificacionId"))
        require(expectedId == null || id == expectedId)
        require(data.get("simulada") == true)
        val status = requireNotNull(IdentityStatus.entries.find { it.code == text("estado") })
        val attempts = integer("intentos")
        val maximum = integer("maxIntentos")
        val retry = requireNotNull(data.get("reintentoPermitido") as? Boolean)
        val reason = if (data.get("motivo") == JSONObject.NULL) null
            else requireNotNull(IdentityReason.entries.find { it.code == text("motivo") })
        require(maximum == 3 && attempts in 0..maximum)
        require(retry == (status == IdentityStatus.REJECTED && attempts < maximum))
        when (status) {
            IdentityStatus.PENDING -> require(attempts == 0 && reason == null)
            IdentityStatus.APPROVED -> require(attempts > 0 && reason == null)
            IdentityStatus.REJECTED -> require(attempts > 0 && reason != null)
        }
        return IdentityVerification(id, status, attempts, maximum, retry, reason)
    }
}

internal fun identityUuid(value: String): String =
    UUID.fromString(value).toString().also { require(it == value) }
