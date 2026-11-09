package co.solventa.mobile.feature.claim

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.core.database.sqlite.transaction
import co.solventa.mobile.core.SyncResult
import java.io.Closeable
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.json.JSONObject

internal class ClaimStore(noBackupDirectory: File, private val now: () -> Instant = Instant::now) : Closeable {
    private val database = SQLiteDatabase.openOrCreateDatabase(File(noBackupDirectory, "claim-reports.db"), null)

    init {
        database.execSQL("PRAGMA synchronous=FULL")
        database.execSQL("""CREATE TABLE IF NOT EXISTS reports (
            operation TEXT PRIMARY KEY, actor TEXT NOT NULL, policy TEXT NOT NULL, event_date TEXT NOT NULL,
            type TEXT NOT NULL, place TEXT NOT NULL, description TEXT NOT NULL, remote_id TEXT, status TEXT, error TEXT
        )""")
        database.execSQL("""CREATE TABLE IF NOT EXISTS policies (
            actor TEXT NOT NULL, id TEXT NOT NULL, type TEXT NOT NULL, start TEXT NOT NULL, end TEXT NOT NULL,
            PRIMARY KEY(actor, id)
        )""")
        val columns = database.rawQuery("PRAGMA table_info(reports)", null).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(1)) }
        }
        for (column in listOf("queued_at", "last_result_at", "error_code", "registered_at", "superseded_by", "server_result")) {
            if (column !in columns) database.execSQL("ALTER TABLE reports ADD COLUMN $column TEXT")
        }
    }

    @Synchronized fun policies(actor: String): List<ClaimPolicy> = database.rawQuery(
        "SELECT id,type,start,end FROM policies WHERE actor=? ORDER BY id", arrayOf(actor),
    ).use { cursor ->
        buildList { while (cursor.moveToNext()) add(ClaimPolicy(cursor.getString(0), cursor.getString(1),
            LocalDate.parse(cursor.getString(2)), LocalDate.parse(cursor.getString(3)))) }
    }

    @Synchronized fun savePolicies(actor: String, policies: List<ClaimPolicy>) {
        database.beginTransaction()
        try {
            database.delete("policies", "actor=?", arrayOf(actor))
            policies.forEach { policy ->
                database.insertOrThrow("policies", null, ContentValues().apply {
                    put("actor", actor); put("id", policy.id); put("type", policy.type)
                    put("start", policy.start.toString()); put("end", policy.end.toString())
                })
            }
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
    }

    @Synchronized fun list(actor: String): List<ClaimReport> = database.rawQuery(
        "SELECT * FROM reports WHERE actor=? ORDER BY rowid", arrayOf(actor),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                fun text(name: String): String = cursor.getString(cursor.getColumnIndexOrThrow(name))
                val remote = cursor.getString(cursor.getColumnIndexOrThrow("remote_id"))
                val error = cursor.getString(cursor.getColumnIndexOrThrow("error"))
                fun optional(name: String): String? = cursor.getString(cursor.getColumnIndexOrThrow(name))
                val serverResult = optional("server_result")?.let { SyncResult.parse(JSONObject(it), text("operation")) }
                add(ClaimReport(text("operation"), actor,
                    ClaimPayload(text("policy"), Instant.parse(text("event_date")), text("type"), text("place"), text("description")),
                    remote?.let { ClaimReceipt(it, text("status"), optional("registered_at")?.let(Instant::parse), serverResult) },
                    error?.let(ClaimError::valueOf), optional("queued_at")?.let(Instant::parse),
                    optional("last_result_at")?.let(Instant::parse), optional("error_code"), optional("superseded_by"), serverResult))
            }
        }
    }

    @Synchronized fun enqueue(actor: String, payload: ClaimPayload, correction: String? = null): ClaimReport {
        require(actor.isNotBlank())
        val report = ClaimReport(UUID.randomUUID().toString(), actor, payload, queuedAt = now())
        val values = ContentValues().apply {
            put("operation", report.operation); put("actor", actor); put("policy", payload.policyId)
            put("event_date", payload.date.toString()); put("type", payload.type)
            put("place", payload.place); put("description", payload.description)
            putNull("error")
            put("queued_at", report.queuedAt.toString()); putNull("last_result_at"); putNull("error_code")
        }
        database.transaction {
            if (correction != null) {
                // Preserve the rejected operation; an uncertain ACK must replay its original key and payload.
                check(list(actor).single { it.operation == correction }.correctable)
                check(database.update("reports", ContentValues().apply { put("superseded_by", report.operation) },
                    "operation=? AND actor=? AND remote_id IS NULL AND superseded_by IS NULL AND error IN (?,?)",
                    arrayOf(correction, actor, ClaimError.POLICY.name, ClaimError.DATE.name)) == 1)
            }
            database.insertOrThrow("reports", null, values)
        }
        return report
    }

    @Synchronized fun confirm(report: ClaimReport, receipt: ClaimReceipt) {
        claimUuid(receipt.id)
        require(receipt.status == "registrado")
        require(receipt.serverResult == null || receipt.serverResult.operationId == report.operation && receipt.serverResult.httpStatus < 300)
        val stored = list(report.actor).single { it.operation == report.operation }
        require(stored.payload == report.payload && (stored.receipt == null || stored.receipt == receipt))
        if (stored.receipt != null) return
        check(database.update("reports", ContentValues().apply {
            put("remote_id", receipt.id); put("status", receipt.status); putNull("error")
            put("last_result_at", now().toString()); putNull("error_code")
            put("registered_at", receipt.registeredAt?.toString())
            put("server_result", receipt.serverResult?.json()?.toString())
        }, "operation=? AND actor=?", arrayOf(report.operation, report.actor)) == 1)
    }

    @Synchronized fun fail(report: ClaimReport, error: ClaimError, code: String? = null, serverResult: SyncResult? = null) {
        require(serverResult == null || serverResult.operationId == report.operation)
        check(database.update("reports", ContentValues().apply {
            put("error", error.name); put("error_code", code); put("last_result_at", now().toString())
            put("server_result", serverResult?.json()?.toString())
        },
            "operation=? AND actor=? AND remote_id IS NULL", arrayOf(report.operation, report.actor)) == 1)
    }

    @Synchronized override fun close() = database.close()
}
