package co.solventa.mobile.feature.auth

import co.solventa.mobile.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import java.util.UUID
import javax.inject.Inject

class MobileAuthApi internal constructor(private val base: String) : AuthApi, BiometricApi {
    @Inject constructor() : this(BuildConfig.AUTH_BASE_URL)

    override fun register(name: String, email: String, password: String) = decodeUser(
        request("POST", "/usuarios", JSONObject().put("nombre", name).put("correo", email).put("contrasena", password)),
    )

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

    override fun beginEnrollment(token: String, password: String, publicKey: ByteArray) =
        request("POST", "/biometria/enrolamientos/desafio",
            JSONObject().put("contrasena", password).put("clavePublica", base64Url(publicKey)), token, 200)

    override fun confirmEnrollment(token: String, challenge: BiometricChallenge, signature: ByteArray) {
        val result = request("POST", "/biometria/enrolamientos", biometricProof(challenge, signature), token, 201)
        require(result.keys().asSequence().toSet() == setOf("credencialId", "estado"))
        require(strictString(result, "credencialId") == challenge.credentialId && strictString(result, "estado") == "activa")
    }

    override fun biometricChallenge(credentialId: String) = request("POST", "/biometria/desafios",
        JSONObject().put("credencialId", canonicalUuid(credentialId)), expectedStatus = 200)

    override fun biometricLogin(challenge: BiometricChallenge, signature: ByteArray): AuthGrant {
        val result = request("POST", "/sesiones/biometria", biometricProof(challenge, signature), expectedStatus = 200)
        require(result.keys().asSequence().toSet() ==
            setOf("usuario", "tokenType", "accessToken", "refreshToken", "expiraEn", "refreshExpiraEn"))
        listOf("tokenType", "accessToken", "refreshToken", "expiraEn", "refreshExpiraEn").forEach { strictString(result, it) }
        val user = result.getJSONObject("usuario")
        require(user.keys().asSequence().toSet() == setOf("id", "nombre", "correo", "rol"))
        listOf("id", "nombre", "correo", "rol").forEach { strictString(user, it) }
        canonicalUuid(strictString(user, "id"))
        return decodeGrant(result)
    }

    override fun biometricCredentials(token: String): List<RemoteBiometricCredential> {
        val result = request("GET", "/biometria/credenciales", token = token, expectedStatus = 200)
        require(result.keys().asSequence().toSet() == setOf("credenciales"))
        val entries = result.getJSONArray("credenciales")
        return (0 until entries.length()).map { index ->
            val entry = entries.getJSONObject(index)
            require(entry.keys().asSequence().toSet() == setOf("credencialId", "creadaEn", "ultimoUsoEn", "estado"))
            val state = strictString(entry, "estado")
            require(state in setOf("activa", "revocada"))
            val created = strictString(entry, "creadaEn")
            require(OffsetDateTime.parse(created).offset.totalSeconds == 0)
            if (!entry.isNull("ultimoUsoEn")) require(OffsetDateTime.parse(strictString(entry, "ultimoUsoEn")).offset.totalSeconds == 0)
            RemoteBiometricCredential(canonicalUuid(strictString(entry, "credencialId")), created, state == "activa")
        }
    }

    override fun revokeBiometricCredential(token: String, credentialId: String) {
        request("DELETE", "/biometria/credenciales/${canonicalUuid(credentialId)}", token = token, expectedStatus = 204)
    }

    private fun biometricProof(challenge: BiometricChallenge, signature: ByteArray): JSONObject {
        require(signature.isNotEmpty() && signature.size <= 72)
        return JSONObject().put("credencialId", challenge.credentialId)
            .put("desafioId", challenge.challengeId).put("firma", base64Url(signature))
    }

    private fun request(method: String, path: String, body: JSONObject? = null, token: String? = null, expectedStatus: Int? = null): JSONObject {
        val encodedBody = body?.toString()?.toByteArray(Charsets.UTF_8)
        if (expectedStatus != null && encodedBody != null) require(encodedBody.size <= 8192)
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
                connection.outputStream.use { it.write(checkNotNull(encodedBody)) }
            }
            val status = connection.responseCode
            if (status !in 200..299) throw AuthHttpError(status)
            if (expectedStatus != null && status != expectedStatus) throw AuthProblem(AuthError.UNAVAILABLE)
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
