package co.solventa.mobile.feature.evidence

import co.solventa.mobile.BuildConfig
import co.solventa.mobile.feature.auth.AuthHttpError
import co.solventa.mobile.feature.auth.AuthRepository
import co.solventa.mobile.feature.auth.AuthGrant
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONException
import org.json.JSONObject

internal data class EvidenceClaim(val id: String, val type: String, val date: String, val description: String)
internal data class EvidenceConfiguration(val policy: EvidencePolicy, val maxChunkBytes: Int)

internal class MobileEvidenceApi internal constructor(private val auth: AuthRepository, private val base: String) {
    @Inject constructor(auth: AuthRepository) : this(auth, BuildConfig.AUTH_BASE_URL)

    suspend fun claims(actor: String): List<EvidenceClaim> = session(actor) { grant ->
        val entries = request("GET", "/siniestros", grant.accessToken).getJSONArray("siniestros")
        (0 until entries.length()).map { index ->
            val item = entries.getJSONObject(index)
            EvidenceClaim(uuid(item.getString("id")), item.getString("tipoEvento"), item.getString("fechaEvento"),
                item.getString("descripcion"))
        }
    }

    suspend fun configuration(actor: String): EvidenceConfiguration = session(actor) { grant ->
        val data = request("GET", "/siniestros/configuracion-evidencias", grant.accessToken)
        val formats = data.getJSONArray("formatos")
        val sizes = buildMap {
            for (i in 0 until formats.length()) {
                val format = formats.getJSONObject(i)
                for (kind in EvidenceKind.entries.filter { it.mime == format.getString("mimeType") }) {
                    put(kind, format.getLong("maxBytes"))
                }
            }
        }
        val chunk = data.getInt("maxChunkBytes")
        require(chunk in 1..16 * 1024 * 1024)
        val seconds = data.getLong("maxVideoSeconds")
        require(seconds in 1..Long.MAX_VALUE / 1000)
        EvidenceConfiguration(EvidencePolicy(data.getInt("maxCantidad"), sizes, seconds * 1000), chunk)
    }

    suspend fun confirmedIds(scope: EvidenceScope): Set<String> = session(scope.actorId) { grant ->
        val entries = request("GET", "/siniestros/${uuid(scope.claimId)}/evidencias", grant.accessToken).getJSONArray("evidencias")
        (0 until entries.length()).map { uuid(entries.getJSONObject(it).getString("id")) }.toSet()
    }

    fun transport(store: EvidenceStore, configuration: EvidenceConfiguration): EvidenceTransport = object : EvidenceTransport {
        override suspend fun cancel(item: EvidenceItem): EvidenceReceipt? = session(item.scope.actorId) { grant ->
            val load = try {
                load(item, store, grant.accessToken, allowCancelled = true)
            } catch (error: EvidenceHttpProblem) {
                if (store.uploadId(item) == null && error.permanent) return@session null
                throw error
            }
            if (load.getString("estado") == "confirmada") return@session receipt(item, load)
            if (load.getString("estado") == "cancelada") return@session null
            val path = "/siniestros/${uuid(item.scope.claimId)}/cargas/${uuid(load.getString("id"))}"
            try {
                request("DELETE", path, grant.accessToken)
                null
            } catch (error: EvidenceHttpProblem) {
                if (error.code != "carga_confirmada") throw error
                receipt(item, request("GET", path, grant.accessToken))
            }
        }

        override suspend fun confirmed(scope: EvidenceScope, localId: String): EvidenceReceipt? {
            val item = store.list(scope).single { it.id == localId }
            return session(scope.actorId) { grant ->
                val load = load(item, store, grant.accessToken)
                if (load.getString("estado") == "confirmada") receipt(item, load) else null
            }
        }

        override suspend fun upload(item: EvidenceItem, file: File, progress: (Long) -> Unit): EvidenceReceipt {
            val coroutine = currentCoroutineContext()
            return session(item.scope.actorId) { grant ->
                var load = load(item, store, grant.accessToken)
                if (load.getString("estado") == "confirmada") return@session receipt(item, load)
                val path = "/siniestros/${uuid(item.scope.claimId)}/cargas/${uuid(load.getString("id"))}"
                var offset = load.getLong("offset")
                progress(offset)
                RandomAccessFile(file, "r").use { input ->
                    input.seek(offset)
                    val buffer = ByteArray(configuration.maxChunkBytes)
                    while (offset < item.bytes) {
                        coroutine.ensureActive()
                        val count = minOf(buffer.size.toLong(), item.bytes - offset).toInt()
                        input.readFully(buffer, 0, count)
                        load = request("PUT", "$path/contenido", grant.accessToken,
                            bytes = buffer.copyOf(count), offset = offset)
                        verifyLoad(item, load)
                        if (load.getLong("offset") != offset + count) throw EvidenceProblem(EvidenceError.PROTOCOL)
                        offset += count
                        progress(offset)
                    }
                }
                coroutine.ensureActive()
                receipt(item, request("POST", "$path/confirmacion", grant.accessToken))
            }
        }
    }

