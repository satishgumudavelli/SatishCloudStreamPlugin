package com.vegaprovider.providers

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.vegaprovider.core.VegaBase
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** dramacool (urls.json key "dramacool"; "asianc" is the same site). Selectors/stream hop captured live 2026-11-13. */
class DramaCool : VegaBase("dramacool", "https://asianc.id") {
    override var name = "DramaCool"
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.Movie)

    override val mainPage = mainPageOf(
        "recently-added" to "Recently Added",
        "most-popular-drama" to "Most Popular",
    )

    private suspend fun get(url: String, ref: String = mainUrl) = app.get(url, headers = mapOf("User-Agent" to UA, "Referer" to "$ref/"))

    private val epRe = Regex("^/(.+)-episode-\\d+\\.html$")

    // home cards link to episode pages (/slug-episode-N.html); search/popular link to /drama-detail/slug. Normalise to the latter.
    private fun Document.cards() = select("ul.list-episode-item > li").mapNotNull { c ->
        val a = c.selectFirst("a") ?: return@mapNotNull null
        val href = a.attr("href")
        val slug = epRe.find(href)?.groupValues?.get(1)
        val link = if (slug != null) "$mainUrl/drama-detail/$slug" else if (href.startsWith("/drama-detail/")) fixUrl(href) else return@mapNotNull null
        val img = c.selectFirst("img")
        newTvSeriesSearchResponse(c.selectFirst("h3")?.text()?.trim() ?: return@mapNotNull null, link, TvType.AsianDrama) {
            posterUrl = img?.attr("data-original")?.ifBlank { null }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        resolveHost()
        val doc = get("$mainUrl/${request.data}" + if (page > 1) "?page=$page" else "").document
        return newHomePageResponse(request.name, doc.cards())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        resolveHost()
        return get("$mainUrl/search?type=drama&keyword=${URLEncoder.encode(query, "UTF-8")}").document.cards()
    }

    override suspend fun load(url: String): LoadResponse? {
        resolveHost()
        val doc = get(url).document
        val title = doc.selectFirst(".details .info h1")?.text()?.trim() ?: return null
        val episodes = doc.select("ul.all-episode > li > a").mapNotNull { a ->
            val href = a.attr("href").ifBlank { return@mapNotNull null }
            newEpisode(fixUrl(href)) {
                name = a.selectFirst("h3")?.text()
                episode = Regex("episode-(\\d+)").find(href)?.groupValues?.get(1)?.toIntOrNull()
            }
        }.reversed() // site lists newest first
        return newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes) {
            posterUrl = doc.selectFirst(".details .img img")?.attr("src")
            plot = doc.select(".details .info p").firstOrNull { it.text().startsWith("Description") }?.nextElementSibling()?.text()
        }
    }

    // vidbasic player payload: AES-256-CBC, key/iv are literals in the player's own public JS (crypto-js.js script tag data-value).
    private fun dec(b64: String): String? = runCatching {
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec("94588293375053432799222445521289".toByteArray(), "AES"), IvParameterSpec("5259228356829423".toByteArray()))
        String(c.doFinal(Base64.getMimeDecoder().decode(b64)))
    }.getOrNull()

    override suspend fun loadLinks(
        data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ): Boolean {
        resolveHost()
        val embed = get(data).document.selectFirst("iframe")?.attr("src")?.let { if (it.startsWith("//")) "https:$it" else it } ?: return false
        val player = get(embed, "https://vidbasic.top").document.selectFirst("li[data-video*=3rdplayer]")?.attr("data-video") ?: return false
        val crypto = get("https://vidbasic.top$player", embed.substringBeforeLast("/embed")).document
            .selectFirst("script[data-name=crypto]")?.attr("data-value") ?: return false
        val url = dec(crypto)?.takeIf { it.startsWith("http") } ?: return false
        callback(newExtractorLink("VidBasic", "VidBasic", url, ExtractorLinkType.M3U8) {
            referer = "https://vidbasic.top/"
            this.headers = mapOf("Origin" to "https://vidbasic.top")
        })
        // ponytail: subtitles (kk.vidbasic.top track, per-line encrypted VTT) not ported.
        return true
    }
}
