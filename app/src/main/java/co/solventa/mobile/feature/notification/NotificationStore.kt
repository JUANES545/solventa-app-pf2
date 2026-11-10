package co.solventa.mobile.feature.notification

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject

internal class AndroidNotificationStore @Inject constructor(
    @ApplicationContext context: Context,
) : NotificationLocalStore {
    private val preferences =
        context.getSharedPreferences("mobile-notifications", Context.MODE_PRIVATE)

    @Synchronized
    override fun seen(actor: String): Set<String> = values(key(actor, "seen"))

    @Synchronized
    override fun read(actor: String): Set<String> = values(key(actor, "read"))

    @Synchronized
    override fun discarded(actor: String): Set<String> = values(key(actor, "discarded"))

    @Synchronized
    override fun recordSeen(actor: String, ids: Set<String>): Set<String> {
        val name = key(actor, "seen")
        val previous = values(name)
        val fresh = ids - previous
        persist(name, previous + ids)
        return fresh
    }

    @Synchronized
    override fun markRead(actor: String, id: String) {
        change(actor, id, read = true)
    }

    @Synchronized
    override fun markAllRead(actor: String, ids: Set<String>) {
        ids.forEach(::requireUuid)
        val name = key(actor, "read")
        val editor = preferences.edit().putStringSet(name, values(name) + ids)
        ids.forEach { id ->
            val revisionKey = key(actor, "revision.$id")
            editor.putLong(revisionKey, preferences.getLong(revisionKey, 0) + 1)
        }
        if (!editor.commit()) throw NotificationProblem(NotificationError.STORAGE)
    }

    @Synchronized
    override fun change(actor: String, id: String, read: Boolean?, discarded: Boolean?): NotificationUndo {
        requireUuid(id)
        val readKey = key(actor, "read")
        val discardedKey = key(actor, "discarded")
        val reads = values(readKey)
        val discards = values(discardedKey)
        val revisionKey = key(actor, "revision.$id")
        val revision = preferences.getLong(revisionKey, 0) + 1
        val previous = NotificationUndo(actor, id, revision, id in reads, id in discards)
        val editor = preferences.edit().putLong(revisionKey, revision)
        if (read != null) editor.putStringSet(readKey, if (read) reads + id else reads - id)
        if (discarded != null) editor.putStringSet(discardedKey, if (discarded) discards + id else discards - id)
        if (!editor.commit()) throw NotificationProblem(NotificationError.STORAGE)
        return previous
    }

    @Synchronized
    override fun undo(actor: String, change: NotificationUndo): Boolean {
        requireUuid(change.id)
        if (actor != change.actor) return false
        if (preferences.getLong(key(actor, "revision.${change.id}"), 0) != change.revision) return false
        this.change(actor, change.id, change.read, change.discarded)
        return true
    }

    private fun values(name: String): Set<String> =
        try {
            preferences.getStringSet(name, emptySet()).orEmpty().mapTo(mutableSetOf()) {
                requireUuid(it)
            }
        } catch (error: IllegalArgumentException) {
            throw NotificationProblem(NotificationError.STORAGE, error)
        }

    private fun persist(name: String, values: Set<String>) {
        if (!preferences.edit().putStringSet(name, values).commit()) {
            throw NotificationProblem(NotificationError.STORAGE)
        }
    }

    private fun key(actor: String, suffix: String): String = "${requireUuid(actor)}.$suffix"

    private fun requireUuid(value: String): String =
        UUID.fromString(value).toString().also { require(it == value) }
}
