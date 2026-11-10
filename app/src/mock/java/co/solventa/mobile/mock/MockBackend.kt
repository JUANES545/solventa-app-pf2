package co.solventa.mobile.mock

import co.solventa.mobile.feature.auth.base64Url
import co.solventa.mobile.feature.auth.decodeBase64Url
import co.solventa.mobile.feature.auth.sha256Base64
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal enum class MockScenario(val label: String) {
    DATA("Con información"), EMPTY("Sin registros"), SERVICE("Servicio no disponible · 503"),
    FORBIDDEN("Sin permisos · 403"), MISSING("Recurso no disponible · 404"),
    MALFORMED("Respuesta inválida"), REFRESH("Sesión renovable · un 401"),
    EXPIRED("Sesión rechazada · 401 persistente"), DISCONNECTED("Conexión interrumpida"),
    ACK_LOST("Confirmación perdida después de guardar"),
}

internal class MockBackend(private val file: File? = null) : Dispatcher() {
    private val data = if (file != null && file.exists()) JSONObject(file.readText())
        else JSONObject().put("claims", JSONArray().put(seedClaim())).put("uploads", JSONObject())
            .put("operations", JSONObject()).put("credentials", JSONObject())
    @Volatile var scenario = MockScenario.valueOf(data.optString("scenario", MockScenario.DATA.name))
        private set
    var delayMillis = 450L
    private var refreshPending = scenario == MockScenario.REFRESH
    private val challenges = mutableMapOf<String, JSONObject>()

    init {
        val claims = data.getJSONArray("claims")
        if (!data.optBoolean("expandedFixtures")) {
            listOf(
                Triple("11111111-1111-4111-8111-111111111112", "Rotura de vidrio", "Medellín"),
                Triple("11111111-1111-4111-8111-111111111122", "Daño por lluvia", "Cali"),
                Triple("11111111-1111-4111-8111-111111111132", "Golpe en parqueadero", "Bogotá"),
            ).forEachIndexed { index, (id, event, place) ->
                if (!hasClaim(id)) claims.put(seedClaim().put("id", id).put("tipoEvento", event)
                    .put("lugar", place).put("descripcion", "Reporte de $event en revisión.")
                    .put("fechaEvento", Instant.now().minusSeconds((index + 2L) * 86400).toString())
                    .put("registradoEn", Instant.now().minusSeconds((index + 2L) * 86000).toString()))
            }
            data.put("expandedFixtures", true)
        }
        if (!data.has("notices")) {
            data.put("notices", JSONArray().put(notice(NOTICE, CLAIM, "Reporte recibido",
                "Consulta el avance de tu siniestro.", claims.getJSONObject(0).getString("registradoEn"))))
        }
        if (!data.optBoolean("expandedNotices")) {
            listOf("11111111-1111-4111-8111-111111111112", "11111111-1111-4111-8111-111111111122",
                "11111111-1111-4111-8111-111111111132").forEachIndexed { index, claim ->
                val value = claims.getJSONObject((0 until claims.length()).first { claims.getJSONObject(it).getString("id") == claim })
                val noticeId = "11111111-1111-4111-8111-1111111111${index + 4}6"
                val notices = data.getJSONArray("notices")
                if ((0 until notices.length()).none { notices.getJSONObject(it).getString("notificacionId") == noticeId }) {
                notices.put(notice(
                    noticeId, claim,
                    listOf("Revisión de vidrios", "Reporte en revisión", "Seguimiento del vehículo")[index],
                    "Consulta el estado de tu reporte de ${value.getString("tipoEvento").lowercase()}.",
                    value.getString("registradoEn"),
                ))
                }
            }
            data.put("expandedNotices", true)
        }
        val notices = data.getJSONArray("notices")
        for (index in 0 until notices.length()) {
            notices.getJSONObject(index).put("entregadaEn",
                Instant.parse("2026-11-07T15:00:00Z").minusSeconds(index * 86400L).toString())
        }
        save()
    }

