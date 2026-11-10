package co.solventa.mobile.feature.notification

import java.time.Instant

internal enum class NotificationKind(val code: String) {
    PAYMENTS("pagos"),
    CLAIMS("siniestros"),
    EXPIRATIONS("vencimientos"),
    RENEWALS("renovaciones");

    companion object {
        fun from(code: String): NotificationKind =
            entries.singleOrNull { it.code == code } ?: throw IllegalArgumentException("Tipo desconocido")
    }
}

internal enum class NotificationChannel(val code: String) {
    IN_APP("in_app"),
    EMAIL("email"),
    PUSH("push");

    companion object {
        fun from(code: String): NotificationChannel =
            entries.singleOrNull { it.code == code } ?: throw IllegalArgumentException("Canal desconocido")
    }
}

internal enum class NotificationResourceKind(val code: String) {
    PAYMENT("pago"),
    CLAIM("siniestro"),
    POLICY("poliza");

    companion object {
        fun from(code: String): NotificationResourceKind =
            entries.singleOrNull { it.code == code } ?: throw IllegalArgumentException("Recurso desconocido")
    }
}

internal data class NotificationResource(val kind: NotificationResourceKind, val id: String)

internal data class MobileNotification(
    val id: String,
    val kind: NotificationKind,
    val title: String,
    val content: String,
    val resource: NotificationResource,
    val deliveredAt: Instant,
)

internal data class NotificationPreference(
    val kind: NotificationKind,
    val channel: NotificationChannel,
    val enabled: Boolean,
)

internal data class NotificationItem(val notification: MobileNotification, val read: Boolean)

internal data class NotificationUndo(
    val actor: String,
    val id: String,
    val revision: Long,
    val read: Boolean,
    val discarded: Boolean,
)

internal data class NotificationTarget(
    val notificationId: String,
    val resource: NotificationResource,
)

internal enum class NotificationError { NETWORK, SESSION, UNAVAILABLE, PROTOCOL, STORAGE }

internal class NotificationProblem(val reason: NotificationError, cause: Throwable? = null) :
    Exception(cause)

internal interface NotificationApi {
    suspend fun notifications(actor: String): List<MobileNotification>
    suspend fun preferences(actor: String): List<NotificationPreference>
    suspend fun updatePush(actor: String, enabled: Boolean): List<NotificationPreference>
}

internal interface NotificationLocalStore {
    fun seen(actor: String): Set<String>
    fun read(actor: String): Set<String>
    fun recordSeen(actor: String, ids: Set<String>): Set<String>
    fun markRead(actor: String, id: String)
    fun markAllRead(actor: String, ids: Set<String>)
    fun discarded(actor: String): Set<String>
    fun change(actor: String, id: String, read: Boolean? = null, discarded: Boolean? = null): NotificationUndo
    fun undo(actor: String, change: NotificationUndo): Boolean
}

internal interface NotificationPresenter {
    fun permissionGranted(): Boolean
    fun show(notification: MobileNotification)
    fun clear() {}
}
