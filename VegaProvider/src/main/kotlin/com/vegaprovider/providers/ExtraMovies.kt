package com.vegaprovider.providers

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.vegaprovider.core.VegaBase
import com.vegaprovider.extractors.HubCloudExtractor
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * ExtraMovies (urls.json key "extra"). extramovies.miami (WP "gridshow" theme, hubcloud buttons inline) and extramovies.sarl
 * (vegamovies theme, links behind a mobilejsr.com shortener gate) are two different sites; only .miami is ported. Captured live 2026-11-13.
 */
class ExtraMovies : VegaBase("extra", "https://extramovies.miami") {
    override var name = "ExtraMovies (Vega)"
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "" to "Latest",
        "category/hollywood-movies" to "Hollywood",
        "category/south-movies" to "South Movies",
    )

    private suspend fun get(url: String) = app.get(url, headers = mapOf("User-Agent" to UA))

    private fun Element.toCard(): SearchResponse? {
        val a = selectFirst(".gridshow-grid-post-title a") ?: return null
        val title = a.text().trim()
        return newMovieSearchResponse(title, a.attr("href"), TvType.Movie) {
            posterUrl = selectFirst("img.wp-post-image")?.attr("src")
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        resolveHost()
        val path = if (request.data.isEmpty()) "" else "/${request.data}"
        val items = get("$mainUrl$path" + if (page > 1) "/page/$page/" else "/").document
            .select(".gridshow-grid-post").mapNotNull { it.toCard() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        resolveHost()
        return get("$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}").document
            .select(".gridshow-grid-post").mapNotNull { it.toCard() }
    }

    override suspend fun load(url: String): LoadResponse? {
        resolveHost()
        val doc = get(url).document
        val title = doc.selectFirst("h1.post-title")?.text()?.trim() ?: return null
        // ponytail: series posts (if any) are treated as movies; each quality block is one hubcloud button.
        val links = doc.select("a.maxbutton-episode[href*=hubcloud]").joinToString("\n") { it.attr("href") }
        if (links.isEmpty()) return null
        return newMovieLoadResponse(title, url, TvType.Movie, links) {
            posterUrl = doc.selectFirst("meta[property=og:image]")?.attr("content")
            plot = doc.selectFirst("meta[property=og:description]")?.attr("content")
        }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var any = false
        data.lines().filter { it.isNotBlank() }.forEach { href ->
            // hubcloud.pw/drive/<id> -> #download (gamerxyt hubcloud.php) -> r2 mkv / pixeldrain buttons, handled by HubCloudExtractor
            runCatching { HubCloudExtractor().getUrl(href, href, subtitleCallback) { any = true; callback(it) } }
        }
        return any
    }
}
