package co.solventa.mobile.mock

import co.solventa.mobile.feature.auth.AuthError
import co.solventa.mobile.feature.auth.AuthHttpError
import co.solventa.mobile.feature.auth.AuthProblem
import co.solventa.mobile.feature.notification.NotificationError
import co.solventa.mobile.feature.notification.NotificationProblem
import kotlinx.coroutines.CancellationException

internal fun mockNotificationFailure(error: Exception): String = when (error) {
    is CancellationException -> throw error
    is AuthProblem -> if (error.reason == AuthError.STORAGE) "No se pudo guardar la sesión"
        else "Inicia sesión en Solventa para enviar el aviso"
    is AuthHttpError -> if (error.status == 401 || error.status == 403)
        "Inicia sesión en Solventa para enviar el aviso" else "No se pudo comprobar la sesión"
    is SecurityException -> "Activa los avisos de Solventa en ajustes"
    is IllegalArgumentException -> "El aviso o el siniestro no son válidos"
    is IllegalStateException -> "Activa los avisos y selecciona Con información"
    is NotificationProblem -> if (error.reason == NotificationError.STORAGE)
        "No se pudo guardar el aviso" else "No se pudo enviar el aviso"
    is java.io.IOException -> "No se pudo guardar o consultar el aviso"
    else -> "No se pudo enviar el aviso"
}
