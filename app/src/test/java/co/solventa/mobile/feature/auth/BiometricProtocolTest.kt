package co.solventa.mobile.feature.auth

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class BiometricProtocolTest {
    private val fixture = JSONObject(checkNotNull(javaClass.getResource("/biometric-vector-v1.json")).readText())
    private val publicKey get() = decodeBase64Url(fixture.getString("clavePublica"), 91)
    private val epoch get() = fixture.getLong("expEpoch") * 1000
    private fun challenge() = JSONObject().put("credencialId", fixture.getString("credencialId"))
        .put("desafioId", fixture.getString("desafioId")).put("proposito", "login")
        .put("algoritmo", fixture.getString("algoritmo")).put("mensaje", fixture.getString("mensaje"))
        .put("expiraEn", Instant.ofEpochMilli(epoch).toString())
    private fun decode(json: JSONObject = challenge(), issuer: String = fixture.getString("issuer"), now: Long = epoch - 30_000) =
        decodeBiometricChallenge(json, "login", fixture.getString("usuarioId"), publicKey, issuer,
            fixture.getString("credencialId"), now)

    @Test fun sixtySecondChallengeAllowsDeviceClockTwoSecondsBehind() {
        val parsed = decode(now = epoch - 62_000)
        assertEquals(epoch, parsed.expiresAt)
        assertArrayEquals(fixture.getString("mensajeTexto").toByteArray(), parsed.message)
    }

    @Test fun futureBoundAllowsExactlyFiveSecondsOfSkewButNotOneMillisecondMore() {
        assertEquals(epoch, decode(now = epoch - 65_000).expiresAt)
        assertThrows(IllegalArgumentException::class.java) { decode(now = epoch - 65_001) }
    }

    @Test fun skewNeverMakesAnExpiredOrDifferentlyBoundTimestampValid() {
        assertThrows(IllegalArgumentException::class.java) { decode(now = epoch) }
        assertThrows(IllegalArgumentException::class.java) { decode(now = epoch + 1) }
        assertThrows(IllegalArgumentException::class.java) {
            decode(challenge().put("expiraEn", Instant.ofEpochMilli(epoch + 1000).toString()), now = epoch - 62_000)
        }
    }

    @Test fun futureBoundDoesNotOverflowNearMaximumClockValue() {
        val expiresAt = Long.MAX_VALUE - 500
        val lines = fixture.getString("mensajeTexto").split('\n').toMutableList()
        lines[8] = Instant.ofEpochMilli(expiresAt).epochSecond.toString()
        val response = challenge().put("expiraEn", Instant.ofEpochMilli(expiresAt).toString())
            .put("mensaje", base64Url(lines.joinToString("\n").toByteArray()))
        assertEquals(expiresAt, decode(response, now = expiresAt - 1000).expiresAt)
        assertThrows(IllegalArgumentException::class.java) { decode(now = Long.MIN_VALUE) }
    }

    @Test fun sharedPythonVectorValidatesExactBytesAndDerSignatureInJava() {
        val parsed = decode()
        assertArrayEquals(fixture.getString("mensajeTexto").toByteArray(), parsed.message)
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKey))
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initVerify(key)
            update(parsed.message)
        }
        assertTrue(signature.verify(decodeBase64Url(fixture.getString("firma"), 72)))
        signature.update(parsed.message + byteArrayOf(10))
        assertFalse(signature.verify(decodeBase64Url(fixture.getString("firma"), 72)))
    }

    @Test fun issuerSlashMustNotBeNormalizedAndEveryMessageBindingMustMatch() {
        assertThrows(IllegalArgumentException::class.java) { decode(issuer = fixture.getString("issuer") + "/") }
        val lines = fixture.getString("mensajeTexto").split('\n')
        lines.indices.forEach { index ->
            val changed = lines.toMutableList().apply { this[index] += "x" }.joinToString("\n")
            assertThrows(IllegalArgumentException::class.java) {
                decode(challenge().put("mensaje", base64Url(changed.toByteArray())))
            }

        }
        listOf("\n", "\r", "\r\n").forEach { suffix ->
            assertThrows(IllegalArgumentException::class.java) {
                decode(challenge().put("mensaje", base64Url((fixture.getString("mensajeTexto") + suffix).toByteArray())))
            }
        }
    }

    @Test fun publicIssuerBindsExactOriginNotTheBffPathOrLocalEnvironment() {
        val issuer = "https://65qgzhody9.execute-api.us-east-2.amazonaws.com"
        val lines = fixture.getString("mensajeTexto").split('\n').toMutableList()
        lines[3] = sha256Base64(issuer.toByteArray(Charsets.UTF_8))
        val response = challenge().put("mensaje", base64Url(lines.joinToString("\n").toByteArray(Charsets.US_ASCII)))
        assertArrayEquals(lines.joinToString("\n").toByteArray(Charsets.US_ASCII), decode(response, issuer).message)
        for (other in listOf("$issuer/", "$issuer/movil/v1", "https://identidad.solventa.test",
            issuer.replace("https://", "http://"))) {
            assertThrows(IllegalArgumentException::class.java) { decode(response, other) }
        }
    }

    @Test fun malformedFieldsExpirationAndUnknownAlgorithmsNeverAuthorizeAChallenge() {
        listOf("proposito" to "enrolar", "algoritmo" to "ES256", "credencialId" to "INVALID",
            "desafioId" to "x", "mensaje" to "", "extra" to "unexpected").forEach { (field, value) ->
            assertThrows(IllegalArgumentException::class.java) { decode(challenge().put(field, value)) }
        }
        assertThrows(IllegalArgumentException::class.java) { decode(challenge().put("expiraEn", Instant.ofEpochMilli(epoch - 30_000).toString())) }
        assertThrows(IllegalArgumentException::class.java) { decode(challenge().put("expiraEn", Instant.ofEpochMilli(epoch + 60_000).toString())) }
        assertThrows(AuthProblem::class.java) { decode(challenge().put("algoritmo", 1)) }
    }

    @Test fun base64UrlIsCanonicalBoundedAndUnpadded() {
        listOf("AA==", "AA\n", "AA ", "AA+", "AB", "", "A").forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { decodeBase64Url(invalid, 32) }
        }
        assertEquals(32, decodeBase64Url(fixture.getString("desafioId"), 32).size)
        assertThrows(IllegalArgumentException::class.java) { decodeBase64Url(base64Url(ByteArray(33)), 32) }
    }
}
