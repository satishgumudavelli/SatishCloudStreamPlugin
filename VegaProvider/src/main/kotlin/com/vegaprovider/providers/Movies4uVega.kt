package com.vegaprovider.providers

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.vegaprovider.core.VegaBase
import com.vegaprovider.extractors.MirrorResolver
import org.jsoup.nodes.Element
import java.net.URLEncoder

/** movies4u (urls.json key "movies4u"). Selectors captured live 2026-11-13; logic copied from Movies4uAG. */
class Movies4uVega : VegaBase("movies4u", "https://movies4u.tc") {
    override var name = "Movies4u (Vega)"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "" to "Latest", "bollywood" to "Bollywood", "hollywood" to "Hollywood", "dual-audio" to "Dual Audio",
        "south-indian" to "South Indian", "tv-show" to "TV Show", "web-series" to "Web Series",
    )

    private val h get() = mapOf("User-Agent" to UA)
    private val noise = Regex("""(?i)\[|\b\d+p\b|\b4k\b|HDTC|HDTS|HDRip|BluRay|WEB-DL|Full Movie""")
    private fun clean(raw: String): String {
        val cut = noise.find(raw)?.range?.first ?: return raw
        return raw.substring(0, cut).trim(' ', '-', '|', ':').ifBlank { raw }
    }

    private fun Element.toCard(): SearchResponse? {
        val a = selectFirst("h2.entry-title a") ?: return null
        val title = clean(a.text().trim())
        val poster = selectFirst("img")?.attr("src")
        return if (classNames().contains("category-web-series")) newTvSeriesSearchResponse(title, a.attr("href"), TvType.TvSeries) { posterUrl = poster }
        else newMovieSearchResponse(title, a.attr("href"), TvType.Movie) { posterUrl = poster }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        resolveHost()
        val base = if (request.data.isEmpty()) mainUrl else "$mainUrl/category/${request.data}"
        val doc = app.get(if (page == 1) "$base/" else "$base/page/$page/", headers = h).document
        return newHomePageResponse(request.name, doc.select(".entry-card.card-content").mapNotNull { it.toCard() },
            hasNext = doc.selectFirst("a.next.page-numbers") != null)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        resolveHost()
        return app.get("$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}", headers = h).document
            .select(".entry-card.card-content").mapNotNull { it.toCard() }
    }

    // data lines: "quality|url|direct" (direct=1 -> url is already a mirror, 0 -> mdrive page to expand)
    private fun line(q: String, u: String, direct: Boolean) = "${q.replace("|", "/")}|$u|${if (direct) 1 else 0}"

    override suspend fun load(url: String): LoadResponse? {
        resolveHost()
        val doc = app.get(url, headers = h).document
        val title = clean(doc.selectFirst("meta[property=og:title]")?.attr("content")?.ifBlank { null }
            ?: doc.selectFirst("h1.entry-title")?.text() ?: return null)
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.selectFirst(".entry-content p")?.text()?.trim()?.ifBlank { null }
        val year = Regex("""(19|20)\d{2}""").find(title)?.value?.toIntOrNull()
        val post = doc.selectFirst(".post.type-post")
        val qualityLinks = post?.select("a[href*=mdrive.cloud/mdisk]")?.map { a ->
            (a.closest("p, div.downloads-btns-div")?.previousElementSibling()?.takeIf { it.tagName() == "h4" || it.tagName() == "h3" }
                ?.text()?.trim()?.ifBlank { null } ?: title) to a.attr("href")
        }?.distinctBy { it.second }.orEmpty()
        if (qualityLinks.isEmpty()) return null

        val isSeries = post?.classNames()?.contains("category-web-series") == true ||
            Regex("""(?i)Season:|Episode:|SHOW Name:""").containsMatchIn(doc.selectFirst(".entry-content")?.text().orEmpty())
        if (!isSeries) {
            return newMovieLoadResponse(title, url, TvType.Movie, qualityLinks.joinToString("\n") { line(it.first, it.second, false) }) {
                posterUrl = poster; this.plot = plot; this.year = year
            }
        }
        // ponytail: whole-season zip tiers skipped; episodes keyed by (season, episode) from each mdrive page's "Episodes: N" headings.
        val eps = linkedMapOf<Pair<Int, Int>, MutableList<String>>()
        qualityLinks.filterNot { it.first.contains("zip", true) || it.first.contains("batch", true) }.forEach { (q, md) ->
            val season = Regex("""(?i)season\s*(\d+)""").find(q)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val c = runCatching { app.get(md, headers = h).document }.getOrNull()?.selectFirst("#container-content-single") ?: return@forEach
            var found = false
            c.select("h3, h4, h5").forEach { hd ->
                val n = Regex("""(?i)episodes?[:\s]*(\d+)""").find(hd.text())?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach
                val ms = hd.nextElementSibling()?.select("a[href^=http]") ?: return@forEach
                found = true
                ms.forEach { eps.getOrPut(season to n) { mutableListOf() }.add(line(q, it.attr("href"), true)) }
            }
            if (!found) c.select("a[href^=http]").forEach { eps.getOrPut(season to 1) { mutableListOf() }.add(line(q, it.attr("href"), true)) }
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
        for (l in data.lines().filter { it.isNotBlank() }) {
            val p = l.split("|")
            if (p.size < 3) continue
            runCatching {
                if (p[2] == "1") MirrorResolver.resolve(p[1], p[0], subtitleCallback, cb)
                else app.get(p[1], headers = h).document.select("#container-content-single a[href^=http]")
                    .map { it.attr("href") }.distinct().forEach { MirrorResolver.resolve(it, p[0], subtitleCallback, cb) }
            }
        }
        return any
    }
}
