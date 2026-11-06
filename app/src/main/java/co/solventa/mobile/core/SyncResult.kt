package co.solventa.mobile.core

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import org.json.JSONObject

internal data class SyncResult(val operationId: String, val at: Instant, val httpStatus: Int, val code: String?) {
    init {
        require(UUID.fromString(operationId).toString() == operationId)
        require(httpStatus in 200..499)
        require(if (httpStatus < 300) code == null else !code.isNullOrBlank())
    }

    fun json() = JSONObject().apply {
        put("operacionId", operationId); put("fecha", at.toString())
        put("httpStatus", httpStatus); put("code", code ?: JSONObject.NULL)
    }

    companion object {
        fun parse(json: JSONObject, operationId: String): SyncResult {
            require(json.getString("operacionId") == operationId)
            val date = OffsetDateTime.parse(json.getString("fecha"))
            require(date.offset == ZoneOffset.UTC)
            val code = json.get("code")
            return SyncResult(operationId, date.toInstant(), requireNotNull(json.get("httpStatus") as? Int),
                if (code == JSONObject.NULL) null else requireNotNull(code as? String))
        }

        fun fromResponse(json: JSONObject, operationId: String): SyncResult? =
            if (json.isNull("ultimoResultado")) null else parse(json.getJSONObject("ultimoResultado"), operationId)
    }
}
