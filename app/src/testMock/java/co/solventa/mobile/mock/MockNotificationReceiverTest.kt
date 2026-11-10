package co.solventa.mobile.mock

import co.solventa.mobile.feature.auth.AuthError
import co.solventa.mobile.feature.auth.AuthHttpError
import co.solventa.mobile.feature.auth.AuthProblem
import co.solventa.mobile.feature.notification.NotificationError
import co.solventa.mobile.feature.notification.NotificationProblem
import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class MockNotificationReceiverTest {
    @Test
    fun failuresUseFixedSpanishResultsWithoutExceptionDetails() {
        val privateDetail = "internal-private-path-and-token"
        val cases = listOf(
            AuthProblem(AuthError.EXPIRED) to "Inicia sesión en Solventa para enviar el aviso",
            AuthProblem(AuthError.STORAGE) to "No se pudo guardar la sesión",
            AuthHttpError(401) to "Inicia sesión en Solventa para enviar el aviso",
            AuthHttpError(503) to "No se pudo comprobar la sesión",
            SecurityException(privateDetail) to "Activa los avisos de Solventa en ajustes",
            IllegalArgumentException(privateDetail) to "El aviso o el siniestro no son válidos",
            IllegalStateException(privateDetail) to "Activa los avisos y selecciona Con información",
            NotificationProblem(NotificationError.STORAGE, Exception(privateDetail)) to "No se pudo guardar el aviso",
            IOException(privateDetail) to "No se pudo guardar o consultar el aviso",
            Exception(privateDetail) to "No se pudo enviar el aviso",
        )
        cases.forEach { (error, expected) ->
            assertEquals(expected, mockNotificationFailure(error))
            assertFalse(mockNotificationFailure(error).contains(privateDetail))
        }
    }

    @Test
    fun cancellationIsPropagatedRatherThanConvertedToAnOrdinaryFailure() {
        val cancelled = CancellationException("private-detail")
        try {
            mockNotificationFailure(cancelled)
            fail("La cancelación debe propagarse")
        } catch (actual: CancellationException) {
            assertSame(cancelled, actual)
        }
    }
}
