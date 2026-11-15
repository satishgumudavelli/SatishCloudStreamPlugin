package com.vegaprovider.providers

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.vegaprovider.core.LinkStreamer
import com.vegaprovider.core.VegaBase
import com.vegaprovider.extractors.MirrorResolver
import com.vegaprovider.extractors.PlaydriveResolver
import org.json.JSONObject
import org.jsoup.nodes.Element

/** luxmovies / rogmovies (urls.json key "lux"). Vegamovies clone; selectors captured live 2026-11-13. */
class LuxMovies : VegaBase("lux", "https://rogmovies.cyou") {
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

    private val playRe = Regex("""/e/[A-Za-z0-9_-]+/s-(\d+)/ep-(\d+)""")
    private val epHeadRe = Regex("""-:\s*Episode:?\s*(\d+)\s*:-""", RegexOption.IGNORE_CASE)

    /** Episode numbers listed on a nexdrive page ("-:Episode: N:-" headings). */
    private suspend fun nexEpisodes(nexUrl: String): List<Int> =
        runCatching { app.get(nexUrl, headers = h).document.select("h3, h4, h5").mapNotNull { epHeadRe.find(it.text())?.groupValues?.get(1)?.toIntOrNull() } }
            .getOrDefault(emptyList()).distinct()

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

        // Series: the WATCH ONLINE playdrive embed lists every episode (/e/<id>/s-SS/ep-NN); the nexdrive pages hold per-episode
        // download links ("-:Episode: N:-"). Episode list = the embed's (fallback: the first nexdrive page); each episode carries its
        // watch url plus one "NEX|N|quality|nexdrive url" per quality block, resolved only when played so opening the series stays fast.
        val embed = doc.selectFirst("article.post-content a[href*=playdrive]")?.attr("href")
        val origin = embed?.substringBefore("/e/")
        val watch = embed?.let { runCatching { app.get(it, headers = h, referer = "$mainUrl/").text }.getOrNull() }
            ?.let { t -> playRe.findAll(t).map { Triple(it.value, it.groupValues[1].toInt(), it.groupValues[2].toInt()) }.distinctBy { it.first }.toList() }
            .orEmpty()
        val qualityBlocks = bl.map { (heading, nex) -> (Regex("(\\d{3,4})p").find(heading)?.value ?: heading.take(20)) to nex }
        val season = watch.firstOrNull()?.second
            ?: Regex("""Season\s*(\d+)""", RegexOption.IGNORE_CASE).find(bl.firstOrNull()?.first ?: title)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val epNums = if (watch.isNotEmpty()) watch.map { it.third }.distinct().sorted()
            else qualityBlocks.firstOrNull()?.let { nexEpisodes(it.second) }.orEmpty()
        if (epNums.isEmpty()) return null
        val episodes = epNums.map { n ->
            val lines = ArrayList<String>()
            watch.firstOrNull { it.third == n }?.let { lines.add("Watch|$origin${it.first}") }
            qualityBlocks.forEach { (q, nex) -> lines.add("NEX|$n|$q|$nex") }
            newEpisode(lines.joinToString("\n")) { this.season = season; episode = n; name = "Episode $n" }
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) { posterUrl = poster; this.plot = plot }
    }

    private val hostSel = "a[href*=vcloud], a[href*=hubcloud], a[href*=fastdl], a[href*=filebee], a[href*=filepress]"

    /** Links under the "-:Episode: N:-" heading of a nexdrive page, in document order. */
    private suspend fun episodeLinks(nexUrl: String, n: Int): List<String> {
        val out = ArrayList<String>()
        var cur = -1
        app.get(nexUrl, headers = h).document.select("h3, h4, h5, $hostSel").forEach { el ->
            if (el.tagName().startsWith("h")) cur = epHeadRe.find(el.text())?.groupValues?.get(1)?.toIntOrNull() ?: -1
            else if (cur == n) out.add(el.attr("href"))
        }
        return out.distinct()
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ): Boolean = LinkStreamer.stream(data.lines().filter { it.isNotBlank() }, callback) { line, cb ->
        when {
            line.startsWith("Watch|") -> PlaydriveResolver.resolve(line.substringAfter("|"), "Watch", "$mainUrl/", cb)
            line.startsWith("NEX|") -> {
                val (n, q, nex) = line.removePrefix("NEX|").split("|", limit = 3)
                // vegadrive mirrors are not resolved (unverified); vcloud/fastdl/filebee are
                episodeLinks(nex, n.toInt()).forEach { MirrorResolver.resolve(it, q, subtitleCallback, cb) }
            }
            else -> {
                // movie posts: "heading|nexdrive url"
                val (label, link) = line.split("|", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                if (link.contains("nexdrive")) app.get(link, headers = h).document.select(hostSel).map { it.attr("href") }.distinct()
                    .forEach { MirrorResolver.resolve(it, label, subtitleCallback, cb) }
                else MirrorResolver.resolve(link, label, subtitleCallback, cb)
            }
        }
    }
}
