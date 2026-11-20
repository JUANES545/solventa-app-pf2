package co.solventa.mobile.feature.identity

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

internal data class IdentityAttempt(val verificationId: String, val attemptId: String)
internal class IdentityStorageProblem : Exception()

internal interface IdentityAttempts {
    fun read(actor: String): IdentityAttempt?
    fun save(actor: String, attempt: IdentityAttempt)
    fun clear(actor: String, attempt: IdentityAttempt)
}

@Singleton
internal class IdentityAttemptStore @Inject constructor(@ApplicationContext context: Context) : IdentityAttempts {
    private val preferences = context.getSharedPreferences("identity-synthetic-attempts", Context.MODE_PRIVATE)

    @Synchronized override fun read(actor: String): IdentityAttempt? {
        val value = try {
            preferences.getString(actor, null)
        } catch (_: ClassCastException) {
            throw IdentityStorageProblem()
        } ?: return null
        val parts = value.split('|')
        try {
            require(parts.size == 2)
            return IdentityAttempt(identityUuid(parts[0]), identityUuid(parts[1]))
        } catch (_: IllegalArgumentException) {
            throw IdentityStorageProblem()
        }
    }

    @Synchronized override fun save(actor: String, attempt: IdentityAttempt) {
        val saved = read(actor)
        if (saved != null && saved != attempt) throw IdentityStorageProblem()
        if (!preferences.edit().putString(actor,
                "${identityUuid(attempt.verificationId)}|${identityUuid(attempt.attemptId)}").commit()) {
            throw IdentityStorageProblem()
        }
    }

    @Synchronized override fun clear(actor: String, attempt: IdentityAttempt) {
        val saved = read(actor) ?: return
        if (saved != attempt) throw IdentityStorageProblem()
        if (!preferences.edit().remove(actor).commit()) throw IdentityStorageProblem()
    }
}
