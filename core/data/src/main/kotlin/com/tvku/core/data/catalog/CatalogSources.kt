package com.tvku.core.data.catalog

import android.content.Context
import com.tvku.core.common.Dispatcher
import com.tvku.core.common.TvkuDispatchers
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Qualifier
import javax.net.ssl.HttpsURLConnection

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class CatalogUrl

interface CatalogFetcher {
    /** @throws IOException on network or HTTP failure. */
    suspend fun fetch(): String
}

interface CatalogCache {
    suspend fun read(): String?
    suspend fun write(text: String)
}

/** HTTPS-only download with timeouts and a size cap. No extra HTTP library needed. */
class HttpsCatalogFetcher @Inject constructor(
    @param:CatalogUrl private val url: String,
    @param:Dispatcher(TvkuDispatchers.IO) private val io: CoroutineDispatcher,
) : CatalogFetcher {
    override suspend fun fetch(): String = withContext(io) {
        require(url.startsWith("https://")) { "catalog URL must be https" }
        var target = URL(url)
        repeat(MAX_REDIRECTS + 1) {
            val conn = (target.openConnection() as HttpsURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "TVKu-Android")
            }
            try {
                when (val code = conn.responseCode) {
                    HttpURLConnection.HTTP_OK -> return@withContext conn.inputStream.use { input ->
                        val bytes = input.readNBytesCapped(MAX_BYTES)
                        String(bytes, Charsets.UTF_8)
                    }
                    in 301..308 -> {
                        val next = URL(target, conn.getHeaderField("Location") ?: throw IOException("redirect without location"))
                        if (next.protocol != "https") throw IOException("refusing non-https redirect")
                        target = next
                    }
                    else -> throw IOException("catalog HTTP $code")
                }
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("too many redirects")
    }

    private fun java.io.InputStream.readNBytesCapped(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) throw IOException("catalog too large")
        }
        return out.toByteArray()
    }

    private companion object {
        const val MAX_REDIRECTS = 5
        const val MAX_BYTES = 2 * 1024 * 1024
    }
}

/** Last good catalog, kept for offline start. Written atomically. */
class FileCatalogCache @Inject constructor(
    @ApplicationContext context: Context,
    @param:Dispatcher(TvkuDispatchers.IO) private val io: CoroutineDispatcher,
) : CatalogCache {
    private val file = File(context.filesDir, "catalog-v1.json")

    override suspend fun read(): String? = withContext(io) { if (file.exists()) file.readText() else null }

    override suspend fun write(text: String) = withContext(io) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
        Unit
    }
}
