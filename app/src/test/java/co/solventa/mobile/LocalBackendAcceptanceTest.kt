package co.solventa.mobile

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import co.solventa.mobile.feature.auth.*
import co.solventa.mobile.feature.assistance.*
import co.solventa.mobile.feature.claim.*
import co.solventa.mobile.feature.evidence.*
import co.solventa.mobile.feature.notification.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocalBackendAcceptanceTest {
    @Test fun realServicesAcceptDurableReportEvidenceAndAssistanceWithoutDemo() = runBlocking {
        val path = System.getenv("SOLVENTA_LOCAL_ACCEPTANCE_FIXTURE")
        assumeTrue(path != null)
        val fixture = JSONObject(File(checkNotNull(path)).readText())
        val base = fixture.getString("base")
        check(base == "http://127.0.0.1:18723/movil/v1")
        fun control(mode: String) {
            val connection = URL("http://127.0.0.1:18723/__control/$mode").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                assertEquals(200, connection.responseCode)
            } finally { connection.disconnect() }
        }
        var grant: AuthGrant? = null
        val authStore = object : AuthStore {
            override fun read() = grant
            override fun write(value: AuthGrant) { grant = value }
            override fun clear() { grant = null }
        }
        val authApi = MobileAuthApi(base)
        val auth = AuthRepository(authApi, authStore)
        val user = auth.login(fixture.getString("email"), fixture.getString("password"))
        val app = RuntimeEnvironment.getApplication()
        val http = MobileEvidenceApi(auth, base)
        val claims = MobileClaimApi(http)
        val models = ViewModelStore()
        var model = ClaimViewModel(app, claims).also { models.put("claim", it) }
        fun settle() {
            val deadline = System.currentTimeMillis() + 45_000
            while (model.state.value.busy && System.currentTimeMillis() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            assertFalse(model.state.value.busy)
        }
        try {
            assertEquals(user.id, auth.restore()?.id)
            val initial = checkNotNull(grant)
            grant = initial.copy(expiresAt = 0)
            auth.restore()
            assertTrue(initial.refreshToken != checkNotNull(grant).refreshToken)
            model.show(user.id)
            settle()
            val policy = model.state.value.policies.first()
            val date = ZonedDateTime.now().minusMinutes(1).truncatedTo(ChronoUnit.MINUTES)
            val draft = ClaimDraft(policy.id, date.toLocalDate().toString(), date.toLocalTime().toString(),
                "Prueba local de integracion", "Ubicacion manual sintetica", "Reporte sintetico sin efectos externos.")
            model.edit(draft)
            control("offline")
            model.submit()
            settle()
            assertEquals(ClaimError.NETWORK, model.state.value.error)
            val queued = model.state.value.reports.single()
            assertNull(queued.receipt)
            val operation = queued.operation
            // Recreate the ViewModel while preserving its real SQLite queue.
            models.clear()
            model = ClaimViewModel(app, claims).also { models.put("resumed", it) }
            control("online")
            model.show(user.id)
            settle()
            assertNull("Restored report failure", model.state.value.error)
            val confirmed = model.state.value.reports.single()
            assertEquals(operation, confirmed.operation)
            val receipt = checkNotNull(confirmed.receipt)
            assertEquals("registrado", receipt.status)
            assertEquals(receipt.id, claims.send(confirmed).id)
            assertEquals(1, claims.claims(user.id).count { it.id == receipt.id })
            assertEquals(ClaimProgress.REPORTED, claims.status(user.id, receipt.id).progress)

            val scope = EvidenceScope(user.id, receipt.id)
            val configuration = http.configuration(user.id)
            val image = File(app.cacheDir, "local-acceptance-synthetic.jpg")
            val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
            try {
                image.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
            } finally { bitmap.recycle() }
            try {
                EvidenceStore(app.noBackupFilesDir).use { store ->
                    val item = store.enqueue(scope, EvidenceKind.PHOTO, image, Instant.now(), null, 0,
                        configuration.policy, emptySet())
                    val transport = http.transport(store, configuration.copy(maxChunkBytes = 64))
                    control("drop-upload-ack")
                    try {
                        transport.upload(item, store.file(item)) {}
                        fail("The lost upload acknowledgement must remain an error")
                    } catch (error: EvidenceProblem) { assertEquals(EvidenceError.NETWORK, error.reason) }
                    assertNotNull(store.uploadId(item))
                    val accepted = transport.upload(item, store.file(item)) {}
                    store.confirm(item, accepted)
                    assertEquals(setOf(accepted.remoteId), http.confirmedIds(scope))
                    assertEquals(accepted.remoteId, transport.upload(item, store.file(item)) {}.remoteId)
                    assertEquals(EvidenceStatus.CONFIRMED, store.list(scope).single().status)
                }
            } finally { check(image.delete()) }

            val location = AssistanceLocation(4.656, -74.056, AssistanceLocationOrigin.MANUAL, Instant.now())
            val assistance = MobileAssistanceApi(http)
            val provider = assistance.providers(user.id, location, 10).first()
            val key = UUID.randomUUID().toString()
            val registered = assistance.request(user.id, receipt.id, provider, location, key)
            assertEquals("registrada", registered.status)
            assertEquals(registered, assistance.request(user.id, receipt.id, provider, location, key))
            val notifications = MobileNotificationApi(auth, base)
            assertTrue(notifications.notifications(user.id).isEmpty())
            assertEquals(12, notifications.preferences(user.id).size)
            assertTrue(notifications.updatePush(user.id, false)
                .filter { it.channel == NotificationChannel.PUSH }.none { it.enabled })
            val active = checkNotNull(grant)
            auth.logout()
            try {
                authApi.refresh(active.refreshToken)
                fail("Revoked refresh token was accepted")
            } catch (error: AuthHttpError) { assertEquals(401, error.status) }
        } finally {
            control("online")
            models.clear()
            if (grant != null) auth.logout()
        }
    }
}
