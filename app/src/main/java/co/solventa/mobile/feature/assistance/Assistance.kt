package co.solventa.mobile.feature.assistance

import java.time.Instant
import java.util.UUID

internal enum class AssistanceLocationOrigin(val code: String) {
    PERMISSION("permiso"),
    MANUAL("manual"),
}

internal data class AssistanceLocation(
    val latitude: Double,
    val longitude: Double,
    val origin: AssistanceLocationOrigin,
    val confirmedAt: Instant,
) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
    }
}

internal data class AssistanceProvider(
    val id: String,
    val name: String,
    val serviceType: String,
    val approximateDistanceKm: Double,
) {
    init {
        require(UUID.fromString(id).toString() == id)
        require(name.isNotBlank() && serviceType.isNotBlank())
        require(approximateDistanceKm.isFinite() && approximateDistanceKm >= 0)
    }
}

internal data class AssistanceReceipt(
    val id: String,
    val status: String,
    val providerId: String,
    val serviceType: String,
    val createdAt: Instant,
) {
    init {
        require(UUID.fromString(id).toString() == id)
        require(status == "registrada")
        require(UUID.fromString(providerId).toString() == providerId)
        require(serviceType.isNotBlank())
    }
}

internal enum class AssistanceError {
    PERMISSION,
    LOCATION_UNAVAILABLE,
    INVALID_LOCATION,
    NO_PROVIDER,
    PROVIDER_UNAVAILABLE,
    RESOURCE_UNAUTHORIZED,
    NETWORK,
    SESSION,
    PROTOCOL,
    CONFLICT,
}

internal class AssistanceProblem(val reason: AssistanceError, cause: Throwable? = null) :
    Exception(reason.name, cause)
