package com.vegaprovider.providers

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.vegaprovider.core.VegaBase
import java.net.URLEncoder

/** moviezwap (urls.json key "moviezwap"). Old-school WAP markup, captured live 2026-11-13. TLS resets sometimes: retried once. */
class Moviezwap : VegaBase("moviezwap", "https://www.moviezwap.moe") {
    override var name = "Moviezwap (Vega)"
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "/category/Telugu-(2026)-Movies.html" to "Telugu Movies",
        "/category/Tamil-(2026)-Movies.html" to "Tamil Movies",
        "/category/Telugu-Dubbed-Hollywood-Movies-Complete-Set.html" to "Hollywood Telugu Dubbed",
        "/category/Telugu-Web-Series.html" to "Web Series",
    )

    private suspend fun get(path: String) = (if (path.startsWith("http")) path else mainUrl.trimEnd('/') + path).let { u ->
        runCatching { app.get(u, headers = mapOf("User-Agent" to UA)) }.getOrNull()
            ?: app.get(u, headers = mapOf("User-Agent" to UA))
    }

    private fun org.jsoup.nodes.Document.cards() = select("a[href^='/movie/']").mapNotNull { a ->
        val t = a.text().trim().ifBlank { return@mapNotNull null }
        newMovieSearchResponse(t, mainUrl.trimEnd('/') + a.attr("href"), TvType.Movie)
    }.distinctBy { it.url }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        resolveHost()
        // ponytail: category pages are a single list (no pagination seen); page 2+ returns nothing.
        if (page > 1) return null
        return newHomePageResponse(request.name, get(request.data).document.cards())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        resolveHost()
        return get("/search.php?q=${URLEncoder.encode(query, "UTF-8")}").document.cards()
    }

    override suspend fun load(url: String): LoadResponse? {
        resolveHost()
        val doc = get(url).document
        val title = doc.selectFirst("h2")?.text()?.trim() ?: return null
        val poster = doc.selectFirst("img[src^='/poster/']")?.attr("src")?.let { mainUrl.trimEnd('/') + it }
        // detail page lists /dwload.php?file=<id> per quality; the download.php page holds the real .mp4
        val data = doc.select("a[href*='dwload.php?file='], a[href*='download.php?file=']")
            .joinToString("\n") { it.attr("href").replace("dwload.php", "download.php") + "|" + it.text().trim() }
        return newMovieLoadResponse(title, url, TvType.Movie, data) { posterUrl = poster }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var any = false
        for (line in data.lines().filter { it.isNotBlank() }) {
            val (path, label) = line.split("|", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            runCatching {
                val href = get(path).document.select("a").firstOrNull {
                    it.text().contains("Fast Download Server") && it.attr("href").lowercase().contains(".mp4")
                }?.attr("href") ?: return@runCatching
                val q = Regex("(\\d{3,4})p").find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                callback(newExtractorLink("MoviezWap", "MoviezWap ${label.substringAfterLast('-').removeSuffix(".mp4")}", href.replace(" ", "%20"), ExtractorLinkType.VIDEO) { quality = q })
                any = true
            }
        }
        return any
    }
}
