package co.solventa.mobile.feature.auth

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
class EncryptedAuthStoreTest {
    @Test fun isolatedStoreDoesNotReadOrClearProductionPreferences() {
        val context = RuntimeEnvironment.getApplication()
        val production = context.getSharedPreferences("mobile-auth", Context.MODE_PRIVATE)
        val isolated = context.getSharedPreferences("auth-storage-test", Context.MODE_PRIVATE)
        production.edit().putString("grant", "existing-session").commit()
        val store = EncryptedAuthStore(context, "auth-storage-test", "auth-storage-test")

        assertNull(store.read())
        isolated.edit().putString("grant", "test-session").commit()
        store.clear()

        assertFalse(isolated.contains("grant"))
        assertEquals("existing-session", production.getString("grant", null))
    }

    @Test fun injectableConstructorKeepsProductionPreferences() {
        val context = RuntimeEnvironment.getApplication()
        val production = context.getSharedPreferences("mobile-auth", Context.MODE_PRIVATE)
        production.edit().putString("grant", "existing-session").commit()

        EncryptedAuthStore(context).clear()

        assertFalse(production.contains("grant"))
    }
}
