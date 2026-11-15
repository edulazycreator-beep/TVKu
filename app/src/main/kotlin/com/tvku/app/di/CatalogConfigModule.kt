package com.tvku.app.di

import com.tvku.app.BuildConfig
import com.tvku.core.data.catalog.CatalogUrl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object CatalogConfigModule {
    @Provides
    @CatalogUrl
    fun provideCatalogUrl(): String = BuildConfig.CATALOG_URL
}
