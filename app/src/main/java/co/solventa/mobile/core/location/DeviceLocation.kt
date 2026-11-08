package co.solventa.mobile.core.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import java.time.Instant
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

internal data class DeviceLocation(
    val latitude: Double,
    val longitude: Double,
    val measuredAt: Instant,
) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
    }
}

internal fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

internal suspend fun currentDeviceLocation(context: Context): DeviceLocation? {
    if (!hasLocationPermission(context)) return null
    val manager = context.getSystemService(LocationManager::class.java)
    val provider = when {
        manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
            LocationManager.NETWORK_PROVIDER
        manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
            LocationManager.GPS_PROVIDER
        else -> return null
    }
    return withTimeoutOrNull(10_000) {
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            try {
                LocationManagerCompat.getCurrentLocation(
                    manager,
                    provider,
                    signal,
                    ContextCompat.getMainExecutor(context),
                ) { location ->
                    if (continuation.isActive) {
                        val fresh = location?.takeIf {
                            android.os.SystemClock.elapsedRealtimeNanos() -
                                it.elapsedRealtimeNanos in 0..60_000_000_000L
                        }
                        continuation.resume(
                            fresh?.let {
                                DeviceLocation(
                                    it.latitude,
                                    it.longitude,
                                    Instant.ofEpochMilli(it.time),
                                )
                            },
                        )
                    }
                }
            } catch (_: SecurityException) {
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }
}
