package com.vegaprovider.providers

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.vegaprovider.core.VegaBase
import com.vegaprovider.extractors.MirrorResolver
import org.json.JSONObject
import org.jsoup.nodes.Element

/** luxmovies / rogmovies (urls.json key "lux"). Vegamovies clone; selectors captured live 2026-11-13. */
class LuxMovies : VegaBase("lux", "https://rogmovies.autos") {
    override var name = "LuxMovies (Vega)"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "" to "New",
        "web-series/netflix" to "Netflix",
        "web-series/amazon-prime-video" to "Amazon Prime",
        "web-series/jiohotstar" to "JioHotstar",
        "movies-by-quality/2160p" to "4K Movies",
        "category/movies-by-genres/action" to "Action",
        "category/movies-by-genres/comedy" to "Comedy",
        "category/movies-by-genres/drama" to "Drama",
    )

    private val h get() = mapOf("User-Agent" to UA)
    private fun clean(t: String) = t.replace("Download", "").trim()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        resolveHost()
        val path = if (request.data.isEmpty()) "" else "${request.data}/"
        val doc = app.get("$mainUrl/$path" + "page/$page/", headers = h).document
        val items = doc.select("#moviesGridMain > a").mapNotNull { a: Element ->
            val href = a.attr("href").ifBlank { return@mapNotNull null }
            val img = a.selectFirst("img")
            val title = clean(a.selectFirst(".poster-title")?.text()?.takeIf { it.isNotBlank() } ?: img?.attr("alt") ?: return@mapNotNull null)
            newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = img?.attr("src") }
        }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        resolveHost()
        val hits = JSONObject(app.get("$mainUrl/search.php?q=$query&page=1", headers = h).text).optJSONArray("hits")
            ?: return emptyList()
        return (0 until hits.length()).mapNotNull { i ->
            val d = hits.getJSONObject(i).optJSONObject("document") ?: return@mapNotNull null
            newMovieSearchResponse(clean(d.optString("post_title")), mainUrl + d.optString("permalink"), TvType.Movie) {
                posterUrl = d.optString("post_thumbnail").ifBlank { null }
            }
        }
    }

    /** (heading, nexdrive url) pairs in page order: <h5>heading</h5><p><a href=nexdrive>. */
    private fun blocks(doc: org.jsoup.nodes.Document): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var heading = ""
        doc.select("article.post-content").first()?.select("h3, h4, h5, a[href*=nexdrive]")?.forEach { el ->
            if (el.tagName() == "a") {
                if (heading.isNotBlank() && out.none { it.second == el.attr("href") }) out.add(heading to el.attr("href"))
            } else heading = el.text().trim()
        }
        return out
    }

    override suspend fun load(url: String): LoadResponse? {
        resolveHost()
        val doc = app.get(url, headers = h).document
        val title = clean(doc.selectFirst("h1.post-title")?.text() ?: return null)
        val poster = doc.selectFirst("article.post-content img[src*=tmdb]")?.attr("src")
        val plot = doc.select("h4").firstOrNull { it.text().contains("PLOT", true) }?.nextElementSibling()?.text()
        val bl = blocks(doc)
        val isSeries = Regex("season\\s*\\d|web\\s*series", RegexOption.IGNORE_CASE).containsMatchIn(title)

        if (!isSeries) return newMovieLoadResponse(title, url, TvType.Movie, bl.joinToString("\n") { "${it.first}|${it.second}" }) {
            posterUrl = poster; this.plot = plot
        }
        // ponytail: each nexdrive link (episode/pack per quality) listed as its own "episode"; no per-episode split.
        val episodes = bl.mapIndexed { i, (heading, nex) ->
            newEpisode("$heading|$nex") {
                season = Regex("\\bS(\\d{1,2})\\b").find(heading)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                episode = i + 1; name = heading
            }
        }
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
                if (link.contains("nexdrive")) {
                    // vegadrive/fastdl.beer mirrors skipped: not verified (Constitution II)
                    app.get(link, headers = h).document
                        .select("a[href*=vcloud], a[href*=hubcloud], a[href*=filebee], a[href*=filepress]")
                        .map { it.attr("href") }.distinct().forEach { MirrorResolver.resolve(it, label, subtitleCallback, cb) }
                } else MirrorResolver.resolve(link, label, subtitleCallback, cb)
            }
        }
        return any
    }
}
