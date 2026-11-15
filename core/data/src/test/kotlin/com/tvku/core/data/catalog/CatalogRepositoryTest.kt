package com.tvku.core.data.catalog

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

private class FakeFetcher(var result: () -> String) : CatalogFetcher {
    override suspend fun fetch(): String = result()
}

private class FakeCache(var stored: String? = null) : CatalogCache {
    var writes = 0
    override suspend fun read() = stored
    override suspend fun write(text: String) { stored = text; writes++ }
}

class CatalogRepositoryTest {
    private val sample = CatalogParserTest.SAMPLE

    @Test
    fun `network catalog is published and cached`() = runTest {
        val cache = FakeCache()
        val repo = CatalogRepository(FakeFetcher { sample }, cache)
        repo.refresh()
        assertEquals(2, repo.state.value.channels.size)
        assertEquals(CatalogState.Origin.NETWORK, repo.state.value.origin)
        assertNull(repo.state.value.lastError)
        assertEquals(sample, cache.stored)
        assertEquals("KompasTV", repo.channel("kompastv-id")?.name)
    }

    @Test
    fun `offline start uses the cached catalog and reports the problem`() = runTest {
        val repo = CatalogRepository(FakeFetcher { throw IOException("offline") }, FakeCache(sample))
        repo.refresh()
        assertEquals(2, repo.state.value.channels.size)
        assertEquals(CatalogState.Origin.CACHE, repo.state.value.origin)
        assertEquals(CatalogState.Problem.OFFLINE_OR_SERVER, repo.state.value.lastError)
        assertFalse(repo.state.value.isRefreshing)
    }

    @Test
    fun `invalid catalog never replaces a good one or reaches the cache`() = runTest {
        val cache = FakeCache()
        val fetcher = FakeFetcher { sample }
        val repo = CatalogRepository(fetcher, cache)
        repo.refresh()
        fetcher.result = { """{"schema":99}""" }
        repo.refresh()
        assertEquals(2, repo.state.value.channels.size)
        assertEquals(CatalogState.Problem.INVALID_CATALOG, repo.state.value.lastError)
        assertEquals(1, cache.writes)
    }

    @Test
    fun `nothing available shows an empty catalog with an error`() = runTest {
        val repo = CatalogRepository(FakeFetcher { throw IOException("offline") }, FakeCache())
        repo.refresh()
        assertEquals(0, repo.state.value.channels.size)
        assertEquals(CatalogState.Origin.NONE, repo.state.value.origin)
    }
}