    @Synchronized fun queueNotice(eventId: String, claim: String): JSONObject {
        require(UUID.fromString(eventId).toString() == eventId && hasClaim(claim))
        check(scenario == MockScenario.DATA && data.optBoolean("push", true))
        val entries = data.getJSONArray("notices")
        val existing = (0 until entries.length()).map(entries::getJSONObject)
            .firstOrNull { it.getString("notificacionId") == eventId }
        if (existing != null) {
            require(existing.getJSONObject("recurso").getString("id") == claim)
            save()
            return JSONObject(existing.toString())
        }
        val value = notice(eventId, claim, "Tu siniestro tiene novedades",
            "Consulta el avance y las evidencias de tu reporte.", "2026-11-07T15:00:00Z")
        entries.put(value)
        save()
        return JSONObject(value.toString())
    }

    @Synchronized fun choose(value: MockScenario) {
        scenario = value
        refreshPending = value == MockScenario.REFRESH
        save()
    }

    @Synchronized fun removeEmptyTestClaims(candidates: Set<String>, protectedOperations: Set<String>): Set<String> {
        val claims = data.getJSONArray("claims")
        val uploads = data.getJSONObject("uploads")
        val notices = data.getJSONArray("notices")
        val operations = data.getJSONObject("operations")
        val removed = (0 until claims.length()).map(claims::getJSONObject).filter { claim ->
            val id = claim.getString("id")
            id in candidates && claim.optString("polizaId") == POLICY &&
                claim.optString("lugar") == "Lugar de prueba" &&
                when (claim.optString("tipoEvento")) {
                    "Prueba de fotografía" -> claim.optString("descripcion") in setOf(
                        "Fotografía sintética para comprobar carga y restauración.",
                        "Fotografía de práctica para comprobar carga y restauración.",
                    )
                    "Prueba de vídeo" -> claim.optString("descripcion") == "Clip de tráfico de dominio público."
                    "Choque de prueba" -> claim.optString("descripcion") ==
                        "El vehículo recibió un golpe en el costado derecho. La puerta y el guardabarros presentan daños visibles. Adjunto fotografías para su revisión."
                    else -> false
                } &&
                uploads.keys().asSequence().none { uploads.getJSONObject(it).optString("siniestroId") == id } &&
                (0 until notices.length()).none {
                    notices.getJSONObject(it).getJSONObject("recurso").optString("id") == id
                } &&
                operations.keys().asSequence().none { operation ->
                    val entry = operations.getJSONObject(operation)
                    JSONObject(entry.getString("payload")).optString("siniestroId") == id ||
                        (operation in protectedOperations && entry.getJSONObject("response").optString("id") == id)
                }
        }.map { it.getString("id") }.toSet()
        if (removed.isNotEmpty()) {
            for (index in claims.length() - 1 downTo 0) {
                if (claims.getJSONObject(index).getString("id") in removed) claims.remove(index)
            }
            // Retain the idempotency ledger so a late replay cannot create another test report.
            save()
        }
        return removed
    }

