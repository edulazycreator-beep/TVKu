package com.tvku.app.di

import com.tvku.core.common.ApplicationScope
import com.tvku.core.common.Dispatcher
import com.tvku.core.common.TvkuDispatchers
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CoroutinesModule {
    @Provides @Dispatcher(TvkuDispatchers.IO)
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides @Dispatcher(TvkuDispatchers.Default)
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides @Singleton @ApplicationScope
    fun provideApplicationScope(@Dispatcher(TvkuDispatchers.Default) dispatcher: CoroutineDispatcher): CoroutineScope =
        CoroutineScope(SupervisorJob() + dispatcher)
}
