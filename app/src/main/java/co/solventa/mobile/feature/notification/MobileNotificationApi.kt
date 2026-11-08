package co.solventa.mobile.feature.notification

import co.solventa.mobile.BuildConfig
import co.solventa.mobile.feature.auth.AuthGrant
import co.solventa.mobile.feature.auth.AuthHttpError
import co.solventa.mobile.feature.auth.AuthProblem
import co.solventa.mobile.feature.auth.AuthRepository
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID
import javax.inject.Inject
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal class MobileNotificationApi internal constructor(
    private val auth: AuthRepository,
    private val base: String,
) : NotificationApi {
    @Inject constructor(auth: AuthRepository) : this(auth, BuildConfig.AUTH_BASE_URL)

    override suspend fun notifications(actor: String): List<MobileNotification> =
        session(actor) { grant ->
            val entries = request("GET", "/notificaciones", grant.accessToken)
                .getJSONArray("notificaciones")
            require(entries.length() <= 50)
            (0 until entries.length()).map { parseNotification(entries.getJSONObject(it)) }
                .groupBy(MobileNotification::id)
                .map { (_, repeated) ->
                    require(repeated.distinct().size == 1)
                    repeated.first()
                }
        }

    override suspend fun preferences(actor: String): List<NotificationPreference> =
        session(actor) { grant ->
            parsePreferences(
                request("GET", "/notificaciones/preferencias", grant.accessToken)
                    .getJSONArray("preferencias"),
            )
        }

    override suspend fun updatePush(
        actor: String,
        enabled: Boolean,
    ): List<NotificationPreference> = session(actor) { grant ->
        val body = JSONObject().put(
            "preferencias",
            JSONArray(
                NotificationKind.entries.map { kind ->
                    JSONObject()
                        .put("tipo", kind.code)
                        .put("canal", NotificationChannel.PUSH.code)
                        .put("habilitada", enabled)
                },
            ),
        )
        parsePreferences(
            request("PUT", "/notificaciones/preferencias", grant.accessToken, body)
                .getJSONArray("preferencias"),
        )
    }

    private suspend fun <T> session(actor: String, action: (AuthGrant) -> T): T {
        try {
            return try {
                auth.withSession(actor, action)
            } catch (error: AuthHttpError) {
                if (error.status != 401) throw error
                auth.refreshSession(actor)
                auth.withSession(actor, action)
            }
        } catch (error: AuthProblem) {
            throw NotificationProblem(NotificationError.SESSION, error)
        } catch (error: AuthHttpError) {
            throw NotificationProblem(
                if (error.status in setOf(401, 403)) NotificationError.SESSION
                else NotificationError.NETWORK,
                error,
            )
        } catch (error: JSONException) {
            throw NotificationProblem(NotificationError.PROTOCOL, error)
        } catch (error: IllegalArgumentException) {
            throw NotificationProblem(NotificationError.PROTOCOL, error)
        } catch (error: DateTimeParseException) {
            throw NotificationProblem(NotificationError.PROTOCOL, error)
        }
    }

    private fun request(
        method: String,
        path: String,
        token: String,
        body: JSONObject? = null,
    ): JSONObject {
        val connection = URL(base + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.useCaches = false
            connection.setRequestProperty("Authorization", "******")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-store")
            body?.toString()?.toByteArray(Charsets.UTF_8)?.let { payload ->
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setFixedLengthStreamingMode(payload.size)
                connection.outputStream.use { it.write(payload) }
            }
            val status = connection.responseCode
            if (status == 401) throw AuthHttpError(status)
            val input = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = input?.use { stream ->
                val data = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (data.size() + count > MAX_RESPONSE_BYTES) {
                        throw NotificationProblem(NotificationError.PROTOCOL)
                    }
                    data.write(buffer, 0, count)
                }
                data.toString(Charsets.UTF_8.name())
            }.orEmpty()
            if (status != 200) {
                throw NotificationProblem(
                    if (status in setOf(401, 403)) NotificationError.SESSION
                    else NotificationError.NETWORK,
                )
            }
            return JSONObject(response)
        } catch (error: NotificationProblem) {
            throw error
        } catch (error: IOException) {
            throw NotificationProblem(NotificationError.NETWORK, error)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseNotification(value: JSONObject): MobileNotification {
        requireKeys(
            value,
            setOf("notificacionId", "tipo", "titulo", "contenido", "recurso", "entregadaEn"),
        )
        val title = value.getString("titulo").also { require(it.isNotBlank() && it.length <= 80) }
        val content =
            value.getString("contenido").also { require(it.isNotBlank() && it.length <= 240) }
        val resource = value.getJSONObject("recurso")
        requireKeys(resource, setOf("tipo", "id"))
        val kind = NotificationKind.from(value.getString("tipo"))
        val resourceKind = NotificationResourceKind.from(resource.getString("tipo"))
        require(
            when (kind) {
                NotificationKind.PAYMENTS -> resourceKind == NotificationResourceKind.PAYMENT
                NotificationKind.CLAIMS -> resourceKind == NotificationResourceKind.CLAIM
                NotificationKind.EXPIRATIONS,
                NotificationKind.RENEWALS,
                -> resourceKind == NotificationResourceKind.POLICY
            },
        )
        return MobileNotification(
            id = uuid(value.getString("notificacionId")),
            kind = kind,
            title = title,
            content = content,
            resource = NotificationResource(
                resourceKind,
                uuid(resource.getString("id")),
            ),
            deliveredAt = Instant.parse(value.getString("entregadaEn")),
        )
    }

    private fun parsePreferences(entries: JSONArray): List<NotificationPreference> {
        require(entries.length() == NotificationKind.entries.size * NotificationChannel.entries.size)
        return (0 until entries.length()).map { index ->
            entries.getJSONObject(index).let { value ->
                requireKeys(value, setOf("tipo", "canal", "habilitada"))
                NotificationPreference(
                    NotificationKind.from(value.getString("tipo")),
                    NotificationChannel.from(value.getString("canal")),
                    value.getBoolean("habilitada"),
                )
            }
        }.also { preferences ->
            require(preferences.map { it.kind to it.channel }.distinct().size == preferences.size)
        }
    }

    private fun requireKeys(value: JSONObject, expected: Set<String>) {
        require(value.keys().asSequence().toSet() == expected)
    }

    private fun uuid(value: String): String =
        UUID.fromString(value).toString().also { require(it == value) }

    private companion object {
        const val MAX_RESPONSE_BYTES = 1_048_576
    }
}
