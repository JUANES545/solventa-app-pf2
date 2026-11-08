package co.solventa.mobile.feature.assistance

import co.solventa.mobile.feature.auth.AuthHttpError
import co.solventa.mobile.feature.auth.AuthProblem
import co.solventa.mobile.feature.evidence.EvidenceError
import co.solventa.mobile.feature.evidence.EvidenceHttpProblem
import co.solventa.mobile.feature.evidence.EvidenceProblem
import co.solventa.mobile.feature.evidence.MobileEvidenceApi
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import org.json.JSONException
import org.json.JSONObject

internal class MobileAssistanceApi @Inject constructor(
    private val http: MobileEvidenceApi,
) : AssistanceGateway {
    override suspend fun providers(
        actor: String,
        location: AssistanceLocation,
        radiusKm: Int,
    ): List<AssistanceProvider> = execute {
        http.session(actor) { grant ->
            val response = http.request(
                "POST",
                "/prestadores/consulta",
                grant.accessToken,
                body = locationPayload(location).put("radioKm", radiusKm),
            )
            exact(response, setOf("prestadores"))
            val entries = response.getJSONArray("prestadores")
            (0 until entries.length()).map { index ->
                val item = entries.getJSONObject(index)
                exact(item, setOf("id", "nombre", "tipoServicio", "distanciaKm"))
                AssistanceProvider(
                    UUID.fromString(item.getString("id")).toString(),
                    item.getString("nombre"),
                    item.getString("tipoServicio"),
                    item.getDouble("distanciaKm"),
                )
            }.also { providers ->
                require(providers.map(AssistanceProvider::id).distinct().size == providers.size)
                require(
                    providers.map(AssistanceProvider::approximateDistanceKm) ==
                        providers.map(AssistanceProvider::approximateDistanceKm).sorted(),
                )
            }
        }
    }

    override suspend fun request(
        actor: String,
        claimId: String,
        provider: AssistanceProvider,
        location: AssistanceLocation,
        operationId: String,
    ): AssistanceReceipt = execute {
        val operation = UUID.fromString(operationId).toString()
        val claim = UUID.fromString(claimId).toString()
        http.session(actor) { grant ->
            val response = http.request(
                "POST",
                "/asistencias",
                grant.accessToken,
                body = locationPayload(location).apply {
                    put("prestadorId", provider.id)
                    put("tipoAsistencia", provider.serviceType)
                    put("siniestroId", claim)
                },
                idempotencyKey = operation,
            )
            exact(
                response,
                setOf("id", "estado", "prestadorId", "tipoAsistencia", "creadaEn"),
            )
            AssistanceReceipt(
                UUID.fromString(response.getString("id")).toString(),
                response.getString("estado"),
                UUID.fromString(response.getString("prestadorId")).toString(),
                response.getString("tipoAsistencia"),
                Instant.parse(response.getString("creadaEn")),
            ).also {
                require(it.providerId == provider.id && it.serviceType == provider.serviceType)
            }
        }
    }

    private fun locationPayload(location: AssistanceLocation) = JSONObject().put(
        "ubicacion",
        JSONObject()
            .put("latitud", location.latitude)
            .put("longitud", location.longitude)
            .put("origen", location.origin.code)
            .put("confirmada", true)
            .put("confirmadaEn", location.confirmedAt.toString()),
    )

    private suspend fun <T> execute(action: suspend () -> T): T = try {
        action()
    } catch (error: EvidenceHttpProblem) {
        throw AssistanceProblem(
            when (error.code) {
                "prestador_no_disponible" -> AssistanceError.PROVIDER_UNAVAILABLE
                "recurso_no_autorizado" -> AssistanceError.RESOURCE_UNAUTHORIZED
                "idempotencia_conflictiva" -> AssistanceError.CONFLICT
                "ubicacion_no_vigente" -> AssistanceError.INVALID_LOCATION
                else -> when (error.reason) {
                    EvidenceError.SESSION -> AssistanceError.SESSION
                    EvidenceError.NETWORK -> AssistanceError.NETWORK
                    else -> AssistanceError.PROTOCOL
                }
            },
            error,
        )
    } catch (error: EvidenceProblem) {
        throw AssistanceProblem(
            if (error.reason == EvidenceError.NETWORK) {
                AssistanceError.NETWORK
            } else {
                AssistanceError.PROTOCOL
            },
            error,
        )
    } catch (error: AuthHttpError) {
        throw AssistanceProblem(AssistanceError.SESSION, error)
    } catch (error: AuthProblem) {
        throw AssistanceProblem(AssistanceError.SESSION, error)
    } catch (error: JSONException) {
        throw AssistanceProblem(AssistanceError.PROTOCOL, error)
    } catch (error: IllegalArgumentException) {
        throw AssistanceProblem(AssistanceError.PROTOCOL, error)
    }

    private fun exact(value: JSONObject, fields: Set<String>) {
        require(value.keys().asSequence().toSet() == fields)
    }
}
