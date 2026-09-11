package com.rollapp.shared.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * A scope that outlives any screen.
 *
 * Some work must finish even though the screen that started it is already gone.
 * Provisioning a user's profile is the clearest case: the instant Firebase Auth
 * returns, the auth-state listener navigates away from the sign-in screen, its
 * ViewModel is cleared, and anything still suspended in `viewModelScope` is
 * cancelled — silently, because cancellation is not an error. The profile write
 * never reaches the server and every later read falls back to "Someone".
 *
 * SupervisorJob so one failed job cannot take the scope down with it.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object CoroutineModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
