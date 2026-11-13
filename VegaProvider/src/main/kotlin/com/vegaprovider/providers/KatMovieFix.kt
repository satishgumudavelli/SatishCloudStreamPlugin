package com.vegaprovider.providers

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.vegaprovider.core.VegaBase
import com.vegaprovider.extractors.MirrorResolver
import org.jsoup.nodes.Element

/** katmoviefix (urls.json key "katmoviefix"). Selectors captured live 2026-11-13. Hop: post -> katlinks.in/archives/N -> gdflix/filebee/gofile; some posts link hubcloud.pw directly. */
class KatMovieFix : VegaBase("katmoviefix", "https://katmoviefix.study") {
    override var name = "KatMovieFix (Vega)"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "" to "Latest",
        "category/netflix" to "Netflix",
        "category/anime" to "Anime",
        "category/hollywood-movies" to "Hollywood",
    )

    private val hosts = Regex("hubcloud|vcloud|gdflix|gdlink|filebee|filepress|gofile\\.io|pixeldrain")

    private suspend fun doc(url: String) = app.get(url, headers = mapOf("User-Agent" to UA)).document

    private fun Element.toCard(): SearchResponse? {
        val a = selectFirst(".post-thumb a, h2 a") ?: return null
        val href = a.attr("href").ifBlank { return null }
        val title = (selectFirst("h2 a") ?: a).let { it.attr("title").ifBlank { it.text() } }.trim()
        val poster = selectFirst("img[data-src]")?.attr("data-src")
        return newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
    }

    private fun cards(d: org.jsoup.nodes.Document) = d.select("li.post").mapNotNull { it.toCard() }.distinctBy { it.url }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        resolveHost()
        val path = if (request.data.isEmpty()) "" else "${request.data}/"
        return newHomePageResponse(request.name, cards(doc("$mainUrl/$path" + "page/$page/")))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        resolveHost()
        return cards(doc("$mainUrl/?s=$query"))
    }

    override suspend fun load(url: String): LoadResponse? {
        resolveHost()
        val d = doc(url)
        val title = d.selectFirst("h1.entry-title")?.text()?.trim() ?: return null
        val poster = d.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = d.select(".entry-content h3").firstOrNull { it.text().contains("Storyline", true) }?.nextElementSibling()?.text()
        // (heading, href) of every download button: h2 > a pointing at katlinks or a supported host directly
        val btns = d.select(".entry-content h2 a[href]").map { it.text().trim() to it.attr("href") }
            .filter { it.second.contains("katlinks") || hosts.containsMatchIn(it.second) }
        val single = btns.filter { it.first.contains("Single Episode", true) }
        if (single.isEmpty()) {
            val data = btns.joinToString("\n") { "${it.first}|${it.second}" }
            return newMovieLoadResponse(title, url, TvType.Movie, data) { posterUrl = poster; this.plot = plot }
        }
        val season = Regex("S(\\d{1,2})\\b|Season\\s*(\\d+)", RegexOption.IGNORE_CASE).find(title)
            ?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toIntOrNull() ?: 1
        // ep number -> "quality|gdflix" lines
        val eps = sortedMapOf<Int, MutableList<String>>()
        single.forEach { (label, href) ->
            val q = Regex("\\d{3,4}p").find(label)?.value ?: ""
            runCatching { doc(href) }.getOrNull()?.select(".entry-content a[href*=gdflix]")?.forEach { a ->
                val n = Regex("E(\\d+)", RegexOption.IGNORE_CASE).find(a.text())?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach
                eps.getOrPut(n) { ArrayList() }.add("$q|${a.attr("href")}")
            }
        }
        val episodes = eps.map { (n, lines) ->
            newEpisode(lines.joinToString("\n")) { this.season = season; episode = n; name = "Episode $n" }
        }
        // ponytail: season packs (.zip via GDFlix) not offered, only single-episode pages.
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) { posterUrl = poster; this.plot = plot }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var any = false
        val cb: (ExtractorLink) -> Unit = { any = true; callback(it) }
        for (line in data.lines().filter { it.isNotBlank() }) {
            val (label, link) = line.split("|", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            runCatching {
                if (link.contains("katlinks")) {
                    doc(link).select(".entry-content a[href]").map { it.attr("href") }.filter { hosts.containsMatchIn(it) }.distinct()
                        .forEach { runCatching { MirrorResolver.resolve(it, label, subtitleCallback, cb) } }
                } else MirrorResolver.resolve(link, label, subtitleCallback, cb)
            }
        }
        return any
    }
}
