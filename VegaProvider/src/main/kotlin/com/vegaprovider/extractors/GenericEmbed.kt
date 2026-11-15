package com.vegaprovider.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * Fallback for a WATCH embed host that no built-in extractor knows: fetch the page and take any .m3u8/.mp4/.mkv url in its
 * markup, inline scripts, or standard packed (p.a.c.k.e.r) script. Sealed/JS-built players (playdrive, turboembed) expose none, so they yield nothing here.
 * ponytail: candidates are not checked here; LinkVerifier drops dead/junk ones afterwards.
 */
object GenericEmbed {
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private val media = Regex("""https?://[^\s"'<>\\)]+?\.(?:m3u8|mp4|mkv)[^\s"'<>\\)]*""", RegexOption.IGNORE_CASE)

    suspend fun resolve(embedUrl: String, label: String, referer: String, callback: (ExtractorLink) -> Unit) {
        val raw = app.get(embedUrl, headers = mapOf("User-Agent" to UA), referer = referer).text
        // VidHide/EarnVids-style pages (hdstream4u.com/file/<id>) hide the playlist in a public p.a.c.k.e.r script: unpack it too
        val html = (raw + "\n" + runCatching { getAndUnpack(raw) }.getOrDefault("")).replace("\\/", "/")
        media.findAll(html).map { it.value }.distinct().take(3).forEach { u ->
            val type = if (u.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            callback(newExtractorLink("Embed", "Embed $label", u, type) { this.referer = embedUrl })
        }
    }
}
