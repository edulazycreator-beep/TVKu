package com.tvku.core.data.catalog

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Real network test on an Android runtime: the published catalog must download and parse. */
@RunWith(AndroidJUnit4::class)
class CatalogEndpointDeviceTest {
    @Test
    fun publishedCatalogDownloadsAndParsesOnAndroid() = runBlocking {
        val text = try {
            HttpsCatalogFetcher(URL, Dispatchers.IO).fetch()
        } catch (e: Exception) {
            Log.e("TVKuDeviceTest", "fetch failed", e)
            fail("fetch failed: ${e.javaClass.name}: ${e.message} / cause: ${e.cause}")
            return@runBlocking
        }
        val channels = try {
            CatalogParser.parse(text)
        } catch (e: Exception) {
            fail("parse failed: ${e.javaClass.name}: ${e.message} / cause: ${e.cause} / first bytes: ${text.take(200)}")
            return@runBlocking
        }
        Log.i("TVKuDeviceTest", "parsed ${channels.size} channels: ${channels.map { it.id }}")
        assertTrue("expected official YouTube live channels, got ${channels.map { it.id }}", channels.count { it.playable != null } >= 4)
        assertTrue("expected official links", channels.count { it.officialLink != null } >= 10)
    }

    private companion object {
        const val URL = "https://github.com/edulazycreator-beep/TVKu/releases/download/catalog/catalog-v1.json"
    }
}
