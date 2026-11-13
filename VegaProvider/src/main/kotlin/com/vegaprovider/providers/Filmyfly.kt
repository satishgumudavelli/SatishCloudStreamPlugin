package com.vegaprovider.providers

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.vegaprovider.core.VegaBase
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.net.URLEncoder

/** FilmyFly (urls.json key "filmyfly"; filmyfly.net/.farm redirect to filmyfly.ong). Captured live 2026-11-13. */
class Filmyfly : VegaBase("filmyfly", "https://filmyfly.ong") {
    override var name = "Filmyfly (Vega)"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "" to "Home",
        "movie-category/42/Web-Series.html" to "Web Series",
        "movie-category/4/Hollywood-Hindi-Movies.html" to "Hollywood",
        "movie-category/21/South-Hindi-Dubbed-Movie.html" to "South Hindi Dubbed",
        "movie-category/1/Bollywood-Hindi-Movies.html" to "Bollywood",
    )

    private suspend fun get(url: String) = app.get(url, headers = mapOf("User-Agent" to UA))

    private fun typeOf(title: String) =
        if (Regex("Web Series|\\bS\\d\\d\\b", RegexOption.IGNORE_CASE).containsMatchIn(title)) TvType.TvSeries else TvType.Movie

    private fun Document.cards() = select("div.A10").mapNotNull { c ->
        val href = c.selectFirst("a.row-thumb-link")?.attr("href") ?: return@mapNotNull null
        val title = c.selectFirst(".row-title")?.text()?.trim() ?: return@mapNotNull null
        newMovieSearchResponse(title, fixUrl(href), typeOf(title)) { posterUrl = c.selectFirst("img")?.attr("src") }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        resolveHost()
        val doc = get("$mainUrl/${request.data}" + if (page > 1) "?page=$page" else "").document
        return newHomePageResponse(request.name, doc.cards())
    }

    // search.html is rendered client-side from a public JSON API; /movie/<id>/<anything>.html resolves by id.
    override suspend fun search(query: String): List<SearchResponse> {
        resolveHost()
        val json = JSONObject(get("https://webfind.filmyflydla.space/search?q=${URLEncoder.encode(query, "UTF-8")}&page=1&per_page=40").text)
        val arr = json.optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
            val title = o.getString("title")
            newMovieSearchResponse(title, "$mainUrl/movie/${o.getString("filmyfly")}/x.html", typeOf(title)) {
                posterUrl = o.optString("poster").ifBlank { null }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        resolveHost()
        val doc = get(url).document
        val title = doc.selectFirst(".fname:contains(Name) .colora")?.text()?.trim() ?: return null
        val poster = doc.selectFirst("img.movie-cover")?.attr("src")
        val plot = doc.selectFirst(".fname:contains(Description) .colorg")?.text()
        val tags = doc.selectFirst(".fname:contains(Genre) .colorb")?.text()?.split(",")?.map { it.trim() }
        val link = doc.selectFirst(".dlbtn a")?.attr("href") ?: return null
        if (typeOf(title) == TvType.Movie) {
            return newMovieLoadResponse(title, url, TvType.Movie, link) { posterUrl = poster; this.plot = plot; this.tags = tags }
        }
        // ponytail: site only offers whole-season pack mkvs, so a series is one "Complete" episode.
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOf(newEpisode(link) { name = "Complete season"; season = 1; episode = 1 })) {
            posterUrl = poster; this.plot = plot; this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var any = false
        // linkmake.in/view/<id> (no captcha) lists filesdl.in/cloud|drive/<id> buttons.
        val buttons = get(data).document.select(".dlink.dl a[href]")
        for (a in buttons) {
            val href = a.attr("href")
            // ponytail: /drive/ pages sit behind a JS cookie challenge (vDDoS) and are skipped; only /cloud/ pages (direct R2 mkv) are used.
            if (!href.contains("/cloud/")) continue
            val label = a.text().replace("Download", "").trim()
            runCatching {
                val r2 = get(href).document.selectFirst("a.button2[href*=r2.dev]")?.attr("href") ?: return@runCatching
                val q = Regex("(\\d{3,4})p").find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                callback(newExtractorLink("FilesDL", "FilesDL $label", r2, ExtractorLinkType.VIDEO) { quality = q })
                any = true
            }
        }
        return any
    }
}
