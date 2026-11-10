package co.solventa.mobile.mock

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import co.solventa.mobile.BuildConfig
import co.solventa.mobile.feature.evidence.*
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

internal object LegacyBlueEvidenceCleanup {
    private val scope = EvidenceScope(MockBackend.ACTOR, MockBackend.CLAIM)

    // Match the old test's JPEG bytes, including accept()'s privacy re-encoding, not blue-looking photos.
    internal fun fingerprints(): Set<String> {
        fun jpeg(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it))
            it.toByteArray()
        }
        fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val source = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888)
        val original = try {
            source.eraseColor(Color.BLUE)
            jpeg(source)
        } finally { source.recycle() }
        val decoded = checkNotNull(BitmapFactory.decodeByteArray(original, 0, original.size))
        val accepted = try { jpeg(decoded) } finally { decoded.recycle() }
        return setOf(hash(original), hash(accepted))
    }

    suspend fun remove(
        store: EvidenceStore,
        claims: List<EvidenceClaim> = emptyList(),
        deleteConfirmed: suspend (EvidenceScope, String) -> Unit,
    ): List<String> {
        check(BuildConfig.BUILD_TYPE == "mock" && BuildConfig.APPLICATION_ID == "co.solventa.mobile.pf2.mock")
        val fingerprints = fingerprints()
        val removed = mutableListOf<String>()
        val scopes = setOf(scope) + claims.filter {
            it.type == "Prueba de fotografía" &&
                it.description == "Fotografía sintética para comprobar carga y restauración."
        }.map { EvidenceScope(MockBackend.ACTOR, it.id) }
        for (target in scopes) for (item in store.list(target)) {
            if (item.kind != EvidenceKind.PHOTO || item.status != EvidenceStatus.CONFIRMED ||
                item.sha256 !in fingerprints) continue
            store.file(item)
            deleteConfirmed(target, checkNotNull(item.remoteId))
            store.removeDemoConfirmed(item)
            removed.add(item.id)
        }
        return removed
    }
}
