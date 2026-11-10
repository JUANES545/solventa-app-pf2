package co.solventa.mobile.core.location

import android.Manifest
import android.app.Application
import android.location.LocationManager
import co.solventa.mobile.feature.assistance.AndroidAssistanceLocator
import co.solventa.mobile.feature.assistance.AssistanceLocationOrigin
import co.solventa.mobile.feature.claim.claimLocationAddress
import co.solventa.mobile.feature.evidence.evidenceLocation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowGeocoder

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class DemoLocationTest {
    @Test fun permissionIsRequiredForEveryFlow() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).denyPermissions(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )

        assertNull(currentDeviceLocation(context))
        assertNull(evidenceLocation(context))
        assertNull(AndroidAssistanceLocator(context).current())

        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        assertNotNull(currentDeviceLocation(context))
    }

    @Test fun allFlowsUseTheSameBogotaLocationWithoutProvidersOrGeocoder() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        val manager = shadowOf(context.getSystemService(LocationManager::class.java))
        manager.setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        manager.setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
        manager.setProviderEnabled(LocationManager.FUSED_PROVIDER, false)
        ShadowGeocoder.setIsPresent(false)

        val location = checkNotNull(currentDeviceLocation(context))
        assertEquals(4.6560, location.latitude, 0.0)
        assertEquals(-74.0560, location.longitude, 0.0)
        assertEquals("Cra. 7 # 72-41, Bogotá", claimLocationAddress(context, location))

        val evidence = checkNotNull(evidenceLocation(context))
        assertEquals(location.latitude, evidence.latitude, 0.0)
        assertEquals(location.longitude, evidence.longitude, 0.0)

        val assistance = checkNotNull(AndroidAssistanceLocator(context).current())
        assertEquals(location.latitude, assistance.latitude, 0.0)
        assertEquals(location.longitude, assistance.longitude, 0.0)
        assertEquals(AssistanceLocationOrigin.PERMISSION, assistance.origin)
    }
}
