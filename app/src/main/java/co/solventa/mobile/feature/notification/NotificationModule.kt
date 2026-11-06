package co.solventa.mobile.feature.notification

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class NotificationModule {
    @Binds
    @Singleton
    abstract fun api(implementation: MobileNotificationApi): NotificationApi

    @Binds
    @Singleton
    abstract fun store(implementation: AndroidNotificationStore): NotificationLocalStore

    @Binds
    @Singleton
    abstract fun presenter(implementation: AndroidNotificationPresenter): NotificationPresenter
}
