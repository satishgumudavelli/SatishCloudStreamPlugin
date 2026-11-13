package com.vegaprovider.providers

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.vegaprovider.core.VegaBase
import com.vegaprovider.extractors.MirrorResolver
import org.json.JSONObject
import org.jsoup.nodes.Element

/** vegamovies (urls.json key "Vega"). Selectors captured live 2026-11-13. */
class Vega : VegaBase("Vega", "https://vegamovies.lease") {
    override var name = "Vega"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "" to "New",
        "web-series/netflix" to "Netflix",
        "web-series/amazon-prime-video" to "Amazon Prime",
        "web-series/apple-tv" to "Apple TV+",
        "category/movies-by-genres/action" to "Action",
        "category/movies-by-genres/comedy" to "Comedy",
        "category/movies-by-genres/horror" to "Horror",
        "category/movies-by-genres/sci-fi" to "Sci-Fi",
    )

    private fun clean(t: String) = t.replace("Download", "").trim()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        resolveHost()
        val path = if (request.data.isEmpty()) "" else "${request.data}/"
        val doc = app.get("$mainUrl/$path" + "page/$page/", headers = mapOf("User-Agent" to UA)).document
        val items = doc.select("#moviesGridMain > a, .movies-grid > a").mapNotNull { it.toCard() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toCard(): SearchResponse? {
        val href = attr("href").ifBlank { return null }
        val img = selectFirst("img")
        val title = clean(selectFirst(".poster-title")?.text()?.takeIf { it.isNotBlank() } ?: img?.attr("alt") ?: return null)
        val poster = img?.attr("data-src")?.ifBlank { null } ?: img?.attr("src")
        return newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        resolveHost()
        val json = JSONObject(app.get("$mainUrl/search.php?q=$query&page=1", headers = mapOf("User-Agent" to UA)).text)
        val hits = json.optJSONArray("hits") ?: return emptyList()
        return (0 until hits.length()).mapNotNull { i ->
            val d = hits.getJSONObject(i).optJSONObject("document") ?: return@mapNotNull null
            newMovieSearchResponse(clean(d.optString("post_title")), mainUrl + d.optString("permalink"), TvType.Movie) {
                posterUrl = d.optString("post_thumbnail").ifBlank { null }
            }
        }
    }

    /** (heading text, nexdrive url) pairs in page order. Headings are h3/h4/h5, buttons sit in the next <p>. */
    private fun qualityBlocks(doc: org.jsoup.nodes.Document): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var heading = ""
        doc.select(".entry-content, .post-content, main").first()?.select("h3, h4, h5, a[href*=nexdrive]")?.forEach { el ->
            if (el.tagName() == "a") {
                val h = heading
                if (h.isNotBlank() && out.none { it.second == el.attr("href") }) out.add(h to el.attr("href"))
            } else heading = el.text().trim()
        }
        return out
    }

    override suspend fun load(url: String): LoadResponse? {
        resolveHost()
        val doc = app.get(url, headers = mapOf("User-Agent" to UA)).document
        val title = clean(doc.selectFirst("h1.post-title")?.text() ?: return null)
        val poster = doc.selectFirst(".entry-content img[data-src], .entry-content img[src*=tmdb]")
            ?.let { it.attr("data-src").ifBlank { it.attr("src") } }
        val plot = doc.select("h3").firstOrNull { it.text().contains("Plot", true) }?.nextElementSibling()?.text()
        val blocks = qualityBlocks(doc)
        val isSeries = Regex("season\\s*\\d|S\\d{2}E\\d{2}|web\\s*series", RegexOption.IGNORE_CASE).containsMatchIn(title) ||
            blocks.any { it.first.contains("Season", true) }

        if (!isSeries) {
            val data = blocks.joinToString("\n") { "${it.first}|${it.second}" }
            return newMovieLoadResponse(title, url, TvType.Movie, data) {
                posterUrl = poster; this.plot = plot
            }
        }
        // ponytail: one nexdrive page (first quality) per season supplies the episode list; other qualities not offered per-episode.
        val episodes = ArrayList<Episode>()
        blocks.filter { it.first.contains("Season", true) }
            .distinctBy { Regex("Season\\s*(\\d+)", RegexOption.IGNORE_CASE).find(it.first)?.groupValues?.get(1) }
            .forEach { (heading, nex) ->
                val season = Regex("Season\\s*(\\d+)", RegexOption.IGNORE_CASE).find(heading)?.groupValues?.get(1)?.toIntOrNull()
                val links = runCatching { app.get(nex, headers = mapOf("User-Agent" to UA)).document }.getOrNull()
                    ?.select("a[href*=vcloud]")?.map { it.attr("href") }?.distinct().orEmpty()
                links.forEachIndexed { i, link ->
                    episodes.add(newEpisode("$heading|$link") { this.season = season; episode = i + 1; name = "Episode ${i + 1}" })
                }
            }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = poster; this.plot = plot
        }
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
                    // vegadrive mirrors skipped: not ported/verified (Constitution II)
                    app.get(link, headers = mapOf("User-Agent" to UA)).document
                        .select("a[href*=vcloud], a[href*=fastdl], a[href*=filebee], a[href*=filepress]")
                        .map { it.attr("href") }.distinct()
                        .forEach { MirrorResolver.resolve(it, label, subtitleCallback, cb) }
                } else MirrorResolver.resolve(link, label, subtitleCallback, cb)
            }
        }
        return any
    }
}
