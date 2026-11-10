package co.solventa.mobile.core.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import co.solventa.mobile.BuildConfig
import java.time.Instant
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

internal const val DEVICE_LOCATION_TIMEOUT_MILLIS = 30_000L

internal data class DeviceLocation(
    val latitude: Double,
    val longitude: Double,
    val measuredAt: Instant,
    val knownAddress: String? = null,
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
    if (BuildConfig.DEMO_LOCATION) {
        return DeviceLocation(4.6560, -74.0560, Instant.now(), "Cra. 7 # 72-41, Bogotá")
    }
    val manager = context.getSystemService(LocationManager::class.java)
    val fine = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    val providers = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
        add(LocationManager.NETWORK_PROVIDER)
        if (fine) add(LocationManager.GPS_PROVIDER)
    }.filter { manager.isProviderEnabled(it) }
    if (providers.isEmpty()) return null
    return withTimeoutOrNull(DEVICE_LOCATION_TIMEOUT_MILLIS) {
        val results = Channel<DeviceLocation?>(providers.size)
        val requests = providers.map { provider ->
            launch { results.send(requestDeviceLocation(context, manager, provider)) }
        }
        try {
            repeat(providers.size) {
                results.receive()?.let { return@withTimeoutOrNull it }
            }
            null
        } finally {
            requests.forEach { it.cancel() }
        }
    }
}

private suspend fun requestDeviceLocation(
    context: Context,
    manager: LocationManager,
    provider: String,
): DeviceLocation? = suspendCancellableCoroutine { continuation ->
    val signal = CancellationSignal()
    continuation.invokeOnCancellation { signal.cancel() }
    try {
        LocationManagerCompat.getCurrentLocation(
            manager,
            provider,
            signal,
            ContextCompat.getMainExecutor(context),
        ) { location: Location? ->
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
    } catch (_: IllegalArgumentException) {
        if (continuation.isActive) continuation.resume(null)
    }
}
