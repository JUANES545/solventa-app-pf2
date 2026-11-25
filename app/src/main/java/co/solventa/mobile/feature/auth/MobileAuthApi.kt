package co.solventa.mobile.feature.auth

import co.solventa.mobile.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import java.util.UUID
import javax.inject.Inject

class MobileAuthApi internal constructor(private val base: String, private val recoveryBase: String = base,
    private val registrationBase: String = base) : AuthApi, BiometricApi, CompleteRegistrationApi {
    @Inject constructor() : this(BuildConfig.AUTH_BASE_URL, BuildConfig.RECOVERY_API_BASE.ifEmpty { BuildConfig.AUTH_BASE_URL },
        BuildConfig.REGISTRATION_API_BASE.ifEmpty { BuildConfig.AUTH_BASE_URL })
    private var recoveryRetryAt = 0L

    internal fun requestRecovery(email: String) {
        checkRecoveryRetry()
        val result = request("POST", "/recuperacion-contrasena", JSONObject().put("correo", email), expectedStatus = 202)
        require(strictString(result, "mensaje").isNotBlank())
    }

    internal fun confirmRecovery(token: String, password: String) {
        checkRecoveryRetry()
        val challenge = request("GET", "/recuperacion-contrasena/desafio", expectedStatus = 200)
        val sealed = encryptRecovery(challenge, token, password)
        request("POST", "/recuperacion-contrasena/confirmar",
            JSONObject().put("token", token).put("credencialCifrada", sealed), expectedStatus = 204)
    }

    private fun checkRecoveryRetry() {
        if (android.os.SystemClock.elapsedRealtime() < recoveryRetryAt) throw AuthProblem(AuthError.RATE_LIMIT)
    }

    override fun register(name: String, email: String, password: String) = decodeUser(
        request("POST", "/usuarios", JSONObject().put("nombre", name).put("correo", email).put("contrasena", password)),
    )

    override fun registerComplete(name: String, email: String, password: String, profile: RegistrationProfile): AuthUser {
        require(profile.valid())
        val challenge=request("GET","/usuarios/desafio", expectedStatus=200)
        val sealed=encryptCredential(challenge,"solventa-register+jwe",JSONObject().put("correo",email).put("contrasena",password))
        return decodeUser(request("POST","/usuarios/registro-completo",profile.json().put("nombre",name).put("correo",email)
            .put("credencialCifrada",sealed),expectedStatus=201))
    }

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
        val responseBody = request("POST", "/biometria/enrolamientos", biometricProof(challenge, signature), token, 201)
        require(responseBody.keys().asSequence().toSet() == setOf("credencialId", "estado"))
        require(strictString(responseBody, "credencialId") == challenge.credentialId && strictString(responseBody, "estado") == "activa")
    }

    override fun biometricChallenge(credentialId: String) = request("POST", "/biometria/desafios",
        JSONObject().put("credencialId", canonicalUuid(credentialId)), expectedStatus = 200)

    override fun biometricLogin(challenge: BiometricChallenge, signature: ByteArray): AuthGrant {
        val responseBody = request("POST", "/sesiones/biometria", biometricProof(challenge, signature), expectedStatus = 200)
        require(responseBody.keys().asSequence().toSet() ==
            setOf("usuario", "tokenType", "accessToken", "refreshToken", "expiraEn", "refreshExpiraEn"))
        listOf("tokenType", "accessToken", "refreshToken", "expiraEn", "refreshExpiraEn").forEach { strictString(responseBody, it) }
        val user = responseBody.getJSONObject("usuario")
        require(user.keys().asSequence().toSet() == setOf("id", "nombre", "correo", "rol"))
        listOf("id", "nombre", "correo", "rol").forEach { strictString(user, it) }
        canonicalUuid(strictString(user, "id"))
        return decodeGrant(responseBody)
    }

    override fun biometricCredentials(token: String): List<RemoteBiometricCredential> {
        val responseBody = request("GET", "/biometria/credenciales", token = token, expectedStatus = 200)
        require(responseBody.keys().asSequence().toSet() == setOf("credenciales"))
        val entries = responseBody.getJSONArray("credenciales")
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
        val origin = when {
            path.startsWith("/usuarios/") -> registrationBase
            path.startsWith("/recuperacion-contrasena") -> recoveryBase
            else -> base
        }
        val connection = URL(origin + path).openConnection() as HttpURLConnection
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
                // Account creation must not be replayed automatically after a lost response.
                if (path.startsWith("/usuarios") || path.startsWith("/recuperacion-contrasena")) {
                    connection.setFixedLengthStreamingMode(checkNotNull(encodedBody).size)
                }
                connection.outputStream.use { it.write(checkNotNull(encodedBody)) }
            }
            val status = connection.responseCode
            if (path.startsWith("/recuperacion-contrasena")) {
                if (status == 429) {
                    val header = connection.getHeaderField("Retry-After")
                    val wait = header?.toLongOrNull()?.coerceIn(0, Int.MAX_VALUE.toLong())?.times(1000)
                        ?: (connection.getHeaderFieldDate("Retry-After", 0) - System.currentTimeMillis()).coerceAtLeast(0)
                    recoveryRetryAt = android.os.SystemClock.elapsedRealtime() + wait
                }
                if (status == 400) {
                    val raw = connection.errorStream?.bufferedReader()?.use { it.readTextBounded() }
                    if (raw != null) {
                        val data = JSONObject(raw)
                        if (data.optString("code") == "recuperacion_invalida") {
                            throw AuthProblem(AuthError.RECOVERY_LINK)
                        }
                    }
                }
            }
            if (status !in 200..299) throw AuthHttpError(status)
            if (expectedStatus != null && status != expectedStatus) throw AuthProblem(AuthError.UNAVAILABLE)
            if (status == 204) return JSONObject()
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readTextBounded() }
            return JSONObject(text)
        } finally {
            connection.disconnect()
        }

    }

    private fun java.io.Reader.readTextBounded(): String {
        val buffer = CharArray(65_537)
        var size = 0
        while (size < buffer.size) {
            val count = read(buffer, size, buffer.size - size)
            if (count == -1) break
            size += count
        }
        if (size > 65_536) throw AuthProblem(AuthError.UNAVAILABLE)
        return String(buffer, 0, size)
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
