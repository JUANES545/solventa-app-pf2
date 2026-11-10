package co.solventa.mobile.mock

import android.app.Application
import android.content.ContentUris
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import co.solventa.mobile.BuildConfig
import co.solventa.mobile.feature.auth.AuthRepository
import co.solventa.mobile.feature.claim.ClaimPayload
import co.solventa.mobile.feature.claim.ClaimReport
import co.solventa.mobile.feature.claim.MobileClaimApi
import co.solventa.mobile.feature.evidence.*
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class DemoVarietyDeviceTest {
    @get:Rule val hilt = HiltAndroidRule(this)
    @Inject lateinit var auth: AuthRepository
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun prepare() {
        hilt.inject()
        check(BuildConfig.APPLICATION_ID == "co.solventa.mobile.pf2.mock")
        MockInitializer.backend.choose(MockScenario.DATA)
    }

    @Test fun publicPhotoAndSampleDocumentAreReviewedUploadedAndRestored() = runBlocking {
        val photo = image("puerta-lateral.jpg")
        val document = image("documento-prueba.jpg")
        auth.login(MockBackend.EMAIL, MockBackend.PASSWORD)
        val api = MobileEvidenceApi(auth, BuildConfig.AUTH_BASE_URL)
        val report = ClaimReport(UUID.randomUUID().toString(), MockBackend.ACTOR,
            ClaimPayload(MockBackend.POLICY, Instant.now().minusSeconds(60), "Daño en puerta lateral",
                "Zona de estacionamiento", "La puerta delantera presenta un golpe y el vidrio requiere revisión."))
        val receipt = MobileClaimApi(api).send(report)
        val claim = api.claims(MockBackend.ACTOR).first { it.id == receipt.id }
        val model = EvidenceViewModel(context.applicationContext as Application, api)
        fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
        fun await() {
            val deadline = SystemClock.elapsedRealtime() + 60_000
            while (model.state.value.busy && SystemClock.elapsedRealtime() < deadline) Thread.sleep(25)
            assertFalse("Evidence operation did not finish", model.state.value.busy)
            assertNull("Evidence operation failed", model.state.value.error)
        }
        try {
            onMain { model.showClaim(MockBackend.ACTOR, claim) }; await()
            val scope = checkNotNull(model.state.value.scope)
            for ((uri, kind) in listOf(photo to EvidenceKind.PHOTO, document to EvidenceKind.DOCUMENT)) {
                assertEquals("image/jpeg", context.contentResolver.getType(uri))
                onMain { model.importFile(uri, kind, scope, model.state.value.generation) }; await()
                assertEquals(EvidenceStep.REVIEW, model.state.value.step)
                assertEquals(kind, model.state.value.draft?.kind)
                assertNotNull(model.state.value.preview?.bitmap)
                onMain { model.accept(false) }; await()
                onMain { model.retry() }; await()
                assertEquals(EvidenceStatus.CONFIRMED, model.state.value.items.last().status)
            }
            onMain { model.showClaim(MockBackend.ACTOR, claim) }; await()
            EvidenceStore(context.noBackupFilesDir).use { store ->
                val items = store.list(scope)
                assertEquals(setOf(EvidenceKind.PHOTO, EvidenceKind.DOCUMENT), items.map { it.kind }.toSet())
                assertEquals(2, items.size)
                items.forEach { item ->
                    assertEquals(EvidenceStatus.CONFIRMED, item.status)
                    assertTrue(api.confirmedIds(scope).contains(item.remoteId))
                    val bitmap = android.graphics.BitmapFactory.decodeFile(store.file(item).path)
                    assertNotNull(bitmap)
                    assertTrue(bitmap.width >= 900 && bitmap.height >= 700)
                    bitmap.recycle()
                }
            }
        } finally { onMain { model.close() } }
    }

    private fun image(name: String): Uri = checkNotNull(context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
        "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND ${MediaStore.Images.Media.RELATIVE_PATH} = ?",
        arrayOf(name, "Pictures/Solventa/"), null,
    )).use { cursor ->
        check(cursor.moveToFirst()) { "Prepara los adjuntos de demostración antes de ejecutar esta clase." }
        ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0))
    }
}
