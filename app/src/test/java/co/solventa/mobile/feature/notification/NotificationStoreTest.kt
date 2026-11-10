package co.solventa.mobile.feature.notification

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class NotificationStoreTest {
    private val actor = "11111111-1111-4111-8111-111111111111"
    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun repeatedIdentifiersAreReportedOnceAndReadStateSurvivesRecreation() {
        val store = AndroidNotificationStore(context)
        assertEquals(setOf(notificationId), store.recordSeen(actor, setOf(notificationId)))
        assertTrue(store.recordSeen(actor, setOf(notificationId)).isEmpty())
        store.markRead(actor, notificationId)

        val restored = AndroidNotificationStore(context)
        assertEquals(setOf(notificationId), restored.seen(actor))
        assertEquals(setOf(notificationId), restored.read(actor))
    }

    @Test
    fun discardAndReadPersistPerActorAndStaleUndoCannotOverwriteANewerAction() {
        val store = AndroidNotificationStore(context)
        val other = "22222222-2222-4222-8222-222222222222"
        val removed = store.change(actor, notificationId, discarded = true)
        val restored = AndroidNotificationStore(context)
        assertEquals(setOf(notificationId), restored.discarded(actor))
        assertTrue(restored.discarded(other).isEmpty())
        restored.change(other, notificationId, discarded = true)
        assertTrue(!restored.undo(other, removed))
        assertTrue(restored.undo(actor, removed))
        assertTrue(restored.discarded(actor).isEmpty())
        val read = restored.change(actor, notificationId, read = true)
        restored.markAllRead(actor, setOf(notificationId))
        assertTrue(!restored.undo(actor, read))
        assertEquals(setOf(notificationId), restored.read(actor))
        assertTrue(restored.read(other).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidActorCannotCreateAnUnscopedPreferenceKey() {
        AndroidNotificationStore(context).recordSeen("../otro", setOf(notificationId))
    }
}
