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
    override fun recordSeen(actor: String, ids: Set<String>): Set<String> {
        val name = key(actor, "seen")
        val previous = values(name)
        val fresh = ids - previous
        persist(name, previous + ids)
        return fresh
    }

    @Synchronized
    override fun markRead(actor: String, id: String) {
        requireUuid(id)
        val name = key(actor, "read")
        persist(name, values(name) + id)
    }

    @Synchronized
    override fun markAllRead(actor: String, ids: Set<String>) {
        ids.forEach(::requireUuid)
        val name = key(actor, "read")
        persist(name, values(name) + ids)
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
