package co.solventa.mobile.feature.claim

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.time.Instant
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ClaimQueueHistoryTest {
    @get:Rule val folder = TemporaryFolder()
    private val created = Instant.parse("2026-11-05T12:00:00Z")

    @Test fun fifoAndLastResultSurviveReopenWithoutChangingConfirmedDatesOrPayloads() {
        val directory = folder.newFolder()
        var clock = created
        val receipt = ClaimReceipt(UUID.randomUUID().toString(), "registrado", created.minusSeconds(1))
        val saved = ClaimStore(directory) { clock }.use { store ->
            val first = store.enqueue("actor-a", payload())
            clock = created.minusSeconds(5)
            val second = store.enqueue("actor-a", payload().copy(type = "Second"))
            assertEquals(listOf(first.operation, second.operation), store.list("actor-a").map { it.operation })
            clock = created.plusSeconds(10)
            store.fail(first, ClaimError.CONFLICT, "idempotencia_conflictiva")
            store.confirm(second, receipt)
            clock = created.plusSeconds(20)
            store.confirm(second, receipt)
            assertTrue(store.list("actor-b").isEmpty())
            store.list("actor-a")
        }
        ClaimStore(directory).use { store ->
            assertEquals(saved, store.list("actor-a"))
            assertEquals(created.plusSeconds(10), saved[0].lastResultAt)
            assertEquals(created.plusSeconds(10), saved[1].lastResultAt)
            assertEquals("idempotencia_conflictiva", saved[0].errorCode)
            assertFalse(saved[0].retryable)
            assertFalse(saved[0].correctable)
            assertEquals(receipt, saved[1].receipt)
            assertThrows(IllegalStateException::class.java) { store.fail(saved[1], ClaimError.NETWORK) }
        }
    }

    @Test fun legacyMigrationKeepsOperationsPoliciesAndReceiptsWithoutInventingDates() {
        val directory = folder.newFolder()
        val original = ClaimStore(directory).use { store ->
            store.savePolicies("actor-a", listOf(policy))
            val first = store.enqueue("actor-a", payload())
            val second = store.enqueue("actor-a", payload().copy(type = "Second"))
            store.fail(first, ClaimError.NETWORK)
            store.confirm(second, ClaimReceipt(UUID.randomUUID().toString(), "registrado"))
            store.list("actor-a")
        }
        SQLiteDatabase.openDatabase(File(directory, "claim-reports.db").path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("ALTER TABLE reports RENAME TO current_reports")
            it.execSQL("""CREATE TABLE reports (
                operation TEXT PRIMARY KEY, actor TEXT NOT NULL, policy TEXT NOT NULL, event_date TEXT NOT NULL,
                type TEXT NOT NULL, place TEXT NOT NULL, description TEXT NOT NULL, remote_id TEXT, status TEXT, error TEXT
            )""")
            it.execSQL("""INSERT INTO reports SELECT operation,actor,policy,event_date,type,place,description,
                remote_id,status,error FROM current_reports ORDER BY rowid""")
            it.execSQL("DROP TABLE current_reports")
        }
        repeat(2) {
            ClaimStore(directory).use { store ->
                assertEquals(original.map { it.copy(queuedAt = null, lastResultAt = null) }, store.list("actor-a"))
                assertEquals(listOf(policy), store.policies("actor-a"))
            }
        }
    }
}
