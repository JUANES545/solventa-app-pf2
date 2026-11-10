package co.solventa.mobile.core.location

import android.Manifest
import android.app.Application
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import co.solventa.mobile.BuildConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("DEPRECATION")
class DeviceLocationTest {
    @Before fun useRealLocationProvider() {
        assumeFalse(BuildConfig.DEMO_LOCATION)
    }

    @Test fun availableFusedLocationDoesNotWaitForAnUnresponsiveNetworkProvider() = runTest {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        val manager = context.getSystemService(LocationManager::class.java)
        val shadow = shadowOf(manager)
        shadow.setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        shadow.setProviderEnabled(LocationManager.FUSED_PROVIDER, true)
        shadow.setLastKnownLocation(LocationManager.FUSED_PROVIDER, Location(LocationManager.FUSED_PROVIDER).apply {
            latitude = 4.5
            longitude = -74.1
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        })

        val result = async { currentDeviceLocation(context) }
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(4.5, result.await()?.latitude)
    }

    @Test fun gpsCanRespondWhileOtherProvidersAreUnavailable() = runTest {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val manager = context.getSystemService(LocationManager::class.java)
        val shadow = shadowOf(manager)
        shadow.setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        shadow.setProviderEnabled(LocationManager.FUSED_PROVIDER, true)
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadow.setLastKnownLocation(LocationManager.GPS_PROVIDER, Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 4.5
            longitude = -74.1
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        })

        val result = async { currentDeviceLocation(context) }
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(4.5, result.await()?.latitude)
    }
}
