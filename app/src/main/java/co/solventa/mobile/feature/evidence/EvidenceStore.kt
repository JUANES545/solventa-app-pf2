package co.solventa.mobile.feature.evidence

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import co.solventa.mobile.core.SyncResult
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import org.json.JSONObject

internal class EvidenceStore(noBackupDirectory: File, private val now: () -> Instant = Instant::now) : Closeable {
    private val directory = File(noBackupDirectory, "claim-evidence")
    private val database: SQLiteDatabase

    init {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Evidence storage unavailable")
        database = SQLiteDatabase.openOrCreateDatabase(File(directory, "queue.db"), null)
        database.execSQL("PRAGMA synchronous=FULL")
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS evidence (
                id TEXT PRIMARY KEY, actor TEXT NOT NULL, claim_id TEXT NOT NULL,
                kind TEXT NOT NULL, captured_at TEXT NOT NULL, latitude REAL, longitude REAL,
                measured_at TEXT, bytes INTEGER NOT NULL, sha256 TEXT NOT NULL, duration INTEGER NOT NULL,
                remote_id TEXT, upload_id TEXT, rejection TEXT, last_error TEXT, UNIQUE(actor, claim_id, id)
            )
        """.trimIndent())
        val columns = database.rawQuery("PRAGMA table_info(evidence)", null).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(1)) }
        }
        if ("rejection" !in columns) database.execSQL("ALTER TABLE evidence ADD COLUMN rejection TEXT")
        if ("last_error" !in columns) database.execSQL("ALTER TABLE evidence ADD COLUMN last_error TEXT")
        for (column in listOf("queued_at", "last_result_at", "error_code", "server_result")) {
            if (column !in columns) database.execSQL("ALTER TABLE evidence ADD COLUMN $column TEXT")
        }
    }

    @Synchronized
    fun list(scope: EvidenceScope): List<EvidenceItem> = database.query(
        "evidence", null, "actor=? AND claim_id=?", arrayOf(scope.actorId, scope.claimId),
        null, null, "rowid",
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.item()) } }

    @Synchronized
    fun enqueue(
        scope: EvidenceScope,
        kind: EvidenceKind,
        source: File,
        capturedAt: Instant,
        location: EvidenceLocation?,
        durationMillis: Long,
        policy: EvidencePolicy,
        confirmedRemoteIds: Set<String>,
    ): EvidenceItem {
        policy.validate(kind, source.length(), durationMillis)
        database.beginTransaction()
        var target: File? = null
        try {
            val existing = list(scope)
            val count = existing.count { it.remoteId == null } +
                (confirmedRemoteIds + existing.mapNotNull { it.remoteId }).size
            if (count >= policy.maxCount) throw EvidenceProblem(EvidenceError.COUNT)
            val id = UUID.randomUUID().toString()
            val file = File(directory, "$id.${kind.extension}")
            target = file
            FileOutputStream(file).use { output ->
                source.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var size = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        size += read
                        if (size > checkNotNull(policy.maxBytes[kind])) throw EvidenceProblem(EvidenceError.SIZE)
                        output.write(buffer, 0, read)
                    }
                }
                output.fd.sync()
            }
            policy.validate(kind, file.length(), durationMillis)
            val item = EvidenceItem(id, scope, kind, capturedAt, location, file.length(), digest(file), durationMillis, queuedAt = now())
            database.insertOrThrow("evidence", null, ContentValues().apply {
                put("id", id); put("actor", scope.actorId); put("claim_id", scope.claimId)
                put("kind", kind.name); put("captured_at", capturedAt.toString())
                location?.let {
                    put("latitude", it.latitude); put("longitude", it.longitude); put("measured_at", it.measuredAt.toString())
                }
                put("bytes", item.bytes); put("sha256", item.sha256); put("duration", durationMillis)
                put("queued_at", item.queuedAt.toString())
            })
            database.setTransactionSuccessful()
            return item
        } finally {
            database.endTransaction()
            // A crash before the row commits can leave an orphan, but can never publish a partial item.
            if (target != null && !containsFile(target)) {
                if (target.exists() && !target.delete()) throw IOException("Unqueued evidence cleanup failed")
            }
        }
    }

    @Synchronized
    fun file(item: EvidenceItem): File {
        val stored = list(item.scope).singleOrNull { it.id == item.id }
            ?: throw EvidenceProblem(EvidenceError.SESSION)
        require(stored.sha256 == item.sha256)
        val file = File(directory, "${stored.id}.${stored.kind.extension}")
        if (!file.isFile || file.length() != stored.bytes || digest(file) != stored.sha256) {
            throw EvidenceProblem(EvidenceError.STORAGE)
        }
        return file
    }

    @Synchronized
    fun confirm(item: EvidenceItem, receipt: EvidenceReceipt) {
        if (receipt.localId != item.id || receipt.sha256 != item.sha256 || receipt.remoteId.isBlank()) {
            throw EvidenceProblem(EvidenceError.PROTOCOL)
        }
        require(receipt.serverResult == null || receipt.serverResult.operationId == item.id && receipt.serverResult.httpStatus < 300)

        val stored = list(item.scope).singleOrNull { it.id == item.id }
            ?: throw EvidenceProblem(EvidenceError.SESSION)
        if (stored.sha256 != receipt.sha256) throw EvidenceProblem(EvidenceError.PROTOCOL)
        if (stored.remoteId != null && stored.remoteId != receipt.remoteId) throw EvidenceProblem(EvidenceError.PROTOCOL)
        if (stored.remoteId != null) return
        check(database.update("evidence", ContentValues().apply {
            put("remote_id", receipt.remoteId); putNull("rejection"); putNull("last_error")
            put("last_result_at", now().toString()); putNull("error_code")
            put("server_result", receipt.serverResult?.json()?.toString())
        },
            "id=? AND actor=? AND claim_id=?", arrayOf(item.id, item.scope.actorId, item.scope.claimId)) == 1)
    }

    @Synchronized
    fun reject(item: EvidenceItem, reason: EvidenceError, code: String? = null, serverResult: SyncResult? = null) {
        require(serverResult == null || serverResult.operationId == item.id)
        check(database.update("evidence", ContentValues().apply {
            put("rejection", reason.name); putNull("last_error")
            put("last_result_at", now().toString()); put("error_code", code)
            put("server_result", serverResult?.json()?.toString())
        },
            "id=? AND actor=? AND claim_id=? AND remote_id IS NULL",
            arrayOf(item.id, item.scope.actorId, item.scope.claimId)) == 1)
    }

    @Synchronized
    fun fail(item: EvidenceItem, reason: EvidenceError, code: String? = null, serverResult: SyncResult? = null) {
        require(serverResult == null || serverResult.operationId == item.id)
        check(database.update("evidence", ContentValues().apply {
            put("last_error", reason.name); put("last_result_at", now().toString()); put("error_code", code)
            put("server_result", serverResult?.json()?.toString())
        },
            "id=? AND actor=? AND claim_id=? AND remote_id IS NULL AND rejection IS NULL",
            arrayOf(item.id, item.scope.actorId, item.scope.claimId)) == 1)
    }

    @Synchronized
    fun removeRejected(item: EvidenceItem) {
        val stored = list(item.scope).single { it.id == item.id }
        require(stored.status == EvidenceStatus.REJECTED && stored.remoteId == null)
        val file = File(directory, "${stored.id}.${stored.kind.extension}")
        if (file.exists() && !file.delete()) throw IOException("Rejected evidence cleanup failed")
        check(database.delete("evidence", "id=? AND actor=? AND claim_id=? AND remote_id IS NULL",
            arrayOf(item.id, item.scope.actorId, item.scope.claimId)) == 1)
    }

    @Synchronized
    fun uploadId(item: EvidenceItem): String? = database.rawQuery(
        "SELECT upload_id FROM evidence WHERE id=? AND actor=? AND claim_id=?",
        arrayOf(item.id, item.scope.actorId, item.scope.claimId),
    ).use { cursor ->
        if (!cursor.moveToFirst()) throw EvidenceProblem(EvidenceError.SESSION)
        cursor.getString(0)
    }

    @Synchronized
    fun saveUploadId(item: EvidenceItem, id: String) {
        require(UUID.fromString(id).toString() == id)
        val previous = uploadId(item)
        if (previous != null && previous != id) throw EvidenceProblem(EvidenceError.PROTOCOL)
        check(database.update("evidence", ContentValues().apply { put("upload_id", id) },
            "id=? AND actor=? AND claim_id=?", arrayOf(item.id, item.scope.actorId, item.scope.claimId)) == 1)
    }

    private fun containsFile(file: File): Boolean = database.rawQuery(
        "SELECT id FROM evidence WHERE id=?", arrayOf(file.nameWithoutExtension),
    ).use { it.moveToFirst() }

    private fun Cursor.item(): EvidenceItem {
        fun text(name: String) = getString(getColumnIndexOrThrow(name))
        val remote = text("remote_id")
        val rejected = text("rejection")?.let(EvidenceError::valueOf)
        val measured = text("measured_at")
        return EvidenceItem(text("id"), EvidenceScope(text("actor"), text("claim_id")),
            EvidenceKind.valueOf(text("kind")), Instant.parse(text("captured_at")),
            measured?.let { EvidenceLocation(getDouble(getColumnIndexOrThrow("latitude")),
                getDouble(getColumnIndexOrThrow("longitude")), Instant.parse(it)) },
            getLong(getColumnIndexOrThrow("bytes")), text("sha256"), getLong(getColumnIndexOrThrow("duration")),
            when {
                remote != null -> EvidenceStatus.CONFIRMED
                rejected == EvidenceError.CONFLICT -> EvidenceStatus.CONFLICT
                rejected != null -> EvidenceStatus.REJECTED
                else -> EvidenceStatus.PENDING
            }, remote, rejected, text("last_error")?.let(EvidenceError::valueOf),
            text("queued_at")?.let(Instant::parse), text("last_result_at")?.let(Instant::parse), text("error_code"),
            text("server_result")?.let { SyncResult.parse(JSONObject(it), text("id")) })
    }

    @Synchronized override fun close() = database.close()
}

internal fun digest(file: File): String {
    val hash = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val size = input.read(buffer)
            if (size == -1) break
            hash.update(buffer, 0, size)
        }
    }
    return hash.digest().joinToString("") { "%02x".format(it) }
}
