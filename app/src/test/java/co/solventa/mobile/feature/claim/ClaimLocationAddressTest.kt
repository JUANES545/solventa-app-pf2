package co.solventa.mobile.feature.claim

import android.Manifest
import android.app.Application
import android.location.Address
import android.location.Geocoder
import android.location.LocationManager
import co.solventa.mobile.BuildConfig
import co.solventa.mobile.core.location.DeviceLocation
import co.solventa.mobile.core.location.currentDeviceLocation
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowGeocoder

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ClaimLocationAddressTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val location = DeviceLocation(4.5, -74.1, Instant.parse("2026-11-10T12:00:00Z"))

    @Test fun systemGeocoderReturnsOnlyItsReadableAddressLine() = runBlocking {
        ShadowGeocoder.setIsPresent(true)
        val geocoder = Geocoder(context)
        val address = Address(Locale.ROOT).apply { setAddressLine(0, "  Calle sintética 10, Ciudad de prueba  ") }
        shadowOf(geocoder).setFromLocation(listOf(address))
        assertEquals("Calle sintética 10, Ciudad de prueba", claimLocationAddress(context, location, geocoder))
    }

    @Test @Config(sdk = [26]) fun legacySystemGeocoderAlsoResolvesWithoutUiThreadWork() = runBlocking {
        ShadowGeocoder.setIsPresent(true)
        val geocoder = Geocoder(context)
        shadowOf(geocoder).setFromLocation(listOf(Address(Locale.ROOT).apply { setAddressLine(0, "Lugar sintético") }))
        assertEquals("Lugar sintético", claimLocationAddress(context, location, geocoder))
    }

    @Test fun absentEmptyAndFailedGeocoderReturnNoInventedAddress() = runBlocking {
        val geocoder = Geocoder(context)
        ShadowGeocoder.setIsPresent(false)
        assertNull(claimLocationAddress(context, location, geocoder))
        ShadowGeocoder.setIsPresent(true)
        shadowOf(geocoder).setFromLocation(emptyList())
        assertNull(claimLocationAddress(context, location, geocoder))
        shadowOf(geocoder).setErrorMessage("synthetic service failure")
        assertNull(claimLocationAddress(context, location, geocoder))
    }

    @Test fun unusableAddressDoesNotBreakThePayloadLimit() = runBlocking {
        ShadowGeocoder.setIsPresent(true)
        val geocoder = Geocoder(context)
        for (text in listOf("", " ", "x".repeat(301))) {
            shadowOf(geocoder).setFromLocation(listOf(Address(Locale.ROOT).apply { setAddressLine(0, text) }))
            assertNull(claimLocationAddress(context, location, geocoder))
        }
    }

    @Test fun missingPermissionAndDisabledProvidersRespectBuildLocationMode() = runBlocking {
        shadowOf(context).denyPermissions(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        assertNull(currentDeviceLocation(context))
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        val manager = context.getSystemService(LocationManager::class.java)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
        shadowOf(manager).setProviderEnabled(LocationManager.FUSED_PROVIDER, false)
        val location = currentDeviceLocation(context)
        if (BuildConfig.DEMO_LOCATION) {
            assertEquals("Cra. 7 # 72-41, Bogotá", location?.knownAddress)
        } else {
            assertNull(location)
        }
    }
}
