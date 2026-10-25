package co.solventa.mobile.feature.auth

import org.json.JSONObject
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID

data class BiometricChallenge(
    val credentialId: String,
    val challengeId: String,
    val message: ByteArray,
    val expiresAt: Long,
)

internal data class BiometricCredential(
    val credentialId: String,
    val alias: String,
    val userId: String,
    val publicKey: ByteArray,
)

data class RemoteBiometricCredential(val id: String, val createdAt: String, val active: Boolean)

internal fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

internal fun decodeBase64Url(value: String, maxBytes: Int): ByteArray {
    require(value.isNotEmpty() && value.length <= (maxBytes * 4 + 2) / 3 && value.matches(Regex("[A-Za-z0-9_-]+")))
    val bytes = Base64.getUrlDecoder().decode(value)
    require(bytes.size <= maxBytes && base64Url(bytes) == value)
    return bytes
}

internal fun canonicalUuid(value: String): String {
    require(UUID.fromString(value).toString() == value)
    return value
}

internal fun strictString(json: JSONObject, field: String): String =
    (json.get(field) as? String) ?: throw AuthProblem(AuthError.UNAVAILABLE)

internal fun sha256Base64(value: ByteArray): String = base64Url(MessageDigest.getInstance("SHA-256").digest(value))

internal fun decodeBiometricChallenge(
    json: JSONObject,
    purpose: String,
    userId: String,
    publicKey: ByteArray,
    issuer: String,
    credentialId: String? = null,
    now: Long = System.currentTimeMillis(),
): BiometricChallenge {
    require(issuer.isNotBlank())
    require(json.keys().asSequence().toSet() ==
        setOf("credencialId", "desafioId", "proposito", "algoritmo", "mensaje", "expiraEn"))
    val credential = canonicalUuid(strictString(json, "credencialId"))
    require(credentialId == null || credentialId == credential)
    val challenge = strictString(json, "desafioId")
    require(decodeBase64Url(challenge, 32).size == 32)
    require(strictString(json, "proposito") == purpose && purpose in setOf("enrolar", "login"))
    require(strictString(json, "algoritmo") == "ECDSA_P256_SHA256_DER")
    val expiration = OffsetDateTime.parse(strictString(json, "expiraEn"))
    require(expiration.offset.totalSeconds == 0)
    val expiresAt = expiration.toInstant().toEpochMilli()
    // Five seconds of clock skew only relax the future bound, not the original expiry.
    val maxFutureMillis = 65_000L
    val latest = if (now > Long.MAX_VALUE - maxFutureMillis) Long.MAX_VALUE else now + maxFutureMillis
    require(expiresAt > now && expiresAt <= latest)
    val message = decodeBase64Url(strictString(json, "mensaje"), 2048)
    require(message.all { it in 0..127 })
    val lines = message.toString(Charsets.US_ASCII).split('\n')
    require(lines.size == 9 && lines == listOf(
        "SOLVENTA-BIOMETRIC-V1", purpose, "movil", sha256Base64(issuer.toByteArray(Charsets.UTF_8)),
        challenge, credential, canonicalUuid(userId), sha256Base64(publicKey), expiration.toEpochSecond().toString(),
    ))
    return BiometricChallenge(credential, challenge, message, expiresAt)
}

internal interface BiometricApi {
    fun beginEnrollment(token: String, password: String, publicKey: ByteArray): JSONObject
    fun confirmEnrollment(token: String, challenge: BiometricChallenge, signature: ByteArray)
    fun biometricChallenge(credentialId: String): JSONObject
    fun biometricLogin(challenge: BiometricChallenge, signature: ByteArray): AuthGrant
    fun biometricCredentials(token: String): List<RemoteBiometricCredential>
    fun revokeBiometricCredential(token: String, credentialId: String)
}
