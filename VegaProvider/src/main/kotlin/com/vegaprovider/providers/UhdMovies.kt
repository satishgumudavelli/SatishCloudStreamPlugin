package com.vegaprovider.providers

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.vegaprovider.core.VegaBase
import com.vegaprovider.extractors.DriveSeedResolver
import org.jsoup.nodes.Element

/** UHDMovies (urls.json key "UhdMovies"). Selectors/chain captured live 2026-11-13. */
class UhdMovies : VegaBase("UhdMovies", "https://uhdmovies.beauty") {
    override var name = "UhdMovies"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "" to "Latest",
        "web-series" to "Web Series",
        "movies" to "Movies",
        "4k-hdr" to "4K HDR",
        "2160p-hevc" to "4K HEVC",
    )

    private val h get() = mapOf("User-Agent" to UA)
    private fun clean(t: String) = t.replace("Download", "").trim()

    private fun cards(doc: org.jsoup.nodes.Document) = doc.select("article.gridlove-post").mapNotNull { a: Element ->
        val link = a.selectFirst(".entry-image a[href]") ?: return@mapNotNull null
        val title = clean(link.attr("title").ifBlank { a.selectFirst("h2 a")?.text() ?: return@mapNotNull null })
        newMovieSearchResponse(title, link.attr("href"), TvType.Movie) { posterUrl = a.selectFirst("img")?.attr("src") }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        resolveHost()
        val path = if (request.data.isEmpty()) "" else "${request.data}/"
        return newHomePageResponse(request.name, cards(app.get("$mainUrl/$path" + "page/$page/", headers = h).document))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        resolveHost()
        return cards(app.get("$mainUrl/search/$query/", headers = h).document)
    }

    /** (heading, shortlink, button text) in page order: <p>file/size text</p><p><a class=maxbutton href=thenaukriadda>. */
    private fun buttons(doc: org.jsoup.nodes.Document): List<Triple<String, String, String>> {
        val out = ArrayList<Triple<String, String, String>>()
        var heading = ""
        doc.selectFirst(".entry-content")?.select("p, h3, h4, h5")?.forEach { el ->
            val btns = el.select("a.maxbutton[href*=thenaukriadda]")
            if (btns.isEmpty()) {
                val t = el.text().replace(Regex("\\s+"), " ").trim()
                if (t.length > 15) heading = t
            } else btns.forEach { out.add(Triple(heading, it.attr("href"), it.text().trim())) }
        }
        return out
    }

    override suspend fun load(url: String): LoadResponse? {
        resolveHost()
        val doc = app.get(url, headers = h).document
        val title = clean(doc.selectFirst("h1.entry-title")?.text() ?: return null)
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val bl = buttons(doc).filter { !it.third.contains("zip", true) && !it.third.contains("pack", true) }
        val isSeries = bl.any { it.third.startsWith("Episode", true) }

        if (!isSeries) return newMovieLoadResponse(title, url, TvType.Movie, bl.joinToString("\n") { "${it.first}|${it.second}" }) {
            posterUrl = poster
        }
        // ponytail: first quality block per season supplies the episode list; other qualities not offered per-episode.
        val episodes = ArrayList<Episode>()
        val seen = HashSet<Int>()
        var curSeason = 0
        var curHeading = ""
        var n = 0
        bl.forEach { (heading, link, text) ->
            if (heading != curHeading) {
                curHeading = heading; n = 0
                curSeason = Regex("\\bS(\\d{1,2})\\b").find(heading)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                if (!seen.add(curSeason)) curSeason = -curSeason  // later qualities of a season: skipped
            }
            n++
            if (curSeason > 0) episodes.add(newEpisode("$heading|$link") { season = curSeason; episode = n; name = text })
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) { posterUrl = poster }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var any = false
        val cb: (ExtractorLink) -> Unit = { any = true; callback(it) }
        for (line in data.lines().filter { it.isNotBlank() }) {
            val (label, link) = line.split("|", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            runCatching { DriveSeedResolver.resolve(link, label, subtitleCallback, cb) }
        }
        return any
    }
}
