package com.vegaprovider.providers

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.vegaprovider.core.VegaBase
import com.vegaprovider.extractors.MirrorResolver
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * kmmovies (urls.json key "kmmovies"). Selectors captured live 2026-11-13.
 * urls.json key "cinevood" points at this same site, so no separate provider.
 */
class KmMovies : VegaBase("kmmovies", "https://kmmovies.beer") {
    override var name = "KMMovies"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "" to "Latest", "category/movies" to "Bollywood", "category/dual-audio" to "Dual Audio", "category/tv-series" to "Series",
    )

    private val h get() = mapOf("User-Agent" to UA)

    private fun Element.toCard(): SearchResponse? {
        val href = selectFirst("a[href]")?.attr("href")?.ifBlank { null } ?: return null
        val title = selectFirst(".movie-title")?.text()?.trim()?.ifBlank { null } ?: return null
        val poster = selectFirst("img")?.attr("src")
        val series = selectFirst(".meta-row")?.text()?.contains("Series", true) == true
        return if (series) newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = poster }
        else newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        resolveHost()
        val base = if (request.data.isEmpty()) mainUrl else "$mainUrl/${request.data}"
        val doc = app.get(if (page == 1) "$base/" else "$base/page/$page/", headers = h).document
        return newHomePageResponse(request.name, doc.select("article.movie-card").mapNotNull { it.toCard() },
            hasNext = doc.selectFirst("a.next.page-numbers") != null)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        resolveHost()
        return app.get("$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}", headers = h).document
            .select("article.movie-card").mapNotNull { it.toCard() }
    }

    // data lines: "label|url"
    private fun Element.links() = select("a.dl-btn[href]").joinToString("\n") {
        "${it.text().trim().replace("|", "/")}|${it.attr("href")}"
    }

    override suspend fun load(url: String): LoadResponse? {
        resolveHost()
        val doc = app.get(url, headers = h).document
        val title = doc.selectFirst("h1.hero-title")?.text()?.trim() ?: return null
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")
        val year = Regex("""\((\d{4})\)""").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val seasons = doc.select(".season-block")
        if (seasons.isEmpty()) {
            val data = doc.links().ifBlank { return null }
            return newMovieLoadResponse(title, url, TvType.Movie, data) { posterUrl = poster; this.plot = plot; this.year = year }
        }
        // ponytail: site only lists combined season packs per quality (no per-episode links), so one "episode" per season.
        val eps = seasons.mapIndexedNotNull { i, b ->
            val data = b.links().ifBlank { return@mapIndexedNotNull null }
            val label = b.selectFirst(".season-block-title")?.text()?.trim() ?: "Season ${i + 1}"
            val s = Regex("""(?i)season\s*(\d+)""").find(label)?.groupValues?.get(1)?.toIntOrNull() ?: (i + 1)
            newEpisode(data) { name = label; season = s; episode = 1 }
        }
        if (eps.isEmpty()) return null
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) { posterUrl = poster; this.plot = plot; this.year = year }
    }

    // kmcloud: /s/<id> page -> /d/<id>/<provider> -> 302 -> __myd_bridge -> 302 -> real host (verified for pixeldrain, gofile)
    private suspend fun kmcloud(page: String, label: String, sub: (SubtitleFile) -> Unit, cb: (ExtractorLink) -> Unit) {
        val doc = app.get(page, headers = h).document
        for (p in listOf("pixeldrain", "gofile")) {
            val d = doc.selectFirst("a[data-provider=$p]")?.attr("href") ?: continue
            val bridge = app.get(d, headers = h, allowRedirects = false).headers["location"] ?: continue
            val real = app.get(bridge, headers = h, allowRedirects = false).headers["location"] ?: continue
            MirrorResolver.resolve(real, label, sub, cb)
        }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var any = false
        val cb: (ExtractorLink) -> Unit = { any = true; callback(it) }
        for (l in data.lines().filter { it.contains("|") }) {
            val label = l.substringBefore("|")
            val link = l.substringAfter("|")
            runCatching {
                if (link.contains("kmcloud")) kmcloud(link, label, subtitleCallback, cb)
                else app.get(link, headers = h).document.select("a[href]").map { it.attr("href") }
                    .filter { it.contains("gofile.io") || it.contains("pixeldrain") || it.contains("hubcloud") }
                    .distinct().forEach { MirrorResolver.resolve(it, label, subtitleCallback, cb) } // skydrop/buzzheavier/vikingfile skipped: not verifiable
            }
        }
        return any
    }
}
