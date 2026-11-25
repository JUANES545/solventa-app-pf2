package co.solventa.mobile.feature.policy

import java.security.MessageDigest

/** Comprueba los bytes recibidos, antes de abrir el destino que eligió el cliente. */
internal fun policyDocumentMatches(bytes: ByteArray, sha256: String?): Boolean {
    if (bytes.isEmpty() || sha256 == null || !sha256.matches(Regex("[0-9a-fA-F]{64}"))) return false
    val expected = ByteArray(32) { sha256.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    return MessageDigest.isEqual(expected, MessageDigest.getInstance("SHA-256").digest(bytes))
}
