package com.vegaprovider.providers

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.vegaprovider.core.VegaBase
import com.vegaprovider.extractors.DriveSeedResolver
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** TopMovies / MoviesLeech (urls.json key "Topmovies"). Chain captured live 2026-11-13:
 *  post -> leechpro.blog/archives/N -> en.thenaukriadda.in/?sid= -> driveseed -> r2.dev .mkv (DriveSeedResolver). */
class Topmovies : VegaBase("Topmovies", "https://moviesleech.media") {
    override var name = "Topmovies (Vega)"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "" to "Latest",
        "web-series" to "Web Series",
        "movies" to "Movies",
        "movies/bollywood" to "Bollywood",
    )

    private val h get() = mapOf("User-Agent" to UA)
    private fun clean(t: String) = t.replace("Download", "").replace(Regex("\\s+"), " ").trim()

    private fun cards(doc: Document) = doc.select("article.latestPost").mapNotNull { a: Element ->
        val link = a.selectFirst("h2.title a") ?: return@mapNotNull null
        val t = clean(link.text())
        newMovieSearchResponse(t, link.attr("href"), if (t.contains("Season", true)) TvType.TvSeries else TvType.Movie) {
            posterUrl = a.selectFirst("img")?.attr("src")
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        resolveHost()
        val path = if (request.data.isEmpty()) "" else "${request.data}/"
        return newHomePageResponse(request.name, cards(app.get("$mainUrl/$path" + "page/$page/", headers = h).document))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        resolveHost()
        return cards(app.get("$mainUrl/search/${query.trim().replace(" ", "+")}/", headers = h).document)
    }

    override suspend fun load(url: String): LoadResponse? {
        resolveHost()
        val doc = app.get(url, headers = h).document
        val title = clean(doc.selectFirst("h1.entry-title")?.text() ?: return null)
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.select("h2").firstOrNull { it.text().contains("Storyline", true) }?.nextElementSibling()?.text()
        // (heading, leechpro url): each h3 is followed by a <p> holding the maxbutton (download-links for movies, g-drive for series)
        val blocks = doc.select("h3").mapNotNull { hd ->
            val a = hd.nextElementSibling()?.selectFirst("a.maxbutton-download-links, a.maxbutton-g-drive") ?: return@mapNotNull null
            clean(hd.text()) to a.attr("href")
        }
        val seasonRe = Regex("Season\\s*(\\d+)", RegexOption.IGNORE_CASE)
        if (doc.selectFirst("a.maxbutton-g-drive") == null) return newMovieLoadResponse(title, url, TvType.Movie, blocks.joinToString("\n") { "${it.first}|${it.second}" }) {
            posterUrl = poster; this.plot = plot
        }
        // ponytail: first quality block supplies the episode list (one season per post); other qualities and Batch/Zip not offered.
        val season = seasonRe.find(title)?.groupValues?.get(1)?.toInt() ?: 1
        val (heading, page) = blocks.firstOrNull() ?: return null
        val episodes = app.get(page, headers = h).document.select("h3 a[href*=thenaukriadda]").mapIndexed { i, a ->
            newEpisode("$heading|${a.attr("href")}") { this.season = season; episode = i + 1; name = a.text().trim() }
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) { posterUrl = poster; this.plot = plot }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var any = false
        val cb: (ExtractorLink) -> Unit = { any = true; callback(it) }
        for (line in data.lines().filter { it.contains("|") }) {
            val (label, link) = line.split("|", limit = 2)
            runCatching {
                if (link.contains("leechpro")) {
                    app.get(link, headers = h).document.select("a[href*=thenaukriadda]").map { it.attr("href") }.distinct()
                        .forEach { runCatching { DriveSeedResolver.resolve(it, label, subtitleCallback, cb) } }
                } else DriveSeedResolver.resolve(link, label, subtitleCallback, cb)
            }
        }
        return any
    }
}
