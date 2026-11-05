package co.solventa.mobile.feature.claim

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.UUID
import org.json.JSONObject

internal data class ClaimPolicy(val id: String, val type: String, val start: LocalDate, val end: LocalDate)
internal enum class ClaimError { REQUIRED, DATE, DESCRIPTION, LENGTH, POLICY, NETWORK, SESSION, PROTOCOL, STORAGE }
internal class ClaimProblem(val reason: ClaimError) : Exception()
internal data class ClaimDraft(
    val policyId: String = "",
    val date: String = "",
    val time: String = "",
    val type: String = "",
    val place: String = "",
    val description: String = "",
) {
    fun payload(policies: List<ClaimPolicy>, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): ClaimPayload {
        if (listOf(policyId, date, time, type, place, description).any(String::isBlank)) throw ClaimProblem(ClaimError.REQUIRED)
        val policy = policies.singleOrNull { it.id == policyId } ?: throw ClaimProblem(ClaimError.POLICY)
        val instant = try {
            LocalDateTime.parse("${date.trim()}T${time.trim()}").atZone(zone).toInstant()
        } catch (_: DateTimeParseException) {
            throw ClaimProblem(ClaimError.DATE)
        }
        val day = instant.atOffset(ZoneOffset.UTC).toLocalDate()
        if (instant > now || day < policy.start || day > policy.end) throw ClaimProblem(ClaimError.DATE)
        if (description.trim().codePointCount(0, description.trim().length) !in 10..5000) throw ClaimProblem(ClaimError.DESCRIPTION)
        if (type.trim().codePointCount(0, type.trim().length) > 100 ||
            place.trim().codePointCount(0, place.trim().length) > 300) throw ClaimProblem(ClaimError.LENGTH)
        return ClaimPayload(policyId, instant, type.trim(), place.trim(), description.trim()).also {
            if (it.json().toString().toByteArray(Charsets.UTF_8).size > 16 * 1024) throw ClaimProblem(ClaimError.LENGTH)
        }
    }
}

internal data class ClaimPayload(val policyId: String, val date: Instant, val type: String, val place: String, val description: String) {
    fun json() = JSONObject().apply {
        put("polizaId", policyId); put("fechaEvento", date.toString())
        put("tipoEvento", type); put("lugar", place); put("descripcion", description)
    }

    fun draft(): ClaimDraft {
        val local = date.atZone(ZoneId.systemDefault())
        return ClaimDraft(policyId, local.toLocalDate().toString(), local.toLocalTime().toString(), type, place, description)
    }
}
internal data class ClaimReceipt(val id: String, val status: String)
internal enum class ClaimProgress(val code: String) {
    REPORTED("reportado"), EVALUATING("en_evaluacion"), REVIEWING("en_revision"), INSPECTING("en_inspeccion"),
    APPROVED("aprobado"), REJECTED("rechazado"), PAID("pagado"), CLOSED("cerrado"),
}
internal data class ClaimStatus(val id: String, val progress: ClaimProgress, val description: String, val updatedAt: Instant)

internal data class ClaimReport(
    val operation: String,
    val actor: String,
    val payload: ClaimPayload,
    val receipt: ClaimReceipt? = null,
    val error: ClaimError? = null,
) {
    val correctable: Boolean get() = receipt == null && error in setOf(ClaimError.POLICY, ClaimError.DATE)
}

internal fun claimUuid(value: String): String = UUID.fromString(value).toString().also { require(it == value) }
