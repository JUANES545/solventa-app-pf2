package co.solventa.mobile.feature.notification

import android.app.Application
import co.solventa.mobile.feature.evidence.evidenceAuth
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

internal val notificationId = "c0ca4037-5e08-4ddf-967a-377b5e420201"
internal val notificationResourceId = "c0ca4037-5e08-4ddf-967a-377b5e420202"
internal val deliveredAt: Instant = Instant.parse("2026-11-05T20:10:00Z")

internal fun notificationJson(
    id: String = notificationId,
    kind: String = "siniestros",
    resourceKind: String = "siniestro",
    resourceId: String = notificationResourceId,
): JSONObject = JSONObject()
    .put("notificacionId", id)
    .put("tipo", kind)
    .put("titulo", "Tu siniestro tiene una actualización")
    .put("contenido", "Consulta el estado y el historial de tu siniestro en Solventa.")
    .put("recurso", JSONObject().put("tipo", resourceKind).put("id", resourceId))
    .put("entregadaEn", deliveredAt.toString())

internal fun notificationsResponse(vararg values: JSONObject = arrayOf(notificationJson())): String =
    JSONObject().put("notificaciones", JSONArray(values.toList())).toString()

internal fun preferencesResponse(push: Boolean = false): String = JSONObject().put(
    "preferencias",
    JSONArray(
        NotificationKind.entries.flatMap { kind ->
            NotificationChannel.entries.map { channel ->
                JSONObject()
                    .put("tipo", kind.code)
                    .put("canal", channel.code)
                    .put("habilitada", if (channel == NotificationChannel.PUSH) push else channel == NotificationChannel.IN_APP)
            }
        },
    ),
).toString()

internal fun notificationApi(server: MockWebServer) =
    MobileNotificationApi(evidenceAuth(), server.url("/movil/v1").toString())

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class NotificationApiTest {
    @Test
    fun readsAuthorizedMinimizedNotificationsAndUpdatesAllPushPreferences() = runBlocking {
        MockWebServer().use { server ->
            val api = notificationApi(server)
            server.enqueue(MockResponse().setBody(notificationsResponse()))
            val values = api.notifications("actor-a")
            assertEquals(1, values.size)
            assertEquals(notificationId, values.single().id)
            assertEquals(NotificationResourceKind.CLAIM, values.single().resource.kind)
            assertEquals(deliveredAt, values.single().deliveredAt)
            val listRequest = server.takeRequest()
            assertEquals("GET", listRequest.method)
            assertEquals("/movil/v1/notificaciones", listRequest.path)
            assertNotNull(listRequest.getHeader("Authorization"))
            assertEquals("no-store", listRequest.getHeader("Cache-Control"))

            server.enqueue(MockResponse().setBody(preferencesResponse()))
            assertFalse(
                api.preferences("actor-a")
                    .single {
                        it.kind == NotificationKind.PAYMENTS &&
                            it.channel == NotificationChannel.PUSH
                    }.enabled,
            )
            server.takeRequest()

            server.enqueue(MockResponse().setBody(preferencesResponse(push = true)))
            val updated = api.updatePush("actor-a", true)
            assertEquals(4, updated.count { it.channel == NotificationChannel.PUSH && it.enabled })
            val update = server.takeRequest()
            assertEquals("/movil/v1/notificaciones/preferencias", update.path)
            assertEquals("PUT", update.method)
            val sent = JSONObject(update.body.readUtf8()).getJSONArray("preferencias")
            assertEquals(4, sent.length())
            assertEquals(
                NotificationKind.entries.map { it.code }.toSet(),
                (0 until sent.length()).map { sent.getJSONObject(it).getString("tipo") }.toSet(),
            )
        }

        @Test
        fun supportsEveryEventTypeOnlyWithItsExpectedResource() = runBlocking {
            MockWebServer().use { server ->
                val api = notificationApi(server)
                val events = listOf(
                    "pagos" to "pago",
                    "siniestros" to "siniestro",
                    "vencimientos" to "poliza",
                    "renovaciones" to "poliza",
                ).mapIndexed { index, (kind, resource) ->
                    notificationJson(
                        id = "c0ca4037-5e08-4ddf-967a-377b5e42020${index + 3}",
                        kind = kind,
                        resourceKind = resource,
                    )
                }
                server.enqueue(MockResponse().setBody(notificationsResponse(*events.toTypedArray())))
                assertEquals(NotificationKind.entries.toSet(), api.notifications("actor-a").map { it.kind }.toSet())

                server.enqueue(
                    MockResponse().setBody(
                        notificationsResponse(
                            notificationJson(kind = "pagos", resourceKind = "siniestro"),
                        ),
                    ),
                )
                try {
                    api.notifications("actor-a")
                    fail()
                } catch (problem: NotificationProblem) {
                    assertEquals(NotificationError.PROTOCOL, problem.reason)
                }
            }
        }
    }

    @Test
    fun repeatedIdentifierIsDeduplicatedButConflictsAndSensitiveExtrasAreRejected() = runBlocking {
        MockWebServer().use { server ->
            val api = notificationApi(server)
            val repeated = notificationJson()
            server.enqueue(MockResponse().setBody(notificationsResponse(repeated, repeated)))
            assertEquals(1, api.notifications("actor-a").size)

            val conflicting = notificationJson().put("titulo", "Título diferente")
            val sensitive = notificationJson().put("numeroCuenta", "123456789")
            for (response in listOf(
                notificationsResponse(notificationJson(), conflicting),
                notificationsResponse(sensitive),
            )) {
                server.enqueue(MockResponse().setBody(response))
                try {
                    api.notifications("actor-a")
                    fail("La respuesta insegura fue aceptada")
                } catch (problem: NotificationProblem) {
                    assertEquals(NotificationError.PROTOCOL, problem.reason)
                }
            }
        }
    }

    @Test
    fun invalidResourcesDatesPreferencesAndFailuresNeverLookSuccessful() = runBlocking {
        MockWebServer().use { server ->
            val api = notificationApi(server)
            val invalidBodies = listOf(
                notificationsResponse(notificationJson(resourceKind = "cuenta")),
                notificationsResponse(notificationJson(resourceId = "../otro")),
                notificationsResponse(notificationJson().put("entregadaEn", "2026-11-05T20:10:00")),
                """{"notificaciones":{}}""",
            )
            for (body in invalidBodies) {
                server.enqueue(MockResponse().setBody(body))
                try {
                    api.notifications("actor-a")
                    fail()
                } catch (problem: NotificationProblem) {
                    assertEquals(NotificationError.PROTOCOL, problem.reason)
                }
            }
            server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
            try {
                api.notifications("actor-a")
                fail()
            } catch (problem: NotificationProblem) {
                assertEquals(NotificationError.NETWORK, problem.reason)
            }
            server.enqueue(MockResponse().setResponseCode(403).setBody("{}"))
            try {
                api.notifications("actor-a")
                fail()
            } catch (problem: NotificationProblem) {
                assertEquals(NotificationError.SESSION, problem.reason)
            }
        }
    }

    @Test
    fun foreignActorCannotUseTheStoredSession() = runBlocking {
        MockWebServer().use { server ->
            try {
                notificationApi(server).notifications(UUID.randomUUID().toString())
                fail()
            } catch (problem: NotificationProblem) {
                assertEquals(NotificationError.SESSION, problem.reason)
            }
            assertEquals(0, server.requestCount)
        }
    }
}
