package com.vegaprovider.providers

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.vegaprovider.core.VegaBase
import com.vegaprovider.extractors.MirrorResolver
import org.jsoup.nodes.Element

/** gokuHD (urls.json key "gokuHD"): WordPress anime site. Selectors captured live 2026-11-13; chain: post -> nexdrive page -> fastdl/vcloud/filebee. */
class GokuHD : VegaBase("gokuHD", "https://gokuhd.nl") {
    override var name = "GokuHD (Vega)"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    override val mainPage = mainPageOf(
        "" to "Latest", "series" to "Series", "movies" to "Movies", "ongoing-series" to "Ongoing", "trending" to "Trending",
    )

    private val h get() = mapOf("User-Agent" to UA, "Referer" to "$mainUrl/")
    private val noise = Regex("""(?i)\s*(\(|\[|\||WEB-DL|Blu-Ray|Hindi Dubbed|Dual Audio).*$""")
    private fun clean(raw: String) = raw.replace(noise, "").trim().ifBlank { raw }
    private fun isMovie(t: String) = Regex("""(?i)anime movie|\(\d{4}\)\s*(web-dl|blu-ray|hdrip)""").containsMatchIn(t) && !t.contains("season", true)

    private fun Element.toCard(): SearchResponse? {
        val a = selectFirst("h2.entry-title a") ?: return null
        val raw = a.text().trim()
        return newAnimeSearchResponse(clean(raw), a.attr("href"), if (isMovie(raw)) TvType.AnimeMovie else TvType.Anime) {
            posterUrl = selectFirst("img")?.attr("src")
            addDubStatus(DubStatus.Dubbed)
        }
    }

    private suspend fun list(path: String, page: Int): List<SearchResponse> {
        val base = if (path.isEmpty()) mainUrl else "$mainUrl/$path"
        return app.get(if (page == 1) "$base/" else "$base/page/$page/", headers = h).document.select("article").mapNotNull { it.toCard() }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        resolveHost()
        return newHomePageResponse(request.name, list(request.data, page), hasNext = true)
    }

    // ponytail: site search (?s=, typesense API) is behind Cloudflare; filter the newest 5 pages of /series and /movies by title instead.
    override suspend fun search(query: String): List<SearchResponse> {
        resolveHost()
        val words = query.lowercase().split(" ").filter { it.isNotBlank() }
        return listOf("series", "movies").flatMap { p -> (1..5).flatMap { runCatching { list(p, it) }.getOrDefault(emptyList()) } }
            .distinctBy { it.url }.filter { r -> words.all { r.name.lowercase().contains(it) } }
    }

    // data lines: "quality|nexdrive url"
    private fun line(q: String, u: String) = "${q.replace("|", "/")}|$u"

    override suspend fun load(url: String): LoadResponse? {
        resolveHost()
        val doc = app.get(url, headers = h).document
        val raw = doc.selectFirst("h1.single-post-title")?.text() ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" | Gokuhd") ?: return null
        val title = clean(raw)
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.select("article.single-entry-body p").map { it.text().trim() }.firstOrNull { it.length > 40 && !it.contains("Searching Keywords") && !it.startsWith("Download") }
        val year = Regex("""(19|20)\d{2}""").find(raw)?.value?.toIntOrNull()
        // quality heading (h4) -> nexdrive buttons, batch/zip skipped
        val groups = doc.select("article.single-entry-body a[href*=nexdrive]").filterNot { it.text().contains("batch", true) || it.text().contains("zip", true) }
            .map { a ->
                val c = a.closest("p")
                (c?.previousElementSibling()?.takeIf { it.tagName() == "h4" }?.text()?.trim() ?: title) to a.attr("href")
            }.distinctBy { it.second }
        if (groups.isEmpty()) return null
        val type = if (isMovie(raw)) TvType.AnimeMovie else TvType.Anime
        if (type == TvType.AnimeMovie) {
            return newMovieLoadResponse(title, url, type, groups.joinToString("\n") { line(it.first, it.second) }) {
                posterUrl = poster; this.plot = plot; this.year = year
            }
        }
        val eps = sortedMapOf<Int, MutableList<String>>()
        groups.forEach { (q, nd) ->
            val c = runCatching { app.get(nd, headers = h).document }.getOrNull()?.selectFirst(".entry-inner") ?: return@forEach
            c.select("h4").forEach { hd ->
                val n = Regex("""(?i)episodes?[:\s]*(\d+)""").find(hd.text())?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach
                hd.nextElementSibling()?.select("a[href^=http]")?.forEach { eps.getOrPut(n) { mutableListOf() }.add(line(q, it.attr("href"))) }
            }
        }
        if (eps.isEmpty()) return null
        val episodes = eps.map { (n, v) -> newEpisode("RAW\n" + v.joinToString("\n")) { name = "Episode $n"; episode = n } }
        return newAnimeLoadResponse(title, url, type) {
            posterUrl = poster; this.plot = plot; this.year = year
            addEpisodes(DubStatus.Dubbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var any = false
        val cb: (ExtractorLink) -> Unit = { any = true; callback(it) }
        val raw = data.startsWith("RAW\n") // episode: lines are already mirror urls; movie: lines are nexdrive pages
        for (l in data.removePrefix("RAW\n").lines().filter { it.isNotBlank() }) {
            val p = l.split("|")
            if (p.size < 2) continue
            runCatching {
                if (raw) MirrorResolver.resolve(p[1], p[0], subtitleCallback, cb)
                else app.get(p[1], headers = h).document.select(".entry-inner a[href^=http]").map { it.attr("href") }.distinct()
                    .forEach { MirrorResolver.resolve(it, p[0], subtitleCallback, cb) }
            }
        }
        return any
    }
}
