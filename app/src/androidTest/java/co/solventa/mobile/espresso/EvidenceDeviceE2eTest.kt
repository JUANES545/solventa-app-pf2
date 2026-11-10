package co.solventa.mobile.espresso

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.media.MediaCodec
import android.media.ExifInterface
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import co.solventa.mobile.BuildConfig
import co.solventa.mobile.MainActivity
import co.solventa.mobile.R
import co.solventa.mobile.feature.auth.AuthRepository
import co.solventa.mobile.feature.evidence.EvidenceKind
import co.solventa.mobile.feature.evidence.EvidenceMedia
import co.solventa.mobile.feature.evidence.EvidencePolicy
import co.solventa.mobile.feature.evidence.EvidenceStatus
import co.solventa.mobile.feature.evidence.EvidenceStep
import co.solventa.mobile.feature.evidence.EvidenceStore
import co.solventa.mobile.feature.evidence.EvidenceScope
import co.solventa.mobile.feature.evidence.EvidenceError
import co.solventa.mobile.feature.evidence.EvidenceViewModel
import co.solventa.mobile.feature.evidence.MobileEvidenceApi
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class EvidenceDeviceE2e

@EvidenceDeviceE2e
@HiltAndroidTest
class EvidenceDeviceE2eTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    @Inject lateinit var auth: AuthRepository
    @Inject internal lateinit var api: MobileEvidenceApi
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var model: EvidenceViewModel
    private lateinit var fixture: JSONObject

    @Before fun prepare() {
        check(BuildConfig.APPLICATION_ID == "co.solventa.mobile.pf2.evidencetest") { "Use the isolated evidenceTest variant" }
        check(localBackendUrl() == BuildConfig.AUTH_BASE_URL)
        hilt.inject()
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        fixture = JSONObject(File(context.filesDir, "evidence-fixture.json").readText())
        runBlocking { auth.login(fixture.getString("email"), fixture.getString("password")) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity {
            it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            model = ViewModelProvider(it)[EvidenceViewModel::class.java]
        }
    }

    @After fun finish() { scenario?.close() }

    private fun text(id: Int) = context.getString(id)
    private fun click(id: Int) = compose.onNodeWithText(text(id)).performScrollTo().performClick()
    private fun useCamera(kind: EvidenceKind) {
        click(when (kind) {
            EvidenceKind.PHOTO -> R.string.evidence_gallery_photo
            EvidenceKind.DOCUMENT -> R.string.evidence_gallery_document
            EvidenceKind.VIDEO -> R.string.evidence_gallery_video
        })
        compose.onNodeWithTag("evidence-source-camera").performClick()
    }
    private fun await(id: Int) {
        compose.waitUntil(30_000) { compose.onAllNodesWithText(text(id)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun settle(step: EvidenceStep) {
        compose.waitUntil(40_000) { model.state.value.step == step && !model.state.value.busy }
        assertNull("Evidence error: ${model.state.value.error}", model.state.value.error)
    }

    private fun denyPermission(expected: EvidenceError) {
        compose.waitForIdle()
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (model.state.value.error == expected) return
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val node = root?.findAccessibilityNodeInfosByViewId("com.android.permissioncontroller:id/permission_deny_button")?.firstOrNull()
                ?: root?.findAccessibilityNodeInfosByViewId("com.google.android.permissioncontroller:id/permission_deny_button")?.firstOrNull()
                ?: root?.findAccessibilityNodeInfosByText("No permitir")?.firstOrNull()
                ?: root?.findAccessibilityNodeInfosByText("Don’t allow")?.firstOrNull()
            if (node != null && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            Thread.sleep(100)
        }
        val root = instrumentation.uiAutomation.rootInActiveWindow
        fun describe(node: AccessibilityNodeInfo?, depth: Int = 0): String {
            if (node == null || depth > 5) return ""
            return "${node.packageName}:${node.viewIdResourceName}:${node.text} " +
                (0 until node.childCount).joinToString { describe(node.getChild(it), depth + 1) }
        }
        fail("Permission dialog deny control was not available; step=${model.state.value.step}; error=${model.state.value.error}; ${describe(root)}")
    }

    private fun addSyntheticVideoLocation(file: File) {
        val staged = File(file.parentFile, "${file.name}.geo.mp4")
        val extractor = MediaExtractor()
        val muxer = MediaMuxer(staged.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            extractor.setDataSource(file.path)
            assertEquals(1, extractor.trackCount)
            val track = muxer.addTrack(extractor.getTrackFormat(0))
            extractor.selectTrack(0)
            muxer.setLocation(4.6f, -74f)
            muxer.start()
            val buffer = java.nio.ByteBuffer.allocateDirect(4 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                buffer.clear()
                val length = extractor.readSampleData(buffer, 0)
                if (length < 0) break
                info.set(0, length, extractor.sampleTime,
                    if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(track, buffer, info)
                extractor.advance()
            }
            muxer.stop()
        } finally {
            extractor.release()
            muxer.release()
        }
        java.nio.file.Files.move(staged.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        val metadata = MediaMetadataRetriever()
        try {
            metadata.setDataSource(file.path)
            assertNotNull(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION))
        } finally { metadata.release() }
    }

    @Test fun physicalCaptureCropVideoAndConfirmedBackend() {
        await(R.string.evidence_entry)
        click(R.string.evidence_entry)
        compose.waitUntil(30_000) { model.state.value.claims.isNotEmpty() && !model.state.value.busy }
        val claim = model.state.value.claims.single { it.id == fixture.getString("claimId") }
        compose.onNodeWithTag("claim-${claim.id}").performScrollTo().performClick()
        settle(EvidenceStep.LIST)

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            useCamera(EvidenceKind.PHOTO)
            denyPermission(EvidenceError.PERMISSION)
            await(R.string.evidence_error_permission)
        }
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        useCamera(EvidenceKind.PHOTO)
        val locationLabel = text(R.string.evidence_location)
        compose.onNodeWithContentDescription(locationLabel).performScrollTo().performClick()
        if (!co.solventa.mobile.feature.evidence.hasEvidenceLocationPermission(context)) denyPermission(EvidenceError.LOCATION)
        compose.waitUntil(15_000) { model.state.value.location == null }
        compose.runOnIdle { model.enableLocation(false); model.back() }

        for (kind in listOf(EvidenceKind.PHOTO, EvidenceKind.DOCUMENT, EvidenceKind.VIDEO)) {
            useCamera(kind)
            await(R.string.evidence_take)
            Thread.sleep(1500)
            click(R.string.evidence_take)
            if (kind == EvidenceKind.VIDEO) {
                await(R.string.evidence_stop)
                Thread.sleep(2200)
                click(R.string.evidence_stop)
            }
            settle(EvidenceStep.REVIEW)
            if (kind == EvidenceKind.PHOTO) {
                click(R.string.evidence_gallery_replace)
                compose.onNodeWithTag("evidence-source-camera").performClick()
                await(R.string.evidence_take)
                Thread.sleep(1000)
                click(R.string.evidence_take)
                settle(EvidenceStep.REVIEW)
            }
            if (kind == EvidenceKind.DOCUMENT) {
                val before = checkNotNull(model.state.value.preview?.bitmap).width
                click(R.string.evidence_edit_crop)
                compose.onNodeWithContentDescription(text(R.string.evidence_crop_left)).performScrollTo()
                    .performSemanticsAction(SemanticsActions.SetProgress) { it(.2f) }
                click(R.string.evidence_crop)
                settle(EvidenceStep.REVIEW)
                assertTrue(checkNotNull(model.state.value.preview?.bitmap).width < before)
            }
            val draft = checkNotNull(model.state.value.draft)
            if (kind == EvidenceKind.VIDEO) {
                addSyntheticVideoLocation(draft.file)
                val media = EvidenceMedia(context.cacheDir)
                val policy = checkNotNull(model.state.value.policy)
                val imported = draft.file.inputStream().use { media.importFile(it, EvidenceKind.VIDEO, policy) }
                try {
                    val preview = media.inspect(imported, EvidenceKind.VIDEO, policy)
                    assertTrue(preview.durationMillis > 0)
                    preview.bitmap?.recycle()
                    val metadata = MediaMetadataRetriever()
                    try {
                        metadata.setDataSource(imported.path)
                        assertNull(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
                        assertNull(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION))
                    } finally { metadata.release() }
                    for (invalidPolicy in listOf(
                        EvidencePolicy(1, mapOf(EvidenceKind.VIDEO to 1L), policy.maxVideoDurationMillis),
                        EvidencePolicy(1, policy.maxBytes, 1),
                    )) {
                        try {
                            draft.file.inputStream().use { media.importFile(it, EvidenceKind.VIDEO, invalidPolicy) }
                            fail("Invalid video must not pass import")
                        } catch (expected: co.solventa.mobile.feature.evidence.EvidenceProblem) {
                            assertTrue(expected.reason in setOf(EvidenceError.SIZE, EvidenceError.DURATION))
                        }
                    }
                } finally { media.delete(imported) }
            }
            else ExifInterface(draft.file.path).apply {
                setAttribute(ExifInterface.TAG_GPS_LATITUDE, "4/1,36/1,0/1")
                setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
                setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "74/1,0/1,0/1")
                setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "W")
                saveAttributes()
            }
            click(R.string.evidence_gallery_attach)
            settle(EvidenceStep.LIST)
            assertTrue(model.state.value.items.any { it.kind == kind && it.status == EvidenceStatus.PENDING })
        }
        assertTrue(model.state.value.items.all { it.location == null })
        val scope = checkNotNull(model.state.value.scope)
        val localIds = model.state.value.items.map { it.id }.toSet()
        EvidenceStore(context.noBackupFilesDir).use { store ->
            for (image in store.list(scope).filter { it.kind != EvidenceKind.VIDEO }) {
                assertFalse(ExifInterface(store.file(image).path).getLatLong(FloatArray(2)))
            }
            val video = store.list(scope).single { it.kind == EvidenceKind.VIDEO }
            val metadata = MediaMetadataRetriever()
            try {
                metadata.setDataSource(store.file(video).path)
                assertNull(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION))
                assertEquals("yes", metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO))
            } finally { metadata.release() }
        }
        click(R.string.evidence_retry)
        compose.waitUntil(90_000) { !model.state.value.busy }
        assertNull("Upload error: ${model.state.value.error}", model.state.value.error)
        assertEquals(3, model.state.value.items.count { it.status == EvidenceStatus.CONFIRMED })
        val remote = runBlocking { api.confirmedIds(scope) }
        assertTrue(remote.containsAll(model.state.value.items.map { checkNotNull(it.remoteId) }))
        val policy = checkNotNull(model.state.value.policy)
        compose.runOnIdle { model.close(); model.open(scope, policy, remote) }
        settle(EvidenceStep.LIST)
        assertEquals(localIds, model.state.value.items.map { it.id }.toSet())
        assertTrue(model.state.value.items.all { it.status == EvidenceStatus.CONFIRMED })
    }

    @Test fun durableInterruptedUploadAcrossProcesses() {
        val phase = InstrumentationRegistry.getArguments().getString("recoveryPhase")
        check(phase in setOf("interrupt", "resume"))
        val snapshotFile = File(context.filesDir, "evidence-recovery.json")
        val scope = EvidenceScope(fixture.getString("actorId"), fixture.getString("claimId"))
        if (phase == "resume") {
            val saved = JSONObject(snapshotFile.readText())
            EvidenceStore(context.noBackupFilesDir).use { store ->
                val pending = store.list(scope).single { it.id == saved.getString("id") }
                assertEquals(EvidenceStatus.PENDING, pending.status)
                assertEquals(saved.getString("uploadId"), store.uploadId(pending))
                assertEquals(saved.getString("sha256"), pending.sha256)
            }
        }
        await(R.string.evidence_entry)
        click(R.string.evidence_entry)
        compose.waitUntil(30_000) { model.state.value.claims.isNotEmpty() && !model.state.value.busy }
        compose.onNodeWithTag("claim-${scope.claimId}").performScrollTo().performClick()
        if (phase == "interrupt") {
            settle(EvidenceStep.LIST)
            useCamera(EvidenceKind.PHOTO)
            await(R.string.evidence_take)
            Thread.sleep(1200)
            click(R.string.evidence_take)
            settle(EvidenceStep.REVIEW)
            click(R.string.evidence_gallery_attach)
            settle(EvidenceStep.LIST)
            val before = model.state.value.items.filter { it.status == EvidenceStatus.CONFIRMED }.map { checkNotNull(it.remoteId) }
            click(R.string.evidence_retry)
            compose.waitUntil(45_000) { !model.state.value.busy && model.state.value.error != null }
            assertEquals(co.solventa.mobile.feature.evidence.EvidenceError.NETWORK, model.state.value.error)
            val pending = model.state.value.items.single { it.status == EvidenceStatus.PENDING }
            EvidenceStore(context.noBackupFilesDir).use { store ->
                snapshotFile.writeText(JSONObject().put("id", pending.id).put("uploadId", checkNotNull(store.uploadId(pending)))
                    .put("sha256", pending.sha256).put("confirmedCount", before.size)
                    .put("confirmedIds", org.json.JSONArray(before)).toString())
            }
            assertEquals(before.toSet(), runBlocking { api.confirmedIds(scope) })
        } else {
            val saved = JSONObject(snapshotFile.readText())
            // Opening a recovered claim triggers retry without pressing the send button.
            compose.waitUntil(90_000) {
                !model.state.value.busy && model.state.value.items.any {
                    it.id == saved.getString("id") && it.status == EvidenceStatus.CONFIRMED
                }
            }
            assertNull(model.state.value.error)
            val remote = runBlocking { api.confirmedIds(scope) }
            assertEquals(saved.getInt("confirmedCount") + 1, remote.size)
            val previous = saved.getJSONArray("confirmedIds")
            for (i in 0 until previous.length()) assertTrue(remote.contains(previous.getString(i)))
            assertEquals(saved.getString("sha256"), model.state.value.items.single { it.id == saved.getString("id") }.sha256)
        }
    }

    @Test fun serverRejectionDoesNotBlockValidEvidenceAndRemovalIsExplicit() {
        val scope = EvidenceScope(fixture.getString("actorId"), fixture.getString("claimId"))
        val configuration = runBlocking { api.configuration(scope.actorId) }
        val remote = runBlocking { api.confirmedIds(scope) }
        val invalid = File.createTempFile("invalid-evidence-", ".jpg", context.cacheDir).apply { writeText("not a JPEG") }
        val valid = File.createTempFile("valid-evidence-", ".jpg", context.cacheDir)
        android.graphics.Bitmap.createBitmap(64, 48, android.graphics.Bitmap.Config.ARGB_8888).let { bitmap ->
            valid.outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it)) }
            bitmap.recycle()
        }
        val rejectedId: String
        val validId: String
        EvidenceStore(context.noBackupFilesDir).use { store ->
            rejectedId = store.enqueue(scope, EvidenceKind.PHOTO, invalid, java.time.Instant.now(), null, 0,
                configuration.policy, remote).id
            validId = store.enqueue(scope, EvidenceKind.PHOTO, valid, java.time.Instant.now(), null, 0,
                configuration.policy, remote).id
        }
        assertTrue(invalid.delete())
        assertTrue(valid.delete())
        await(R.string.evidence_entry)
        click(R.string.evidence_entry)
        compose.waitUntil(30_000) { model.state.value.claims.isNotEmpty() && !model.state.value.busy }
        compose.onNodeWithTag("claim-${scope.claimId}").performScrollTo().performClick()
        compose.waitUntil(90_000) {
            !model.state.value.busy &&
                model.state.value.items.any { it.id == rejectedId && it.status == EvidenceStatus.REJECTED } &&
                model.state.value.items.any { it.id == validId && it.status == EvidenceStatus.CONFIRMED }
        }
        val rejected = model.state.value.items.single { it.id == rejectedId }
        assertEquals(EvidenceError.FORMAT, rejected.rejection)
        EvidenceStore(context.noBackupFilesDir).use { assertTrue(it.file(rejected).exists()) }
        compose.onNodeWithTag("evidence-tile-$rejectedId").performScrollTo().performClick()
        click(R.string.evidence_gallery_remove)
        compose.onAllNodesWithText(text(R.string.evidence_gallery_remove)).onLast().performClick()
        compose.waitUntil(30_000) { !model.state.value.busy && model.state.value.items.none { it.id == rejectedId } }
        assertNull(model.state.value.error)
        assertEquals(remote.size + 1, runBlocking { api.confirmedIds(scope) }.size)
        assertEquals(EvidenceStatus.CONFIRMED, model.state.value.items.single { it.id == validId }.status)
    }
}
