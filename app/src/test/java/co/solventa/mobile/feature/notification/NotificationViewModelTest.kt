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
    var notificationQueries = 0
    val updates = mutableListOf<Boolean>()

    override suspend fun notifications(actor: String): List<MobileNotification> {
        notificationQueries++
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
    val discarded = mutableSetOf<String>()
    private val revisions = mutableMapOf<String, Long>()
    var failChanges = false
    override fun discarded(actor: String) = discarded.toSet()
    override fun change(actor: String, id: String, read: Boolean?, discarded: Boolean?): NotificationUndo {
        if (failChanges) throw NotificationProblem(NotificationError.STORAGE)
        val revision = (revisions[id] ?: 0) + 1
        val previous = NotificationUndo(actor, id, revision, id in this.read, id in this.discarded)
        revisions[id] = revision
        if (read == true) this.read += id else if (read == false) this.read -= id
        if (discarded == true) this.discarded += id else if (discarded == false) this.discarded -= id
        return previous
    }
    override fun undo(actor: String, change: NotificationUndo): Boolean {
        if (actor != change.actor || revisions[change.id] != change.revision) return false
        this.change(actor, change.id, change.read, change.discarded)
        return true
    }

    override fun seen(actor: String): Set<String> = seen.toSet()
    override fun read(actor: String): Set<String> = read.toSet()
    override fun recordSeen(actor: String, ids: Set<String>): Set<String> =
        (ids - seen).also { seen += ids }

    override fun markRead(actor: String, id: String) {
        change(actor, id, read = true)
    }

    override fun markAllRead(actor: String, ids: Set<String>) {
        ids.forEach { change(actor, it, read = true) }
    }
}

private class FakePresenter(var granted: Boolean = true) : NotificationPresenter {
    val shown = mutableListOf<MobileNotification>()
    var clears = 0
    override fun clear() { clears++ }
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
    fun grantedPermissionIsKnownBeforeLoadingButPreferencesAreNotAssumedDisabledOnReentry() {
        val actor = "11111111-1111-4111-8111-111111111111"
        val gate = CompletableDeferred<Unit>()
        val api = FakeNotificationApi().apply { notificationsGate = gate }
        val presenter = FakePresenter()
        val model = NotificationViewModel(api, MemoryNotificationStore(), presenter)
        assertTrue(model.state.value.permissionGranted)
        assertFalse(model.state.value.pushPreferencesKnown)
        model.show(actor)
        assertTrue(model.state.value.permissionGranted)
        assertFalse(model.state.value.pushPreferencesKnown)
        gate.complete(Unit)
        await(model)
        assertTrue(model.state.value.pushPreferencesKnown)
        assertTrue(model.state.value.pushEnabled)
        model.close()
        val returnGate = CompletableDeferred<Unit>()
        api.notificationsGate = returnGate
        model.show(actor)
        assertTrue(model.state.value.permissionGranted)
        assertFalse(model.state.value.pushPreferencesKnown)
        assertNull(model.state.value.message)
        returnGate.complete(Unit)
        await(model)
        assertTrue(model.state.value.pushEnabled)
    }

    @Test
    fun openingAndResumingRecheckRevocationAndClearDeniedMessageAfterSettingsGrant() {
        val actor = "11111111-1111-4111-8111-111111111111"
        val presenter = FakePresenter()
        val model = NotificationViewModel(FakeNotificationApi(), MemoryNotificationStore(), presenter)
        model.show(actor)
        await(model)
        model.close()
        presenter.granted = false
        model.show(actor)
        assertFalse(model.state.value.permissionGranted)
        await(model)
        model.permissionResult(false)
        await(model)
        assertEquals(NotificationMessage.PERMISSION_DENIED, model.state.value.message)
        presenter.granted = true
        model.refreshPermission()
        assertTrue(model.state.value.permissionGranted)
        assertNull(model.state.value.message)
        assertTrue(model.state.value.pushPreferencesKnown)
        assertFalse(model.state.value.pushEnabled)
        model.setPush(true)
        await(model)
        assertTrue(model.state.value.pushEnabled)
        presenter.granted = false
        model.refreshPermission()
        assertFalse(model.state.value.permissionGranted)
    }

