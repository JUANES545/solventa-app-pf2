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

    @Test(expected = IllegalArgumentException::class)
    fun invalidActorCannotCreateAnUnscopedPreferenceKey() {
        AndroidNotificationStore(context).recordSeen("../otro", setOf(notificationId))
    }
}
