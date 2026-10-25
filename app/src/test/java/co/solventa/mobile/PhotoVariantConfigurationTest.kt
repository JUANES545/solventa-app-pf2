package co.solventa.mobile

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class PhotoVariantConfigurationTest {
    @Test fun localPhotoReviewIsAvailableWithoutStorageOrMicrophoneAccess() {
        val application = RuntimeEnvironment.getApplication()
        val info = application.packageManager.getPackageInfo(
            application.packageName, PackageManager.GET_PERMISSIONS or PackageManager.GET_PROVIDERS)
        assertTrue(BuildConfig.PHOTO_REVIEW_ENABLED)
        assertTrue(info.requestedPermissions.orEmpty().contains(Manifest.permission.CAMERA))
        for (permission in listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_MEDIA_IMAGES)) {
            assertFalse(info.requestedPermissions.orEmpty().contains(permission))
        }
        assertFalse(info.providers.orEmpty().any { it.name.contains("FileProvider") || it.name.contains("Photo") })
        val cameraPresent = try {
            Class.forName("androidx.camera.core.ImageCapture")
            true
        } catch (_: ClassNotFoundException) { false }
        assertTrue(cameraPresent)
        val detectorPresent = try {
            Class.forName("com.google.mlkit.vision.face.FaceDetection", false, javaClass.classLoader)
            true
        } catch (_: ClassNotFoundException) { false }
        assertTrue(detectorPresent)
    }
}
