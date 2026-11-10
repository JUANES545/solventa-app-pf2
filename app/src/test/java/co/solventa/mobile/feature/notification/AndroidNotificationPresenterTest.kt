package co.solventa.mobile.feature.notification

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class AndroidNotificationPresenterTest {
    @Test
    fun permissionIncludesRuntimeGlobalSwitchAndChannel() {
        val app = RuntimeEnvironment.getApplication()
        val presenter = AndroidNotificationPresenter(app)
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(presenter.permissionGranted())
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = app.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(true)
        assertTrue(presenter.permissionGranted())
        shadowOf(manager).setNotificationsEnabled(false)
        assertFalse(presenter.permissionGranted())
        shadowOf(manager).setNotificationsEnabled(true)
        manager.createNotificationChannel(NotificationChannel(
            AndroidNotificationPresenter.CHANNEL_ID, "Avisos", NotificationManager.IMPORTANCE_NONE,
        ))
        assertFalse(presenter.permissionGranted())
    }

    @Test
    fun revokingRuntimePermissionIsObservedWithoutRecreatingThePresenter() {
        val app = RuntimeEnvironment.getApplication()
        val manager = app.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(true)
        manager.createNotificationChannel(NotificationChannel(
            AndroidNotificationPresenter.CHANNEL_ID, "Avisos", NotificationManager.IMPORTANCE_DEFAULT,
        ))
        val presenter = AndroidNotificationPresenter(app)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(presenter.permissionGranted())
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(presenter.permissionGranted())
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(presenter.permissionGranted())
    }
}
