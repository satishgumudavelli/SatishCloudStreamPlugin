package com.vegaprovider.providers

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.vegaprovider.core.VegaBase
import com.vegaprovider.extractors.MirrorResolver
import org.json.JSONObject
import org.jsoup.nodes.Element

/** MoviesDrive (urls.json key "drive"). Selectors captured live 2026-11-13. */
class MoviesDrive : VegaBase("drive", "https://new3.moviesdrive.cyou") {
    override var name = "MoviesDrive (Vega)"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "" to "Latest",
        "category/anime" to "Anime",
        "category/netflix" to "Netflix",
        "category/2160p-4k" to "4K",
    )

    private val h get() = mapOf("User-Agent" to UA)
    private fun clean(t: String) = t.replace("Download", "").trim()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        resolveHost()
        val path = if (request.data.isEmpty()) "" else "${request.data}/"
        val doc = app.get("${mainUrl.trimEnd('/')}/$path" + "page/$page/", headers = h).document
        val items = doc.select("#moviesGridMain > a").mapNotNull { a: Element ->
            val href = a.attr("href").ifBlank { return@mapNotNull null }
            val title = clean(a.selectFirst(".poster-title")?.text() ?: return@mapNotNull null)
            newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = a.selectFirst("img")?.attr("src") }
        }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        resolveHost()
        val base = mainUrl.trimEnd('/')
        val hits = JSONObject(app.get("$base/search.php?q=$query&page=1", headers = h).text).optJSONArray("hits")
            ?: return emptyList()
        return (0 until hits.length()).mapNotNull { i ->
            val d = hits.getJSONObject(i).optJSONObject("document") ?: return@mapNotNull null
            newMovieSearchResponse(clean(d.optString("post_title")), base + d.optString("permalink"), TvType.Movie) {
                posterUrl = d.optString("post_thumbnail").ifBlank { null }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        resolveHost()
        val doc = app.get(url, headers = h).document
        val title = clean(doc.selectFirst("h1.post-title")?.text() ?: return null)
        val poster = doc.selectFirst("main.page-body img[src*=tmdb]")?.attr("src")
        val plot = doc.select("h3").firstOrNull { it.text().contains("Storyline", true) }?.nextElementSibling()?.text()
        // download buttons: <h5><a href=mdrive.lol/archive/N>label</a></h5>; zips are unplayable, skipped
        val links = doc.select("main.page-body a[href*=/archive/]")
            .filter { !it.text().contains("zip", true) }
            .map { it.text().trim() to it.attr("href") }.distinctBy { it.second }
        val isSeries = Regex("season\\s*\\d", RegexOption.IGNORE_CASE).containsMatchIn(title) ||
            links.any { it.first.contains("Single Episode", true) }

        if (!isSeries) return newMovieLoadResponse(title, url, TvType.Movie, links.joinToString("\n") { "${it.first}|${it.second}" }) {
            posterUrl = poster; this.plot = plot
        }
        val season = Regex("Season\\s*(\\d+)", RegexOption.IGNORE_CASE).find(title)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        // ponytail: episode list comes from the first quality's archive page only; other qualities not offered per-episode.
        val first = links.firstOrNull() ?: return null
        val doc2 = app.get(first.second, headers = h).document
        val episodes = ArrayList<Episode>()
        var epName = ""; var n = 0
        doc2.select("h5").forEach { e ->
            val a = e.selectFirst("a[href]")
            if (a == null) { if (Regex("^EP\\s*\\d+", RegexOption.IGNORE_CASE).containsMatchIn(e.text())) { epName = e.text().trim(); n++ } }
            else if (epName.isNotEmpty() && a.attr("href").contains("hubcloud", true) && episodes.none { it.name == epName }) {
                val hrefs = e.parent()?.select("a[href]")?.map { it.attr("href") }.orEmpty().joinToString("\n") { "${first.first}|$it" }
                episodes.add(newEpisode(hrefs) { this.season = season; episode = n; name = epName })
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
                if (link.contains("/archive/")) {
                    // archive page lists hubcloud + only-apis; only hubcloud is resolvable (only-apis skipped, unverified)
                    app.get(link, headers = h).document.select("a[href*=hubcloud], a[href*=gdflix]")
                        .map { it.attr("href") }.distinct().forEach { MirrorResolver.resolve(it, label, subtitleCallback, cb) }
                } else MirrorResolver.resolve(link, label, subtitleCallback, cb)
            }
        }
        return any
    }
}