    @Test
    fun successfulPermissionCallbackUsesGlobalAndChannelStateInsteadOfAssumingGranted() {
        val presenter = FakePresenter(granted = false)
        val api = FakeNotificationApi()
        val model = NotificationViewModel(api, MemoryNotificationStore(), presenter)
        model.bindActor("11111111-1111-4111-8111-111111111111")
        await(model)
        model.permissionResult(true)
        assertFalse(model.state.value.permissionGranted)
        assertEquals(NotificationMessage.PERMISSION_DENIED, model.state.value.message)
        assertTrue(api.updates.isEmpty())
    }

    @Test
    fun returningFromExternalPickerDoesNotCancelAnInFlightQueryOrLeaveBusyStale() {
        val actor = "11111111-1111-4111-8111-111111111111"
        val gate = CompletableDeferred<Unit>()
        val api = FakeNotificationApi().apply { notificationsGate = gate }
        val model = NotificationViewModel(api, MemoryNotificationStore(), FakePresenter())
        model.bindActor(actor)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(model.state.value.busy)
        // MainActivity.onResume synchronizes; NotificationHost.onResume only rechecks permission.
        model.synchronize(actor)
        model.refreshPermission()
        model.show(actor)
        gate.complete(Unit)
        await(model)
        assertTrue(model.state.value.open)
        assertEquals(1, api.notificationQueries)
        assertEquals(notificationId, model.state.value.items.single().notification.id)
    }

    @Test
    fun homeCloseCancelsOldQueryAndOpeningNoticesStartsAFreshQuery() {
        val actor = "11111111-1111-4111-8111-111111111111"
        val oldQuery = CompletableDeferred<Unit>()
        val api = FakeNotificationApi().apply { notificationsGate = oldQuery }
        val model = NotificationViewModel(api, MemoryNotificationStore(), FakePresenter())
        model.bindActor(actor)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(model.state.value.busy)
        model.close()
        assertFalse(model.state.value.busy)
        assertFalse(model.state.value.loadingNotifications)
        api.notificationsGate = null
        model.show(actor)
        await(model)
        oldQuery.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, api.notificationQueries)
        assertTrue(model.state.value.open)
        assertFalse(model.state.value.busy)
        assertEquals(notificationId, model.state.value.items.single().notification.id)
    }

    @Test
    fun discardedNoticesStayHiddenAcrossRefreshAndUndoDoesNotRevertLaterReads() {
        val store = MemoryNotificationStore()
        val api = FakeNotificationApi()
        val model = NotificationViewModel(api, store, FakePresenter())
        val actor = "11111111-1111-4111-8111-111111111111"
        model.bindActor(actor)
        await(model)
        model.markRead(model.state.value.items.single())
        val earlier = model.state.value.undo!!
        model.markAllRead()
        model.undo(earlier)
        assertTrue(model.state.value.items.single().read)
        model.discard(model.state.value.items.single())
        val discard = model.state.value.undo!!
        assertTrue(model.state.value.items.isEmpty())
        model.synchronize(actor)
        await(model)
        assertTrue(model.state.value.items.isEmpty())
        model.undo(discard)
        assertTrue(model.state.value.items.single().read)
        assertTrue(store.discarded.isEmpty())
    }

    @Test
    fun switchingAccountsCancelsQueuedWorkAndInvalidatesUndo() {
        val gate = CompletableDeferred<Unit>()
        val api = FakeNotificationApi()
        val presenter = FakePresenter()
        val model = NotificationViewModel(api, MemoryNotificationStore(), presenter)
        val first = "11111111-1111-4111-8111-111111111111"
        model.bindActor(first)
        await(model)
        model.discard(model.state.value.items.single())
        val undo = model.state.value.undo!!
        api.notificationsGate = gate
        model.synchronize(first)
        model.bindActor(null)
        gate.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()
        model.undo(undo)
        assertNull(model.state.value.actor)
        assertNull(model.state.value.undo)
        assertTrue(model.state.value.items.isEmpty())
        assertEquals(1, presenter.shown.size)
        assertEquals(1, presenter.clears)
    }

    @Test
    fun failedLocalDiscardDoesNotHideTheNoticeOrOfferSuccessfulUndo() {
        val store = MemoryNotificationStore()
        val model = NotificationViewModel(FakeNotificationApi(), store, FakePresenter())
        model.bindActor("11111111-1111-4111-8111-111111111111")
        await(model)
        store.failChanges = true
        model.discard(model.state.value.items.single())
        assertEquals(1, model.state.value.items.size)
        assertNull(model.state.value.undo)
        assertEquals(NotificationError.STORAGE, model.state.value.error)
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
