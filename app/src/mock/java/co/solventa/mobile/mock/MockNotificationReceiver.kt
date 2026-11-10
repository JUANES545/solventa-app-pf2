package co.solventa.mobile.mock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import co.solventa.mobile.feature.auth.AuthError
import co.solventa.mobile.feature.auth.AuthHttpError
import co.solventa.mobile.feature.auth.AuthProblem
import co.solventa.mobile.feature.auth.AuthRepository
import co.solventa.mobile.feature.notification.MobileNotification
import co.solventa.mobile.feature.notification.NotificationKind
import co.solventa.mobile.feature.notification.NotificationLocalStore
import co.solventa.mobile.feature.notification.NotificationPresenter
import co.solventa.mobile.feature.notification.NotificationResource
import co.solventa.mobile.feature.notification.NotificationResourceKind
import co.solventa.mobile.feature.notification.NotificationProblem
import co.solventa.mobile.feature.notification.NotificationError
import dagger.hilt.android.AndroidEntryPoint
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MockNotificationReceiver : BroadcastReceiver() {
    @Inject lateinit var auth: AuthRepository
    @Inject internal lateinit var store: NotificationLocalStore
    @Inject internal lateinit var presenter: NotificationPresenter

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val pending = goAsync()
        val eventId = intent.getStringExtra("event_id")
        val claim = intent.getStringExtra("claim_id") ?: MockBackend.CLAIM
        CoroutineScope(Dispatchers.IO).launch {
            try {
                require(eventId != null && UUID.fromString(eventId).toString() == eventId)
                val delivered = auth.withSession(MockBackend.ACTOR) { grant ->
                    val actor = grant.user.id
                    if (!presenter.permissionGranted()) throw SecurityException()
                    val value = MockInitializer.backend.queueNotice(eventId, claim)
                    synchronized(store) {
                        val discarded = eventId in store.discarded(actor)
                        val seen = eventId in store.seen(actor)
                        if (!seen && !discarded) {
                            presenter.show(MobileNotification(eventId, NotificationKind.CLAIMS,
                                value.getString("titulo"), value.getString("contenido"),
                                NotificationResource(NotificationResourceKind.CLAIM, claim),
                                Instant.parse(value.getString("entregadaEn"))))
                            store.recordSeen(actor, setOf(eventId))
                        }
                        !seen && !discarded
                    }
                }
                pending.setResult(0, if (delivered) "Aviso enviado" else "Aviso ya recibido", null)
            } catch (cancelled: CancellationException) {
                pending.setResult(1, "Envío cancelado", null)
                throw cancelled
            } catch (error: Exception) {
                val message = mockNotificationFailure(error)
                Log.w("MockNotification", message)
                pending.setResult(1, message, null)
            } finally {
                pending.finish()
            }

        }

    }

    companion object {
        const val ACTION = "co.solventa.mobile.mock.SEND_NOTIFICATION"
    }
}
