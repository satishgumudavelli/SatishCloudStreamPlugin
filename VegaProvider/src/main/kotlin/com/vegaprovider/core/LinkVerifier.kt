package com.vegaprovider.core

import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Drops links that are dead right now (410/403/404, an HTML error page instead of media, an HLS url that is not a playlist).
 * Reads response headers only (plus 7 bytes for HLS), so a server that ignores Range never streams the whole file.
 * ponytail: if every link fails the check they are all returned unchanged, so a false negative can never hide all sources.
 */
object LinkVerifier {
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private const val TIMEOUT_SEC = 10L

    private suspend fun isPlayable(l: ExtractorLink): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val rb = Request.Builder().url(l.url)
            l.headers.forEach { (k, v) -> rb.header(k, v) }
            if (l.referer.isNotBlank()) rb.header("Referer", l.referer)
            if (l.headers.keys.none { it.equals("user-agent", true) }) rb.header("User-Agent", UA)
            val hls = l.type == ExtractorLinkType.M3U8
            if (!hls) rb.header("Range", "bytes=0-1")
            app.baseClient.newBuilder().callTimeout(TIMEOUT_SEC, TimeUnit.SECONDS).build().newCall(rb.build()).execute().use { r ->
                when {
                    r.code !in 200..206 -> false
                    hls -> r.body?.source()?.let { it.request(7) && it.buffer.readUtf8(7) == "#EXTM3U" } ?: false
                    else -> !r.header("Content-Type").orEmpty().lowercase().startsWith("text/html")
                }
            }
        }.getOrDefault(false)
    }

    suspend fun verified(links: List<ExtractorLink>): List<ExtractorLink> =
        links.amap { if (isPlayable(it)) it else null }.filterNotNull().ifEmpty { links }
}