    @Synchronized override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.requestUrl?.encodedPath ?: return problem(404, "ruta_no_encontrada")
        if (!path.startsWith(PREFIX)) return problem(404, "ruta_no_encontrada")
        val tail = path.removePrefix(PREFIX)
        val method = request.method ?: return problem(405, "metodo_no_permitido")
        return try {
            val public = tail == "/usuarios" || tail == "/sesiones" || tail == "/sesiones/renovar" ||
                tail == "/biometria/desafios" || tail == "/sesiones/biometria"
            if (!public && request.getHeader("Authorization") != "Bearer $ACCESS") {
                return problem(401, "sesion_invalida")
            }
            val operational = tail.startsWith("/siniestros") || tail.startsWith("/notificaciones") ||
                tail == "/prestadores/consulta" || tail == "/asistencias"
            if (operational) {
                when (scenario) {
                    MockScenario.SERVICE -> return problem(503, "servicio_no_disponible")
                    MockScenario.FORBIDDEN -> return problem(403, "alcance_insuficiente")
                    MockScenario.MISSING -> return problem(404,
                        if (tail.startsWith("/siniestros/")) "siniestro_no_encontrado" else "ruta_no_encontrada")
                    MockScenario.MALFORMED -> return json(JSONObject())
                    MockScenario.EXPIRED -> return problem(401, "sesion_invalida")
                    MockScenario.DISCONNECTED -> return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                    MockScenario.REFRESH -> if (refreshPending) {
                        refreshPending = false
                        return problem(401, "sesion_invalida")
                    }
                    else -> Unit
                }
            }
            val response = route(method, tail, request)
            save()
            if (operational && scenario == MockScenario.ACK_LOST && method in setOf("POST", "PUT", "DELETE")) {
                return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
            }
            response.setBodyDelay(delayMillis, TimeUnit.MILLISECONDS)
        } catch (_: JSONException) {
            problem(400, "solicitud_invalida")
        } catch (_: IllegalArgumentException) {
            problem(400, "solicitud_invalida")
        }
    }

    private fun route(method: String, path: String, request: RecordedRequest): MockResponse {
        fun body() = JSONObject(request.body.readUtf8())
        when (method to path) {
            "POST" to "/usuarios" -> return if (validLogin(body())) json(user(), 201) else problem(400, "datos_ficticios_requeridos")
            "POST" to "/sesiones" -> return if (validLogin(body())) json(grant()) else problem(401, "credenciales_invalidas")
            "POST" to "/sesiones/renovar" -> return if (body().optString("refreshToken") == REFRESH)
                json(grant()) else problem(401, "sesion_invalida")
            "GET" to "/sesiones/actual" -> return json(user())
            "DELETE" to "/sesiones/actual" -> return MockResponse().setResponseCode(204)
            "GET" to "/siniestros" -> return json(JSONObject().put("siniestros",
                if (scenario == MockScenario.EMPTY) JSONArray() else data.getJSONArray("claims")))
            "GET" to "/siniestros/polizas" -> return json(JSONObject().put("polizas",
                if (scenario == MockScenario.EMPTY) JSONArray() else JSONArray().put(JSONObject()
                    .put("id", POLICY).put("tipo", "Vehículo").put("estado", "vigente")
                    .put("fechaInicio", LocalDate.now().minusYears(1)).put("fechaFin", LocalDate.now().plusYears(1)))))
            "GET" to "/siniestros/configuracion-evidencias" -> return json(JSONObject()
                .put("maxCantidad", 8).put("maxChunkBytes", 64 * 1024).put("maxVideoSeconds", 20)
                .put("formatos", JSONArray(listOf("image/jpeg", "application/pdf", "video/mp4").map {
                    JSONObject().put("mimeType", it).put("maxBytes", 10 * 1024 * 1024)
                })))
            "POST" to "/siniestros" -> {
                val value = body()
                if (value.getString("polizaId") != POLICY) return problem(404, "poliza_no_encontrada")
                val operation = operation(request)
                val payload = value.toString()
                replay(operation, payload)?.let { return it }
                value.put("id", id()).put("estado", "registrado").put("registradoEn", now())
                data.getJSONArray("claims").put(value)
                remember(operation, payload, value)
                return json(value, 201)
            }
            "GET" to "/notificaciones" -> return json(JSONObject().put("notificaciones",
                if (scenario == MockScenario.EMPTY) JSONArray() else notifications()))
            "GET" to "/notificaciones/preferencias" -> return json(preferences())
            "PUT" to "/notificaciones/preferencias" -> {
                val values = body().getJSONArray("preferencias")
                data.put("push", values.getJSONObject(0).getBoolean("habilitada"))
                return json(preferences())
            }
            "POST" to "/prestadores/consulta" -> return json(JSONObject().put("prestadores",
                if (scenario == MockScenario.EMPTY) JSONArray() else JSONArray().put(JSONObject()
                    .put("id", PROVIDER).put("nombre", "Asistencia vial").put("tipoServicio", "grua")
                    .put("distanciaKm", 2.4))))
            "POST" to "/asistencias" -> {
                val value = body()
                if (!hasClaim(value.getString("siniestroId")) || value.getString("prestadorId") != PROVIDER) {
                    return problem(403, "recurso_no_autorizado")
                }
                val operation = operation(request)
                val payload = value.toString()
                replay(operation, payload)?.let { return it }
                val result = JSONObject().put("id", id()).put("estado", "registrada")
                    .put("prestadorId", PROVIDER).put("tipoAsistencia", value.getString("tipoAsistencia")).put("creadaEn", now())
                remember(operation, payload, result)
                return json(result, 201)
            }
        }
        if (path.startsWith("/biometria") || path == "/sesiones/biometria") return biometric(method, path, request)
        val segments = path.trim('/').split('/')
        if (segments.first() != "siniestros" || segments.size < 2) return problem(404, "ruta_no_encontrada")
        val claim = segments[1]
        if (!hasClaim(claim)) return problem(404, "siniestro_no_encontrado")
        if (method == "GET" && segments.size == 3 && segments[2] == "estado") {
            return json(JSONObject().put("id", claim).put("estado", "reportado")
                .put("descripcion", "Recibimos tu reporte.").put("actualizadoEn", now()))
        }
        if (method == "GET" && segments.size == 3 && segments[2] == "evidencias") {
            val entries = data.getJSONObject("uploads").keys().asSequence().map { data.getJSONObject("uploads").getJSONObject(it) }
                .filter { it.getString("siniestroId") == claim && it.getString("estado") == "confirmada" }
                .map { JSONObject().put("id", it.getString("evidenciaId")) }.toMutableList()
            return json(JSONObject().put("evidencias", JSONArray(entries)))
        }
        if (method == "DELETE" && segments.size == 4 && segments[2] == "evidencias") {
            val uploads = data.getJSONObject("uploads")
            val removed = data.optJSONObject("deletedEvidence") ?: JSONObject().also { data.put("deletedEvidence", it) }
            if (removed.optString(segments[3]) == claim) return MockResponse().setResponseCode(204)
            val key = uploads.keys().asSequence().firstOrNull {
                val value = uploads.getJSONObject(it)
                value.optString("siniestroId") == claim && value.optString("estado") == "confirmada" &&
                    value.optString("evidenciaId") == segments[3]
            } ?: return problem(404, "evidencia_no_encontrada")
            uploads.remove(key)
            removed.put(segments[3], claim)
            return MockResponse().setResponseCode(204)
        }
        if (segments.getOrNull(2) != "cargas") return problem(404, "ruta_no_encontrada")
        val uploads = data.getJSONObject("uploads")
        if (method == "POST" && segments.size == 3) {
            val value = body()
            val operation = operation(request)
            val key = sha256Base64("$claim:$operation".toByteArray())
            val existing = uploads.optJSONObject(key)
            if (existing != null) {
                if (existing.getString("sha256") != value.getString("sha256") ||
                    existing.getLong("tamanoBytes") != value.getLong("tamanoBytes") ||
                    existing.getString("mimeType") != value.getString("mimeType")) return problem(409, "idempotencia_conflictiva")
                return json(publicUpload(existing))
            }
            if (value.getLong("tamanoBytes") !in 1..10 * 1024 * 1024) return problem(413, "limite_tamano")
            value.put("id", id()).put("siniestroId", claim).put("offset", 0).put("estado", "pendiente").put("bytes", "")
            uploads.put(key, value)
            return json(publicUpload(value), 201)
        }
        val upload = uploads.keys().asSequence().map { uploads.getJSONObject(it) }
            .firstOrNull { it.getString("id") == segments.getOrNull(3) && it.getString("siniestroId") == claim }
            ?: return problem(404, "carga_no_encontrada")
        if (method == "GET" && segments.size == 4) return json(publicUpload(upload))
        if (method == "DELETE" && segments.size == 4) {
            if (upload.getString("estado") == "confirmada") return problem(409, "carga_confirmada")
            upload.put("estado", "cancelada")
            return MockResponse().setResponseCode(204)
        }
        if (upload.getString("estado") != "pendiente") return problem(409, "carga_no_pendiente")
        if (method == "PUT" && segments.getOrNull(4) == "contenido") {
            if (request.getHeader("Upload-Offset")?.toLongOrNull() != upload.getLong("offset")) return problem(409, "offset_invalido")
            val bytes = Base64.getDecoder().decode(upload.getString("bytes")) + request.body.readByteArray()
            if (bytes.size > upload.getLong("tamanoBytes")) return problem(413, "limite_tamano")
            upload.put("offset", bytes.size).put("bytes", Base64.getEncoder().encodeToString(bytes))
            return json(publicUpload(upload))
        }
        if (method == "POST" && segments.getOrNull(4) == "confirmacion") {
            val bytes = Base64.getDecoder().decode(upload.getString("bytes"))
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            if (bytes.size.toLong() != upload.getLong("tamanoBytes") || hash != upload.getString("sha256")) {
                return problem(422, "integridad_invalida")
            }
            upload.put("estado", "confirmada").put("evidenciaId", id())
            return json(publicUpload(upload))
        }
        return problem(404, "ruta_no_encontrada")
    }

    private fun biometric(method: String, path: String, request: RecordedRequest): MockResponse {
        val credentials = data.getJSONObject("credentials")
        if (method == "GET" && path == "/biometria/credenciales") {
            return json(JSONObject().put("credenciales", JSONArray(credentials.keys().asSequence().map {
                JSONObject().put("credencialId", it).put("creadaEn", credentials.getJSONObject(it).getString("created"))
                    .put("ultimoUsoEn", JSONObject.NULL).put("estado", credentials.getJSONObject(it).getString("state"))
            }.toList())))
        }
        if (method == "DELETE" && path.startsWith("/biometria/credenciales/")) {
            val credential = credentials.optJSONObject(path.substringAfterLast('/')) ?: return problem(404, "credencial_no_encontrada")
            credential.put("state", "revocada")
            return MockResponse().setResponseCode(204)
        }
        val value = JSONObject(request.body.readUtf8())
        if (method == "POST" && path in setOf("/biometria/enrolamientos/desafio", "/biometria/desafios")) {
            val enrollment = path == "/biometria/enrolamientos/desafio"
            if (enrollment && value.getString("contrasena") != PASSWORD) return problem(401, "credenciales_invalidas")
            val credentialId = if (enrollment) id() else value.getString("credencialId")
            val credential = if (enrollment) JSONObject().put("key", value.getString("clavePublica")).put("state", "pendiente").put("created", now())
                else credentials.optJSONObject(credentialId) ?: return problem(404, "credencial_no_encontrada")
            if (!enrollment && credential.getString("state") != "activa") return problem(401, "credencial_revocada")
            val key = decodeBase64Url(credential.getString("key"), 91)
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(key))
            if (enrollment) credentials.put(credentialId, credential)
            val challenge = base64Url(ByteArray(32).also { SecureRandom().nextBytes(it) })
            val expiry = Instant.now().plusSeconds(60)
            val purpose = if (enrollment) "enrolar" else "login"
            val message = listOf("SOLVENTA-BIOMETRIC-V1", purpose, "movil",
                sha256Base64("urn:solventa:mock".toByteArray()), challenge, credentialId, ACTOR,
                sha256Base64(key), expiry.epochSecond.toString()).joinToString("\n").toByteArray()
            val result = JSONObject().put("credencialId", credentialId).put("desafioId", challenge)
                .put("proposito", purpose).put("algoritmo", "ECDSA_P256_SHA256_DER")
                .put("mensaje", base64Url(message)).put("expiraEn", expiry.toString())
            challenges[challenge] = result
            return json(result)
        }
        if (method == "POST" && path in setOf("/biometria/enrolamientos", "/sesiones/biometria")) {
            val challenge = challenges.remove(value.getString("desafioId")) ?: return problem(401, "desafio_invalido")
            val credentialId = value.getString("credencialId")
            val purpose = if (path == "/sesiones/biometria") "login" else "enrolar"
            if (challenge.getString("credencialId") != credentialId || challenge.getString("proposito") != purpose ||
                Instant.parse(challenge.getString("expiraEn")).isBefore(Instant.now())) return problem(401, "desafio_invalido")
            val credential = credentials.getJSONObject(credentialId)
            val verified = Signature.getInstance("SHA256withECDSA").run {
                initVerify(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(decodeBase64Url(credential.getString("key"), 91))))
                update(decodeBase64Url(challenge.getString("mensaje"), 2048))
                verify(decodeBase64Url(value.getString("firma"), 72))
            }
            if (!verified || credential.getString("state") == "revocada") return problem(401, "firma_invalida")
            credential.put("state", "activa")
            return if (purpose == "login") json(grant()) else json(JSONObject().put("credencialId", credentialId).put("estado", "activa"), 201)
        }
        return problem(404, "ruta_no_encontrada")
    }

    private fun preferences() = JSONObject().put("preferencias", JSONArray(
        listOf("pagos", "siniestros", "vencimientos", "renovaciones").flatMap { kind ->
            listOf("in_app", "email", "push").map { channel -> JSONObject().put("tipo", kind)
                .put("canal", channel).put("habilitada", channel != "push" || data.optBoolean("push", true)) }
        }))

    private fun notifications() = data.getJSONArray("notices")

    private fun notice(id: String, claim: String, title: String, content: String, delivered: String) =
        JSONObject().put("notificacionId", id).put("tipo", "siniestros").put("titulo", title)
            .put("contenido", content).put("recurso", JSONObject().put("tipo", "siniestro").put("id", claim))
            .put("entregadaEn", delivered)

    private fun hasClaim(id: String) = data.getJSONArray("claims").let { entries ->
        (0 until entries.length()).any { entries.getJSONObject(it).getString("id") == id }
    }
    private fun publicUpload(value: JSONObject) = JSONObject(value.toString()).apply { remove("bytes") }
    private fun operation(request: RecordedRequest) = UUID.fromString(checkNotNull(request.getHeader("Idempotency-Key"))).toString()
    private fun replay(operation: String, payload: String): MockResponse? {
        val previous = data.getJSONObject("operations").optJSONObject(operation) ?: return null
        return if (previous.getString("payload") == payload) json(previous.getJSONObject("response"))
            else problem(409, "idempotencia_conflictiva")
    }
    private fun remember(operation: String, payload: String, response: JSONObject) {
        data.getJSONObject("operations").put(operation, JSONObject().put("payload", payload).put("response", response))
    }
    private fun save() {
        data.put("scenario", scenario.name)
        val target = file ?: return
        val temporary = File(target.parentFile, "${target.name}.new")
        FileOutputStream(temporary).use { stream ->
            stream.write(data.toString().toByteArray())
            stream.fd.sync()
        }
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
    private fun validLogin(value: JSONObject) = value.optString("correo") == EMAIL && value.optString("contrasena") == PASSWORD
    private fun user() = JSONObject().put("usuario", JSONObject().put("id", ACTOR)
        .put("nombre", "Cliente").put("correo", EMAIL).put("rol", "cliente"))
    private fun grant() = user().put("tokenType", "Bearer").put("accessToken", ACCESS).put("refreshToken", REFRESH)
        .put("expiraEn", Instant.now().plusSeconds(900).toString()).put("refreshExpiraEn", Instant.now().plusSeconds(86400).toString())
    private fun json(body: JSONObject, status: Int = 200) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json").setHeader("Cache-Control", "no-store").setBody(body.toString())
        .setBodyDelay(delayMillis, TimeUnit.MILLISECONDS)
    private fun problem(status: Int, code: String) = json(JSONObject().put("code", code).put("status", status), status)
    private fun now() = Instant.now().toString()
    private fun id() = UUID.randomUUID().toString()
    private fun seedClaim() = JSONObject().put("id", CLAIM).put("polizaId", POLICY)
        .put("tipoEvento", "Choque").put("fechaEvento", Instant.now().minusSeconds(86400).toString())
        .put("lugar", "Bogotá").put("descripcion", "Daño en el costado del vehículo.")
        .put("estado", "registrado").put("registradoEn", Instant.now().minusSeconds(86000).toString())

    companion object {
        const val EMAIL = "cliente@solventa.test"
        const val PASSWORD = "Solventa123!"
        const val ACTOR = "11111111-1111-4111-8111-111111111101"
        const val CLAIM = "11111111-1111-4111-8111-111111111102"
        const val POLICY = "11111111-1111-4111-8111-111111111103"
        const val PROVIDER = "11111111-1111-4111-8111-111111111104"
        const val NOTICE = "11111111-1111-4111-8111-111111111106"
        const val ACCESS = "synthetic-access"
        const val REFRESH = "synthetic-refresh"
        const val PREFIX = "/movil/v1"
    }
}
