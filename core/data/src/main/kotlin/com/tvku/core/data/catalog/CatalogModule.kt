package com.tvku.core.data.catalog

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
internal abstract class CatalogModule {
    @Binds abstract fun bindFetcher(impl: HttpsCatalogFetcher): CatalogFetcher
    @Binds abstract fun bindCache(impl: FileCatalogCache): CatalogCache
}
