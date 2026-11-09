package co.solventa.mobile.feature.assistance

import android.content.Context
import co.solventa.mobile.core.location.currentDeviceLocation
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

internal interface AssistanceLocator {
    suspend fun current(): AssistanceLocation?
}

internal interface AssistanceGateway {
    suspend fun providers(
        actor: String,
        location: AssistanceLocation,
        radiusKm: Int = 50,
    ): List<AssistanceProvider>

    suspend fun request(
        actor: String,
        claimId: String,
        provider: AssistanceProvider,
        location: AssistanceLocation,
        operationId: String,
    ): AssistanceReceipt
}

internal class AndroidAssistanceLocator @Inject constructor(
    @ApplicationContext private val context: Context,
) : AssistanceLocator {
    override suspend fun current(): AssistanceLocation? =
        currentDeviceLocation(context)?.let {
            AssistanceLocation(
                it.latitude,
                it.longitude,
                AssistanceLocationOrigin.PERMISSION,
                it.measuredAt,
            )
        }
}

internal class FixedAssistanceLocator(
    private val location: AssistanceLocation? = null,
) : AssistanceLocator {
    override suspend fun current(): AssistanceLocation? = location
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class AssistanceModule {
    @Binds
    @Singleton
    abstract fun locator(implementation: AndroidAssistanceLocator): AssistanceLocator

    @Binds
    @Singleton
    abstract fun gateway(implementation: MobileAssistanceApi): AssistanceGateway
}

internal fun manualAssistanceLocation(latitude: String, longitude: String): AssistanceLocation {
    val lat = latitude.trim().toDoubleOrNull()
        ?: throw AssistanceProblem(AssistanceError.INVALID_LOCATION)
    val lon = longitude.trim().toDoubleOrNull()
        ?: throw AssistanceProblem(AssistanceError.INVALID_LOCATION)
    return try {
        AssistanceLocation(
            lat,
            lon,
            AssistanceLocationOrigin.MANUAL,
            Instant.now(),
        )
    } catch (error: IllegalArgumentException) {
        throw AssistanceProblem(AssistanceError.INVALID_LOCATION, error)
    }
}
