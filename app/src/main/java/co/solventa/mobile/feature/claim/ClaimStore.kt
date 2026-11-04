package co.solventa.mobile.feature.claim

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

internal class ClaimStore(noBackupDirectory: File) : Closeable {
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
        "SELECT * FROM reports WHERE actor=? ORDER BY rowid DESC", arrayOf(actor),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                fun text(name: String): String = cursor.getString(cursor.getColumnIndexOrThrow(name))
                val remote = cursor.getString(cursor.getColumnIndexOrThrow("remote_id"))
                val error = cursor.getString(cursor.getColumnIndexOrThrow("error"))
                add(ClaimReport(text("operation"), actor,
                    ClaimPayload(text("policy"), Instant.parse(text("event_date")), text("type"), text("place"), text("description")),
                    remote?.let { ClaimReceipt(it, text("status")) }, error?.let(ClaimError::valueOf)))
            }
        }
    }

    @Synchronized fun enqueue(actor: String, payload: ClaimPayload, correction: String? = null): ClaimReport {
        require(actor.isNotBlank())
        val report = ClaimReport(UUID.randomUUID().toString(), actor, payload)
        val values = ContentValues().apply {
            put("operation", report.operation); put("actor", actor); put("policy", payload.policyId)
            put("event_date", payload.date.toString()); put("type", payload.type)
            put("place", payload.place); put("description", payload.description)
            putNull("error")
        }
        if (correction == null) database.insertOrThrow("reports", null, values)
        else {
            // Only an explicit rejection permits a new key; an uncertain ACK must replay the original payload.
            check(list(actor).single { it.operation == correction }.correctable)
            check(database.update("reports", values, "operation=? AND actor=? AND remote_id IS NULL AND error IN (?,?)",
                arrayOf(correction, actor, ClaimError.POLICY.name, ClaimError.DATE.name)) == 1)
        }
        return report
    }

    @Synchronized fun confirm(report: ClaimReport, receipt: ClaimReceipt) {
        claimUuid(receipt.id)
        require(receipt.status == "registrado")
        val stored = list(report.actor).single { it.operation == report.operation }
        require(stored.payload == report.payload && (stored.receipt == null || stored.receipt == receipt))
        check(database.update("reports", ContentValues().apply {
            put("remote_id", receipt.id); put("status", receipt.status); putNull("error")
        }, "operation=? AND actor=?", arrayOf(report.operation, report.actor)) == 1)
    }

    @Synchronized fun fail(report: ClaimReport, error: ClaimError) {
        check(database.update("reports", ContentValues().apply { put("error", error.name) },
            "operation=? AND actor=? AND remote_id IS NULL", arrayOf(report.operation, report.actor)) == 1)
    }

    @Synchronized override fun close() = database.close()
}
