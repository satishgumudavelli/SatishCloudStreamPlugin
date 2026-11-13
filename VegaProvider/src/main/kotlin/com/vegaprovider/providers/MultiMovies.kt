package com.vegaprovider.providers

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.vegaprovider.core.VegaBase
import org.jsoup.nodes.Document
import java.net.URLEncoder

/** multimovies (urls.json key "multi"). Site is now a TMDB-id embed aggregator; selectors captured live 2026-11-13. */
class MultiMovies : VegaBase("multi", "https://m1.multimovies.courses") {
    override var name = "MultiMovies"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "trending" to "Trending",
        "movies" to "Movies",
        "series" to "Series",
        "genre/k-drama" to "K-drama",
    )

    private suspend fun get(url: String) = app.get(url, headers = mapOf("User-Agent" to UA, "Referer" to mainUrl))

    private fun Document.cards() = select("article.poster-card").mapNotNull { c ->
        val a = c.selectFirst("a.poster-art") ?: return@mapNotNull null
        val href = a.attr("href")
        val title = (c.selectFirst(".poster-caption a")?.text() ?: a.attr("aria-label")).trim()
        val type = if (href.startsWith("/series/")) TvType.TvSeries else TvType.Movie
        newMovieSearchResponse(title, fixUrl(href), type) { posterUrl = c.selectFirst("img")?.attr("src") }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        resolveHost()
        val doc = get("$mainUrl/${request.data}" + if (page > 1) "?page=$page" else "").document
        return newHomePageResponse(request.name, doc.cards())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        resolveHost()
        return get("$mainUrl/search?q=${URLEncoder.encode(query, "UTF-8")}").document.cards()
    }

    // vidrift embed url embeds the TMDB id; the detail page's server chips carry it (movie: /embed/movie/<id>, tv: /embed/tv/<id>/1/1)
    private val idRe = Regex("embed\\.vidrift\\.net/embed/(movie|tv)/(\\d+)")

    override suspend fun load(url: String): LoadResponse? {
        resolveHost()
        val res = get(url)
        val doc = res.document
        val title = doc.selectFirst("h1.cinejoy-detail-title")?.text()?.trim() ?: return null
        val tmdb = idRe.find(res.text)?.groupValues?.get(2) ?: return null
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.selectFirst("#cinejoyOverview")?.text()
        if (url.contains("/movie/")) {
            return newMovieLoadResponse(title, url, TvType.Movie, "movie/$tmdb") { posterUrl = poster; this.plot = plot }
        }
        val seasons = doc.select("select.cinejoy-season-select option").mapNotNull { it.attr("value").toIntOrNull() }.ifEmpty { listOf(1) }
        val episodes = ArrayList<Episode>()
        for (s in seasons) {
            // season 1 is the page itself; others are the same page at /season/N/episode/1
            val d = if (s == 1) doc else runCatching { get("$url/season/$s/episode/1").document }.getOrNull() ?: continue
            d.select(".cinejoy-ep-card[id^=ep-item-$s-]").forEach { c ->
                val e = c.id().substringAfterLast('-').toIntOrNull() ?: return@forEach
                episodes.add(newEpisode("tv/$tmdb/$s/$e") {
                    season = s; episode = e; name = c.selectFirst(".cinejoy-ep-name")?.attr("title")
                })
            }
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) { posterUrl = poster; this.plot = plot }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // ponytail: only the vidrift (Orion, direct mp4 per audio track) server is ported; cineverse/iqst/vidbolt/filmu/nexus not verified.
        val html = runCatching {
            app.get("https://embed.vidrift.net/embed/$data", headers = mapOf("User-Agent" to UA, "Referer" to "https://multimovies.garden/")).text
        }.getOrNull() ?: return false
        var any = false
        Regex("\"url\":\"(https:[^\"]*?video\\.mp4)\"[^{}]*?\"name\":\"([^\"]*)\"").findAll(html).forEach { m ->
            val u = m.groupValues[1].replace("\\/", "/")
            val label = m.groupValues[2].replace("\\u00b7", "-").replace("·", "-")
            callback(newExtractorLink("Vidrift", "Vidrift $label", u, ExtractorLinkType.VIDEO) {
                referer = "https://embed.vidrift.net/"
            })
            any = true
        }
        return any
    }
}
