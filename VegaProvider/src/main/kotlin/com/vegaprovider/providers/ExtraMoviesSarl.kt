package com.vegaprovider.providers

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.vegaprovider.core.VegaBase
import com.vegaprovider.extractors.MobileJsrResolver
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * extramovies.sarl (vegamovies-style theme; a different site from extramovies.miami). Menu categories, cards, search and
 * detail captured live 2026-11-13. Every quality button is a mobilejsr.com/view link, see MobileJsrResolver.
 */
class ExtraMoviesSarl : VegaBase("extrasarl", "https://extramovies.sarl") {
    override var name = "ExtraMovies Sarl (Vega)"
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

    private val tvRegex = Regex("""season|\bS\d{1,2}\b|S\d{1,2}E\d|episode|web[ -]?series|\bseries\b""", RegexOption.IGNORE_CASE)
    private val seasonRegex = Regex("""\bS(?:eason)?\s*0*(\d{1,2})""", RegexOption.IGNORE_CASE)
    private val episodeRegex = Regex("""(?<![A-Za-z])E(?:pisode)?\s*\[?0*(\d{1,3})""", RegexOption.IGNORE_CASE)

    private suspend fun get(url: String) = app.get(url, headers = mapOf("User-Agent" to UA))

    private fun clean(t: String) = t.replace("Download", "", ignoreCase = true).trim(' ', '-', '–')

    private fun Element.toCard(forceType: TvType?): SearchResponse? {
        val href = attr("href").ifBlank { return null }
        val title = selectFirst(".poster-title")?.text()?.trim()?.ifBlank { null } ?: return null
        val type = forceType ?: if (tvRegex.containsMatchIn(title)) TvType.TvSeries else TvType.Movie
        return newMovieSearchResponse(title, href, type) { posterUrl = selectFirst("img")?.attr("src") }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        resolveHost()
        val path = if (request.data.isEmpty()) "" else "/${request.data}"
        val nsfw = request.data.contains("18-adult") || request.data.contains("hot-")
        val items = get("$mainUrl$path" + if (page > 1) "/page/$page/" else "/").document
            .select("a:has(.poster-card)").mapNotNull { it.toCard(if (nsfw) TvType.NSFW else null) }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        resolveHost()
        return get("$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}").document
            .select("a:has(.poster-card)").mapNotNull { it.toCard(null) }
    }

    override suspend fun load(url: String): LoadResponse? {
        resolveHost()
        val doc = get(url).document
        val title = clean(doc.selectFirst("h1.post-title .material-text, h1.post-title")?.text()?.trim() ?: return null)
        // each quality button ("1080p [2.6GB]") is one mobilejsr link; the "Click to view Screenshoot" link is an image page
        val links = doc.select("a[href*=mobilejsr]")
            .filter { !it.text().contains("screenshot", true) && !it.text().contains("screenshoot", true) }
            .map { "${it.text().trim()}|${it.attr("href")}" }.distinct()
        if (links.isEmpty()) return null
        val poster = doc.selectFirst("article img[src*=blogger], .post-body img[src*=blogger]")?.attr("src")
        val data = links.joinToString("\n")

        if (!tvRegex.containsMatchIn(title)) {
            return newMovieLoadResponse(title, url, TvType.Movie, data) { posterUrl = poster }
        }
        // ponytail: a series post is a batch (e.g. "S01E37-40" or a full season) with one file per quality, not per-episode
        // links, so each post is one entry; season/first episode come from the title.
        val season = seasonRegex.find(title)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val first = episodeRegex.find(title)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val range = Regex("""S\d{1,2}E\[?(\d{1,3})\s*[-–]\s*(\d{1,3})""", RegexOption.IGNORE_CASE).find(title)
            ?.let { "Episodes ${it.groupValues[1]}-${it.groupValues[2]}" } ?: "Full season / batch"
        val ep = newEpisode(data) { this.season = season; episode = first; name = "Season $season - $range" }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOf(ep)) { posterUrl = poster }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var any = false
        data.lines().filter { it.isNotBlank() }.forEach { line ->
            val (label, url) = line.split("|", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            runCatching { MobileJsrResolver.resolve(url, label, subtitleCallback) { any = true; callback(it) } }
        }
        return any
    }
}
