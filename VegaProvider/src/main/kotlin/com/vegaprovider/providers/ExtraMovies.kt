package com.vegaprovider.providers

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.vegaprovider.core.VegaBase
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * ExtraMovies (urls.json key "extra"), extramovies.sarl (vegamovies-style theme). Menu/category links, cards, search and
 * detail captured live 2026-11-13. Download buttons all point at mobilejsr.com/view/<id>, a link protector that needs its
 * "Unlock Links" form POSTed first; that step is deliberately not automated, so loadLinks yields nothing yet.
 */
class ExtraMovies : VegaBase("extra", "https://extramovies.sarl") {
    override var name = "ExtraMovies (Vega)"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.NSFW)

    // Site menu: the top bar (Bollywood, Hindi Dubbed, Hollywood, Dual Audio, Web Series, 18+ Adult) maps onto these category pages.
    override val mainPage = mainPageOf(
        "" to "Latest",
        "category/hindi-movies" to "Bollywood (Hindi Movies)",
        "category/hindi-dubbed-movies" to "Hindi Dubbed Movies",
        "category/english-movies" to "Hollywood (English Movies)",
        "category/dual-audio-movies" to "Dual Audio Movies",
        "category/web-series" to "Web Series",
        "category/tv-shows" to "TV Shows",
        "category/south-movies" to "South Movies",
        "category/tamil-movies" to "Tamil Movies",
        "category/telegu-movies" to "Telegu Movies",
        "category/malayalam-movies" to "Malayalam Movies",
        "category/kannada-movies" to "Kannada Movies",
        "category/bengali-movies" to "Bengali Movies",
        "category/gujarati-movies" to "Gujarati Movies",
        "category/marathi-movies" to "Marathi Movies",
        "category/punjabi-movies" to "Punjabi Movies",
        "category/chinese-movies" to "Chinese Movies",
        "category/tagalog-movies" to "Tagalog Movies",
        "category/18-adult" to "18+ Adult",
        "category/hot-short-film" to "Hot Short Film",
        "category/hot-web-series" to "Hot Web Series",
        "category/uncategorized" to "Uncategorized",
    )

    private suspend fun get(url: String) = app.get(url, headers = mapOf("User-Agent" to UA))

    private fun Element.toCard(type: TvType): SearchResponse? {
        val href = attr("href").ifBlank { return null }
        val title = selectFirst(".poster-title")?.text()?.trim()?.ifBlank { null } ?: return null
        return newMovieSearchResponse(title, href, type) { posterUrl = selectFirst("img")?.attr("src") }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        resolveHost()
        val path = if (request.data.isEmpty()) "" else "/${request.data}"
        val type = if (request.data.contains("18-adult") || request.data.contains("hot-")) TvType.NSFW else TvType.Movie
        val items = get("$mainUrl$path" + if (page > 1) "/page/$page/" else "/").document
            .select("a:has(.poster-card)").mapNotNull { it.toCard(type) }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        resolveHost()
        return get("$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}").document
            .select("a:has(.poster-card)").mapNotNull { it.toCard(TvType.Movie) }
    }

    override suspend fun load(url: String): LoadResponse? {
        resolveHost()
        val doc = get(url).document
        val title = doc.selectFirst("h1.post-title .material-text, h1.post-title")?.text()?.trim()?.ifBlank { null } ?: return null
        // ponytail: series posts are shown as one entry; each quality button (label|mobilejsr url) is carried in data.
        val links = doc.select("a[href*=mobilejsr]").joinToString("\n") { "${it.text().trim()}|${it.attr("href")}" }
        return newMovieLoadResponse(title, url, TvType.Movie, links) {
            posterUrl = doc.selectFirst("article img[src*=blogger], .post-body img[src*=blogger]")?.attr("src")
        }
    }

    // ponytail: mobilejsr links need the link protector's "Unlock Links" POST; not automated, so nothing is emitted yet.
    override suspend fun loadLinks(
        data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ): Boolean = false
}
