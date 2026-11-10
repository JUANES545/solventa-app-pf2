package co.solventa.mobile.feature.notification

import android.app.Application
import android.os.Looper
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

private class FakeNotificationApi : NotificationApi {
    var values = listOf(
        MobileNotification(
            notificationId,
            NotificationKind.CLAIMS,
            "Actualización del siniestro",
            "Consulta el estado en Solventa.",
            NotificationResource(NotificationResourceKind.CLAIM, notificationResourceId),
            Instant.parse("2026-11-05T20:10:00Z"),
        ),
    )
    var push = true
    var error: NotificationError? = null
    var notificationsGate: CompletableDeferred<Unit>? = null
    val updates = mutableListOf<Boolean>()

    override suspend fun notifications(actor: String): List<MobileNotification> {
        notificationsGate?.await()
        error?.let { throw NotificationProblem(it) }
        return values
    }

    override suspend fun preferences(actor: String): List<NotificationPreference> =
        NotificationKind.entries.flatMap { kind ->
            NotificationChannel.entries.map { channel ->
                NotificationPreference(
                    kind,
                    channel,
                    if (channel == NotificationChannel.PUSH) push else channel == NotificationChannel.IN_APP,
                )
            }
        }

    override suspend fun updatePush(
        actor: String,
        enabled: Boolean,
    ): List<NotificationPreference> {
        updates += enabled
        push = enabled
        return preferences(actor)
    }
}

private class MemoryNotificationStore : NotificationLocalStore {
    val seen = mutableSetOf<String>()
    val read = mutableSetOf<String>()

    override fun seen(actor: String): Set<String> = seen.toSet()
    override fun read(actor: String): Set<String> = read.toSet()
    override fun recordSeen(actor: String, ids: Set<String>): Set<String> =
        (ids - seen).also { seen += ids }

    override fun markRead(actor: String, id: String) {
        read += id
    }

    override fun markAllRead(actor: String, ids: Set<String>) {
        read += ids
    }
}

private class FakePresenter(var granted: Boolean = true) : NotificationPresenter {
    val shown = mutableListOf<MobileNotification>()
    override fun permissionGranted(): Boolean = granted
    override fun show(notification: MobileNotification) {
        shown += notification
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class NotificationViewModelTest {
    private fun await(model: NotificationViewModel) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (model.state.value.busy && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertFalse("La operación no terminó", model.state.value.busy)
        assertFalse(model.state.value.loadingNotifications)
    }

    @Test
    fun synchronizationPresentsEachIdentifierOnceAndKeepsReadState() {
        val gate = CompletableDeferred<Unit>()
        val api = FakeNotificationApi().apply { notificationsGate = gate }
        val store = MemoryNotificationStore()
        val presenter = FakePresenter()
        val model = NotificationViewModel(api, store, presenter)

        model.bindActor("11111111-1111-4111-8111-111111111111")
        assertTrue(model.state.value.loadingNotifications)
        assertTrue(model.state.value.busy)
        assertTrue(model.state.value.items.isEmpty())
        gate.complete(Unit)
        await(model)
        model.synchronize("11111111-1111-4111-8111-111111111111")
        await(model)

        assertEquals(listOf(notificationId), presenter.shown.map { it.id })
        assertEquals(1, model.state.value.items.size)
        assertFalse(model.state.value.items.single().read)
        model.markAllRead()
        assertTrue(model.state.value.items.single().read)
        assertEquals(setOf(notificationId), store.read)
    }

    @Test
    fun targetWaitsForAuthenticationAndOnlyAuthorizedClaimNavigates() {
        val api = FakeNotificationApi()
        val model = NotificationViewModel(api, MemoryNotificationStore(), FakePresenter())
        model.acceptTarget(notificationId, "siniestro", notificationResourceId)
        assertNull(model.state.value.navigation)

        model.bindActor("11111111-1111-4111-8111-111111111111")
        await(model)

        assertEquals(NotificationNavigation.Claim(notificationResourceId), model.state.value.navigation)
        model.consumeNavigation()
        assertNull(model.state.value.navigation)

        model.acceptTarget(
            "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            "siniestro",
            notificationResourceId,
        )
        model.synchronize("11111111-1111-4111-8111-111111111111")
        await(model)
        assertNull(model.state.value.navigation)
        assertEquals(NotificationMessage.RESOURCE_UNAVAILABLE, model.state.value.message)
    }

    @Test
    fun deniedPermissionDisablesPushAndNeverPostsAnAlert() {
        val api = FakeNotificationApi()
        val presenter = FakePresenter(granted = false)
        val model = NotificationViewModel(api, MemoryNotificationStore(), presenter)
        model.bindActor("11111111-1111-4111-8111-111111111111")
        await(model)
        model.permissionResult(false)
        assertFalse(model.state.value.loadingNotifications)
        await(model)

        assertEquals(listOf(false), api.updates)
        assertTrue(presenter.shown.isEmpty())
        assertFalse(model.state.value.pushEnabled)
        assertEquals(NotificationMessage.PERMISSION_DENIED, model.state.value.message)
    }

    @Test
    fun unavailableResourcesAndNetworkErrorsDoNotSimulateSuccess() {
        val api = FakeNotificationApi()
        api.values = api.values.map {
            it.copy(resource = NotificationResource(NotificationResourceKind.PAYMENT, notificationResourceId))
        }
        val model = NotificationViewModel(api, MemoryNotificationStore(), FakePresenter())
        model.bindActor("11111111-1111-4111-8111-111111111111")
        await(model)
        model.open(model.state.value.items.single())
        assertNull(model.state.value.navigation)
        assertTrue(model.state.value.open)
        assertEquals(NotificationMessage.RESOURCE_UNAVAILABLE, model.state.value.message)

        api.error = NotificationError.NETWORK
        model.synchronize("11111111-1111-4111-8111-111111111111")
        await(model)
        assertEquals(NotificationError.NETWORK, model.state.value.error)
        assertEquals(1, model.state.value.items.size)
    }
}
