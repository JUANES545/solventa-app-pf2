package co.solventa.mobile.espresso

import androidx.test.platform.app.InstrumentationRegistry
import co.solventa.mobile.feature.auth.AuthModule
import co.solventa.mobile.feature.auth.AuthRepository
import co.solventa.mobile.feature.auth.EncryptedAuthStore
import co.solventa.mobile.feature.auth.MobileAuthApi
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import java.net.URI
import javax.inject.Singleton

internal fun localBackendUrl(): String {
    val value = InstrumentationRegistry.getArguments().getString("backendUrl")
        ?: "http://127.0.0.1:18080/movil/v1"
    val uri = URI(value)
    require(uri.scheme == "http" && uri.host in setOf("127.0.0.1", "10.0.2.2") &&
        uri.port in 1024..65535 && uri.path == "/movil/v1" && uri.userInfo == null &&
        uri.query == null && uri.fragment == null) {
        "Tests require an explicit local HTTP backend on port >= 1024 with path /movil/v1."
    }
    return value
}

@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [AuthModule::class])
object LocalAuthModule {
    @Provides
    @Singleton
    fun repository(store: EncryptedAuthStore): AuthRepository =
        AuthRepository(MobileAuthApi(localBackendUrl()), store)
}