    private suspend fun <T> session(actor: String, action: (AuthGrant) -> T): T {
        try {
            return try {
                auth.withSession(actor, action)
            } catch (error: AuthHttpError) {
                if (error.status != 401) throw error
                auth.refreshSession(actor)
                // Every evidence mutation is idempotent; uploads re-read the server offset.
                auth.withSession(actor, action)
            }
        } catch (error: JSONException) {
            throw EvidenceProblem(EvidenceError.PROTOCOL, error)
        } catch (error: IllegalArgumentException) {
            throw EvidenceProblem(EvidenceError.PROTOCOL, error)
        }
    }

    private fun load(item: EvidenceItem, store: EvidenceStore, token: String, allowCancelled: Boolean = false): JSONObject {
        val path = "/siniestros/${uuid(item.scope.claimId)}/cargas"
        val saved = store.uploadId(item)
        val load = if (saved != null) request("GET", "$path/${uuid(saved)}", token)
        else request("POST", path, token, body = JSONObject().apply {
            put("mimeType", item.kind.mime); put("tamanoBytes", item.bytes); put("sha256", item.sha256)
            put("fechaCaptura", item.capturedAt.toString())
            item.location?.let {
                put("ubicacion", JSONObject().put("consentimiento", true).put("latitud", it.latitude)
                    .put("longitud", it.longitude).put("fecha", it.measuredAt.toString()))
            }
        }, idempotencyKey = item.id)
        verifyLoad(item, load, allowCancelled)
        store.saveUploadId(item, uuid(load.getString("id")))
        return load
    }

    private fun verifyLoad(item: EvidenceItem, load: JSONObject, allowCancelled: Boolean = false) {
        val states = if (allowCancelled) setOf("pendiente", "confirmada", "cancelada") else setOf("pendiente", "confirmada")
        if (load.getString("siniestroId") != item.scope.claimId || load.getString("sha256") != item.sha256 ||
            load.getLong("tamanoBytes") != item.bytes || load.getString("mimeType") != item.kind.mime ||
            load.getLong("offset") !in 0..item.bytes || load.getString("estado") !in states) {
            throw EvidenceProblem(EvidenceError.PROTOCOL)
        }
        uuid(load.getString("id"))
    }

    private fun receipt(item: EvidenceItem, load: JSONObject): EvidenceReceipt {
        verifyLoad(item, load)
        if (load.getString("estado") != "confirmada" || load.getLong("offset") != item.bytes) {
            throw EvidenceProblem(EvidenceError.PROTOCOL)
        }
        return EvidenceReceipt(item.id, uuid(load.getString("evidenciaId")), item.sha256)
    }

    private fun request(
        method: String, path: String, token: String, body: JSONObject? = null,
        idempotencyKey: String? = null, bytes: ByteArray? = null, offset: Long? = null,
    ): JSONObject {
        val connection = URL(base + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.useCaches = false
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-store")
            idempotencyKey?.let { connection.setRequestProperty("Idempotency-Key", it) }
            offset?.let { connection.setRequestProperty("Upload-Offset", it.toString()) }
            val payload = bytes ?: body?.toString()?.toByteArray(Charsets.UTF_8)
            if (payload != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", if (bytes != null) "application/octet-stream" else "application/json")
                connection.setFixedLengthStreamingMode(payload.size)
                connection.outputStream.use { it.write(payload) }
            }
            val status = connection.responseCode
            if (status == 401) throw AuthHttpError(status)
            if (status == 204 && method == "DELETE") return JSONObject()
            if (status in 200..299 && (status !in setOf(200, 201) || method == "DELETE")) throw EvidenceProblem(EvidenceError.PROTOCOL)
            val response = (if (status in 200..299) connection.inputStream else connection.errorStream
                ?: throw EvidenceProblem(EvidenceError.NETWORK)).use { input ->
                val data = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (data.size() + count > 1_048_576) throw EvidenceProblem(EvidenceError.PROTOCOL)
                    data.write(buffer, 0, count)
                }
                data.toString(Charsets.UTF_8.name())
            }
            val json = JSONObject(response)
            if (status !in 200..299) {
                val code = json.getString("code")
                val rejection = when (code) {
                    "limite_tamano" -> EvidenceError.SIZE
                    "formato_no_permitido", "formato_invalido", "integridad_invalida" -> EvidenceError.FORMAT
                    "video_fuera_limite" -> EvidenceError.DURATION
                    "ubicacion_no_autorizada" -> EvidenceError.LOCATION
                    "limite_cantidad" -> EvidenceError.COUNT
                    else -> null
                }
                throw EvidenceHttpProblem(status, code, rejection != null && status in setOf(409, 413, 415, 422),
                    rejection ?: when (status) {
                        403, 404 -> EvidenceError.SESSION
                        409, 422, 400 -> EvidenceError.PROTOCOL
                        else -> EvidenceError.NETWORK
                    })
            }
            return json
        } catch (error: EvidenceProblem) {
            throw error
        } catch (error: JSONException) {
            throw EvidenceProblem(EvidenceError.PROTOCOL, error)
        } catch (error: IOException) {
            throw EvidenceProblem(EvidenceError.NETWORK, error)
        } finally {
            connection.disconnect()
        }
    }
}

private fun uuid(value: String): String = UUID.fromString(value).toString().also { require(it == value) }
