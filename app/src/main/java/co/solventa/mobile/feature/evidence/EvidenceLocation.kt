package co.solventa.mobile.feature.evidence

import android.content.Context
import co.solventa.mobile.core.location.currentDeviceLocation
import co.solventa.mobile.core.location.hasLocationPermission

internal fun hasEvidenceLocationPermission(context: Context): Boolean =
    hasLocationPermission(context)

internal suspend fun evidenceLocation(context: Context): EvidenceLocation? =
    currentDeviceLocation(context)?.let {
        EvidenceLocation(it.latitude, it.longitude, it.measuredAt)
    }
