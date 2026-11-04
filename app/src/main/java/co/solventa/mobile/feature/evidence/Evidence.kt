package co.solventa.mobile.feature.evidence

import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.UUID

internal enum class EvidenceKind(val mime: String, val extension: String) {
    PHOTO("image/jpeg", "jpg"), DOCUMENT("image/jpeg", "jpg"), VIDEO("video/mp4", "mp4"),
}

internal data class EvidenceScope(val actorId: String, val claimId: String) {
    init {
        require(actorId.isNotBlank() && claimId.isNotBlank())
    }
}

// Supplied by the authorized claim's backend contract; there are no product defaults.
internal data class EvidencePolicy(
    val maxCount: Int,
    val maxBytes: Map<EvidenceKind, Long>,
    val maxVideoDurationMillis: Long,
) {
    init {
        require(maxCount > 0 && maxBytes.isNotEmpty())
        require(maxBytes.values.all { it > 0 })
        require(EvidenceKind.VIDEO !in maxBytes || maxVideoDurationMillis > 0)
    }

    fun validate(kind: EvidenceKind, bytes: Long, durationMillis: Long = 0) {
        val limit = maxBytes[kind] ?: throw EvidenceProblem(EvidenceError.FORMAT)
        if (bytes !in 1..limit) throw EvidenceProblem(EvidenceError.SIZE)
        if (kind == EvidenceKind.VIDEO && durationMillis !in 1..maxVideoDurationMillis) {
            throw EvidenceProblem(EvidenceError.DURATION)
        }
    }
}

internal data class EvidenceLocation(val latitude: Double, val longitude: Double, val measuredAt: Instant) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
    }
}

internal enum class EvidenceError { CAMERA, PERMISSION, FORMAT, SIZE, DURATION, COUNT, STORAGE, NETWORK, SESSION, LOCATION, PROTOCOL }
internal open class EvidenceProblem(val reason: EvidenceError, cause: Throwable? = null) : IOException(reason.name, cause)
internal class EvidenceHttpProblem(
    val status: Int, val code: String?, val permanent: Boolean, reason: EvidenceError, cause: Throwable? = null,
) : EvidenceProblem(reason, cause)
internal enum class EvidenceStatus { PENDING, CONFIRMED, REJECTED }

internal data class EvidenceItem(
    val id: String,
    val scope: EvidenceScope,
    val kind: EvidenceKind,
    val capturedAt: Instant,
    val location: EvidenceLocation?,
    val bytes: Long,
    val sha256: String,
    val durationMillis: Long,
    val status: EvidenceStatus = EvidenceStatus.PENDING,
    val remoteId: String? = null,
    val rejection: EvidenceError? = null,
) {
    init {
        require(UUID.fromString(id).toString() == id)
        require(bytes > 0 && sha256.matches(Regex("[0-9a-f]{64}")))
        require((status == EvidenceStatus.CONFIRMED) == !remoteId.isNullOrBlank())
        require((status == EvidenceStatus.REJECTED) == (rejection != null))
    }
}

internal data class EvidenceReceipt(val localId: String, val remoteId: String, val sha256: String)

// A future adapter must return only backend-confirmed receipts, never a successful PUT alone.
internal interface EvidenceTransport {
    suspend fun confirmed(scope: EvidenceScope, localId: String): EvidenceReceipt?
    suspend fun upload(item: EvidenceItem, file: File, progress: (Long) -> Unit): EvidenceReceipt
    suspend fun cancel(item: EvidenceItem): EvidenceReceipt? = throw EvidenceProblem(EvidenceError.PROTOCOL)
}
