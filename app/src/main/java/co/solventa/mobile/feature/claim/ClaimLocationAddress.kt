package co.solventa.mobile.feature.claim

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import co.solventa.mobile.core.location.DeviceLocation
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull

internal suspend fun claimLocationAddress(context: Context, location: DeviceLocation,
    geocoder: Geocoder = Geocoder(context, Locale.getDefault()),
): String? {
    location.knownAddress?.let { return it }
    if (!Geocoder.isPresent()) return null
    return try {
        val addresses = if (Build.VERSION.SDK_INT >= 33) {
            withTimeoutOrNull(4_000) {
                suspendCancellableCoroutine { continuation ->
                    geocoder.getFromLocation(location.latitude, location.longitude, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            if (continuation.isActive) continuation.resume(addresses)
                        }
                        override fun onError(errorMessage: String?) {
                            if (continuation.isActive) continuation.resume(emptyList())
                        }
                    })
                }
            }
        } else {
            // The legacy system geocoder is blocking; never run it on the UI thread.
            runInterruptible(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(location.latitude, location.longitude, 1)
            }
        }
        addresses?.firstOrNull()?.getAddressLine(0)?.trim()?.takeIf {
            it.isNotBlank() && it.codePointCount(0, it.length) <= 300
        }
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IllegalStateException) {
        null
    } catch (_: SecurityException) {
        null
    }
}

internal fun claimCoordinatePlace(location: DeviceLocation): String =
    String.format(Locale.ROOT, "Coordenadas: %.6f, %.6f", location.latitude, location.longitude)
