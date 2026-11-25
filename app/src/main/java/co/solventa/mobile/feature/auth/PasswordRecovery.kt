package co.solventa.mobile.feature.auth

import java.math.BigInteger
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import org.json.JSONObject

internal fun recoveryToken(link: String, entry: String): String {
    val prefix = "$entry?recuperacion=1#token="
    if (!entry.startsWith("https://") || !link.startsWith(prefix)) throw AuthProblem(AuthError.RECOVERY_LINK)
    return link.removePrefix(prefix).takeIf { it.matches(Regex("[A-Za-z0-9_-]{64}")) }
        ?: throw AuthProblem(AuthError.RECOVERY_LINK)
}

internal fun encryptRecovery(challenge: JSONObject, token: String, password: String): String =
    encryptCredential(challenge, "solventa-reset+jwe", JSONObject().put("token",token).put("contrasena",password))

internal fun encryptCredential(challenge: JSONObject, type: String, payload: JSONObject): String {
    val jwk = challenge.getJSONObject("clavePublica")
    require(jwk.getString("kty") == "RSA")
    val modulus = BigInteger(1, decodeBase64Url(strictString(jwk, "n"), 1024))
    require(modulus.bitLength() >= 2048)
    val key = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus,
        BigInteger(1, decodeBase64Url(strictString(jwk, "e"), 8))))
    val header = base64Url(JSONObject().put("alg", "RSA-OAEP-256").put("enc", "A256GCM")
        .put("kid", strictString(jwk, "kid")).put("typ", type).toString().toByteArray())
    val aes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    val wrapped = Cipher.getInstance("RSA/ECB/OAEPPadding").run {
        init(Cipher.ENCRYPT_MODE, key, OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT))
        doFinal(aes.encoded)
    }
    val iv = ByteArray(12).also(SecureRandom()::nextBytes)
    val sealed = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, aes, GCMParameterSpec(128, iv))
        updateAAD(header.toByteArray(Charsets.US_ASCII))
        doFinal(payload.put("desafioId", strictString(challenge, "desafioId")).toString().toByteArray())
    }
    val split = sealed.size - 16
    return listOf(header, base64Url(wrapped), base64Url(iv), base64Url(sealed.copyOfRange(0, split)),
        base64Url(sealed.copyOfRange(split, sealed.size))).joinToString(".")
}
