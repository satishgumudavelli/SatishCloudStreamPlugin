package com.vegaprovider.providers

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.vegaprovider.core.VegaBase
import com.vegaprovider.extractors.GreenMotorsDecoder
import com.vegaprovider.extractors.MirrorResolver
import org.jsoup.nodes.Element
import java.net.URLEncoder

/** 4khdhub (urls.json key "4khdhub"). Selectors captured live 2026-11-13. */
class FourKHdHub : VegaBase("4khdhub", "https://4khdhub.one") {
    override var name = "4KHDHub (Vega)"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "" to "Home", "category/series" to "TV Shows", "category/anime" to "Anime", "category/2160p-HDR" to "4K HDR",
    )

    private val h get() = mapOf("User-Agent" to UA)

    private fun Element.toCard(): SearchResponse? {
        val href = attr("href").ifBlank { return null }.let { if (it.startsWith("http")) it else mainUrl + it }
        val title = selectFirst(".movie-card-title")?.text()?.trim()?.ifBlank { null } ?: return null
        val poster = selectFirst("img")?.attr("src")
        val series = select(".movie-card-format").any { it.text() == "Series" }
        return if (series) newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = poster }
        else newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        resolveHost()
        val base = if (request.data.isEmpty()) mainUrl else "$mainUrl/${request.data}"
        val doc = app.get(if (page == 1) "$base/" else "$base/page/$page/", headers = h).document
        return newHomePageResponse(request.name, doc.select(".card-grid a.movie-card").mapNotNull { it.toCard() })
    }

    override suspend fun search(query: String): List<SearchResponse> {
        resolveHost()
        return app.get("$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}", headers = h).document
            .select(".card-grid a.movie-card").mapNotNull { it.toCard() }
    }

    /** "label|url" lines for the HubCloud buttons under [scope]. HubDrive buttons skipped (extra hop, not verified). */
    private fun hubcloudLines(scope: Element, label: String) = scope.select("a.btn[href]")
        .filter { it.text().contains("HubCloud", true) }
        .joinToString("\n") { "${label.replace("|", "/")}|${it.attr("href")}" }

    override suspend fun load(url: String): LoadResponse? {
        resolveHost()
        val doc = app.get(url, headers = h).document
        val title = doc.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")
        val year = Regex("""\((\d{4})\)""").find(doc.title())?.groupValues?.get(1)?.toIntOrNull()
        val seasons = doc.select(".season-item")
        if (seasons.isEmpty()) {
            val data = doc.select(".download-item").joinToString("\n") { item ->
                hubcloudLines(item, item.selectFirst(".download-header .flex-1")?.ownText()?.trim().orEmpty()
                    .ifBlank { item.selectFirst(".file-title")?.text().orEmpty() })
            }.lines().filter { it.isNotBlank() }.joinToString("\n").ifBlank { return null }
            return newMovieLoadResponse(title, url, TvType.Movie, data) { posterUrl = poster; this.plot = plot; this.year = year }
        }
        val eps = linkedMapOf<Pair<Int, Int>, MutableList<String>>()
        seasons.forEach { s ->
            val season = Regex("""\d+""").find(s.selectFirst(".episode-number")?.text().orEmpty())?.value?.toIntOrNull() ?: 1
            val q = s.selectFirst(".episode-title")?.text()?.trim().orEmpty()
            s.select(".episode-download-item").forEach { e ->
                val n = Regex("""\d+""").find(e.selectFirst(".badge-psa")?.text().orEmpty())?.value?.toIntOrNull() ?: return@forEach
                val l = hubcloudLines(e, q)
                if (l.isNotBlank()) eps.getOrPut(season to n) { mutableListOf() }.add(l)
            }
        }
        if (eps.isEmpty()) return null
        val episodes = eps.entries.sortedWith(compareBy({ it.key.first }, { it.key.second })).map { (k, v) ->
            newEpisode(v.joinToString("\n")) { name = "Episode ${k.second}"; season = k.first; episode = k.second }
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) { posterUrl = poster; this.plot = plot; this.year = year }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var any = false
        val cb: (ExtractorLink) -> Unit = { any = true; callback(it) }
        for (l in data.lines().filter { it.contains("|") }) {
            runCatching {
                val real = GreenMotorsDecoder.decode(app.get(l.substringAfter("|"), headers = h).text) ?: return@runCatching
                MirrorResolver.resolve(real, l.substringBefore("|"), subtitleCallback, cb)
            }
        }
        return any
    }
}
