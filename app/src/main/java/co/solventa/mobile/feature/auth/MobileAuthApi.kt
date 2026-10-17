package co.solventa.mobile.feature.auth

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import java.util.UUID
import javax.inject.Inject

class MobileAuthApi @Inject constructor() : AuthApi {
    private val base = "https://65qgzhody9.execute-api.us-east-2.amazonaws.com/movil/v1"

    override fun login(email: String, password: String) = decodeGrant(
        request("POST", "/sesiones", JSONObject().put("correo", email).put("contrasena", password)),
    )

    override fun refresh(token: String) = decodeGrant(
        request("POST", "/sesiones/renovar", JSONObject().put("refreshToken", token)),
    )

    override fun current(token: String) = decodeUser(request("GET", "/sesiones/actual", token = token))

    override fun logout(accessToken: String, refreshToken: String) {
        request("DELETE", "/sesiones/actual", JSONObject().put("refreshToken", refreshToken), accessToken)
    }

    private fun request(method: String, path: String, body: JSONObject? = null, token: String? = null): JSONObject {
        val connection = URL(base + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-store")
            if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            if (status !in 200..299) throw AuthHttpError(status)
            if (status == 204) return JSONObject()
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(65_537)
                var size = 0
                while (size < buffer.size) {
                    val count = reader.read(buffer, size, buffer.size - size)
                    if (count == -1) break
                    size += count
                }
                if (size > 65_536) throw AuthProblem(AuthError.UNAVAILABLE)
                String(buffer, 0, size)
            }
            return JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }
}

internal fun decodeUser(json: JSONObject): AuthUser {
    val user = json.getJSONObject("usuario")
    require(user.getString("rol") == "cliente")
    return AuthUser(UUID.fromString(user.getString("id")).toString(), user.getString("nombre"), user.getString("correo"))
}

internal fun decodeGrant(json: JSONObject): AuthGrant {
    require(json.getString("tokenType") == "Bearer")
    val access = json.getString("accessToken")
    val refresh = json.getString("refreshToken")
    require(access.isNotBlank() && access.length <= 16_384 && refresh.isNotBlank() && refresh.length <= 2048)
    return AuthGrant(
        decodeUser(json), access, refresh,
        OffsetDateTime.parse(json.getString("expiraEn")).toInstant().toEpochMilli(),
        OffsetDateTime.parse(json.getString("refreshExpiraEn")).toInstant().toEpochMilli(),
    )
}
