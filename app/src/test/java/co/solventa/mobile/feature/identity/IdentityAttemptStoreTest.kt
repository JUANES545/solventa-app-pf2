package co.solventa.mobile.feature.identity

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class IdentityAttemptStoreTest {
    @Test fun pendingKeySurvivesStoreRecreationAndRemainsScopedToActor() {
        val app = RuntimeEnvironment.getApplication()
        val store = IdentityAttemptStore(app)
        val pending = IdentityAttempt(pendingIdentity.id, "be794f6e-e01d-4d2f-ad25-c9bf118c117a")
        store.save("actor-a", pending)
        val recreated = IdentityAttemptStore(app)
        assertEquals(pending, recreated.read("actor-a"))
        assertNull(recreated.read("actor-b"))
        recreated.clear("actor-b", pending)
        assertEquals(pending, recreated.read("actor-a"))
        recreated.clear("actor-a", pending)
        assertNull(store.read("actor-a"))
    }

    @Test fun aDifferentPendingKeyCannotBeOverwrittenOrClearedByAnOlderOperation() {
        val store = IdentityAttemptStore(RuntimeEnvironment.getApplication())
        val pending = IdentityAttempt(pendingIdentity.id, "be794f6e-e01d-4d2f-ad25-c9bf118c117a")
        val other = pending.copy(attemptId = "d0608d30-33e1-4aba-8e55-64f685c940af")
        store.save("actor-a", pending)
        try { store.save("actor-a", other); fail() } catch (_: IdentityStorageProblem) {}
        try { store.clear("actor-a", other); fail() } catch (_: IdentityStorageProblem) {}
        assertEquals(pending, store.read("actor-a"))
    }

    @Test fun corruptStoredKeyIsAnExplicitFailureNotPermissionToCreateAnotherAttempt() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("identity-synthetic-attempts", Context.MODE_PRIVATE)
        for (bad in listOf("broken", "../other|broken", "${pendingIdentity.id}|broken")) {
            assertTrue(prefs.edit().putString("actor-a", bad).commit())
            try { IdentityAttemptStore(app).read("actor-a"); fail() } catch (_: IdentityStorageProblem) {}
        }
    }
}
