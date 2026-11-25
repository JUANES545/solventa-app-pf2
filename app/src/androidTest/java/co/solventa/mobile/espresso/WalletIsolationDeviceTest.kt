package co.solventa.mobile.espresso

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import co.solventa.mobile.feature.policy.*
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WalletIsolationDeviceTest {
    @Test fun ciphertextCannotBeReadByAnotherActorAndCanBePurged() {
        // Use the instrumentation package's sandbox, not the installed client's wallet.
        val context = InstrumentationRegistry.getInstrumentation().context
        val wallet = PolicyWallet(context)
        val policy = Policy("1", PolicyProduct.TRAVEL, LocalDate.now(), LocalDate.now().plusDays(3),
            PolicyStatus.SCHEDULED, true, "SEG-PRUEBA-123", "Asistencia en viaje")
        try {
            wallet.write("actor-a", listOf(policy))
            assertEquals(listOf(policy), PolicyWallet(context).read("actor-a")?.policies)
            val stored = context.getSharedPreferences("policy-wallet", 0).getString("snapshot", null)!!
            assertFalse(stored.contains(policy.number))
            assertNull(wallet.read("actor-b"))
            assertNull(wallet.read("actor-a"))
            wallet.write("actor-b", emptyList())
            wallet.clear()
            assertNull(wallet.read("actor-b"))
        } finally { wallet.clear() }
    }
}
