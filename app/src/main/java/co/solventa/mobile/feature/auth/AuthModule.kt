package co.solventa.mobile.feature.auth

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AuthModule {
    @Provides
    @Singleton
    fun repository(api: MobileAuthApi, store: EncryptedAuthStore): AuthRepository = AuthRepository(api, store)
}
