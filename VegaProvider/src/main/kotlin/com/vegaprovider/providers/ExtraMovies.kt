package com.vegaprovider.providers

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.vegaprovider.core.VegaBase
import com.vegaprovider.extractors.MirrorResolver
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * ExtraMovies (urls.json key "extra"), extramovies.miami (WP "gridshow" theme). Menu, cards, search, movie and series
 * posts captured live 2026-11-13. The site's "[18+] Movies" button leaves for extramovies18.pro (401), so it is not listed.
 * Posts with only elinks.fun buttons (a parked domain-for-sale page) are skipped: nothing resolvable there.
 */
class ExtraMovies : VegaBase("extra", "https://extramovies.miami") {
    override var name = "ExtraMovies (Vega)"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // The site's own menu: Bollywood, Hollywood, South (Hindi Dubbed), Dual Audio, Web Series, Korean Drama.
    override val mainPage = mainPageOf(
        "" to "Latest",
        "category/bollywood-movies" to "Bollywood",
        "category/hollywood-movies" to "Hollywood",
        "category/south-movies" to "South (Hindi Dubbed)",
        "category/dual-audio-movies" to "Dual Audio",
        "category/web-series" to "Web Series",
        "category/korean-drama" to "Korean Drama",
    )

    private val deadHosts = listOf("elinks.fun")
    private val tvRegex = Regex("""season|\bS\d{1,2}\b|S\d{1,2}E\d|episode|web[ -]?series""", RegexOption.IGNORE_CASE)
    private val seasonRegex = Regex("""(?:season|\bS)\s*[-–]?\s*0*(\d{1,2})""", RegexOption.IGNORE_CASE)

    private suspend fun get(url: String) = app.get(url, headers = mapOf("User-Agent" to UA))

    private fun clean(t: String) = t.replace("Download", "", ignoreCase = true).trim(' ', '-', '–')

    private fun Element.toCard(): SearchResponse? {
        val a = selectFirst(".gridshow-grid-post-title a") ?: return null
        val title = clean(a.text())
        val type = if (tvRegex.containsMatchIn(title)) TvType.TvSeries else TvType.Movie
        return newMovieSearchResponse(title, a.attr("href"), type) { posterUrl = selectFirst("img.wp-post-image")?.attr("src") }
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

    /** (heading text, button url) pairs in page order; a button belongs to the nearest heading above it. */
    private fun buttons(doc: org.jsoup.nodes.Document): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var heading = ""
        doc.select("h2, h3, h4, a.maxbutton-episode").forEach { el ->
            if (el.tagName() == "a") {
                val href = el.attr("href")
                if (deadHosts.none { href.contains(it) } && out.none { it.second == href }) out.add(heading to href)
            } else el.text().trim().takeIf { it.isNotBlank() }?.let { heading = it }
        }
        return out
    }

    override suspend fun load(url: String): LoadResponse? {
        resolveHost()
        val doc = get(url).document
        val title = clean(doc.selectFirst("h1.post-title")?.text() ?: return null)
        val links = buttons(doc)
        if (links.isEmpty()) return null
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")
        val isTv = tvRegex.containsMatchIn(title) || links.any { tvRegex.containsMatchIn(it.first) }

        if (!isTv) {
            return newMovieLoadResponse(title, url, TvType.Movie, links.joinToString("\n") { "${it.first}|${it.second}" }) {
                posterUrl = poster; this.plot = plot
            }
        }
        // ponytail: the site only ships season packs (one file per quality, e.g. "Season 1 COMPLETE 720p"), not per-episode
        // links, so each season is listed as one entry carrying all its qualities.
        val episodes = links.groupBy { seasonRegex.find(it.first)?.groupValues?.get(1)?.toIntOrNull() ?: 1 }
            .toSortedMap().map { (season, group) ->
                newEpisode(group.joinToString("\n") { "${it.first}|${it.second}" }) {
                    this.season = season; episode = 1; name = "Season $season (full season, choose quality)"
                }
            }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) { posterUrl = poster; this.plot = plot }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var any = false
        data.lines().filter { it.isNotBlank() }.forEach { line ->
            val (label, url) = line.split("|", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            runCatching { MirrorResolver.resolve(url, label, subtitleCallback) { any = true; callback(it) } }
        }
        return any
    }
}
