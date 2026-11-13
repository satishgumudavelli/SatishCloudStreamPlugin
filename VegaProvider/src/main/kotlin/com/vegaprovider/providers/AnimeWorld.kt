package com.vegaprovider.providers

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.vegaprovider.core.VegaBase
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.net.URLEncoder

/** animeworld (urls.json key "animeworld"). WordPress/DooPlay site; players are play.zephyrix.org HLS; captured live 2026-11-13. */
class AnimeWorld : VegaBase("animeworld", "https://watchanimeworld.one") {
    override var name = "AnimeWorld (Vega)"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    override val mainPage = mainPageOf(
        "series" to "Series",
        "movies" to "Movies",
        "category/hindi" to "Hindi",
        "category/anime" to "Anime",
    )

    private suspend fun get(url: String) = app.get(url, headers = mapOf("User-Agent" to UA, "Referer" to "$mainUrl/"))

    private fun Document.cards() = select("li:has(a.lnk-blk)").mapNotNull { c ->
        val href = c.selectFirst("a.lnk-blk")?.attr("href")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val title = c.selectFirst(".entry-title")?.text()?.trim().takeUnless { it.isNullOrBlank() } ?: return@mapNotNull null
        val img = c.selectFirst("img")?.let { it.attr("data-src").ifBlank { it.attr("src") } }?.let { if (it.startsWith("//")) "https:$it" else it }
        newAnimeSearchResponse(title, href, if ("/movies/" in href) TvType.AnimeMovie else TvType.Anime) { posterUrl = img }
    }.distinctBy { it.url }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        resolveHost()
        return newHomePageResponse(request.name, get("$mainUrl/${request.data}/" + if (page > 1) "page/$page/" else "").document.cards())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        resolveHost()
        return get("$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}").document.cards()
    }

    override suspend fun load(url: String): LoadResponse? {
        resolveHost()
        val doc = get(url).document
        val title = doc.selectFirst("h1.entry-title")?.text()?.trim() ?: return null
        val poster = doc.selectFirst("figure img")?.attr("src")?.let { if (it.startsWith("//")) "https:$it" else it }
        val plot = doc.selectFirst(".description")?.text()
        if ("/movies/" in url) return newMovieLoadResponse(title, url, TvType.AnimeMovie, url) { posterUrl = poster; this.plot = plot }
        val post = doc.selectFirst(".choose-season a[data-post]")?.attr("data-post")
        val seasons = doc.select(".choose-season a[data-season]").mapNotNull { it.attr("data-season").toIntOrNull() }.ifEmpty { listOf(0) }
        val eps = seasons.flatMap { s ->
            // season list is a public admin-ajax call made by the site's own frontend; s==0 means the page already shows the episodes
            val d = if (s == 0 || post == null) doc else runCatching {
                app.post("$mainUrl/wp-admin/admin-ajax.php", headers = mapOf("User-Agent" to UA, "Referer" to url, "X-Requested-With" to "XMLHttpRequest"),
                    data = mapOf("action" to "action_select_season", "post" to post, "season" to s.toString())).document
            }.getOrNull() ?: return@flatMap emptyList()
            d.select("article.episodes").mapNotNull { a ->
                val href = a.selectFirst("a.lnk-blk")?.attr("href") ?: return@mapNotNull null
                val m = Regex("(\\d+)x(\\d+)").find(a.selectFirst(".num-epi")?.text().orEmpty()) ?: return@mapNotNull null
                newEpisode(href) {
                    season = m.groupValues[1].toInt(); episode = m.groupValues[2].toInt()
                    name = a.selectFirst(".entry-title")?.text()
                    posterUrl = a.selectFirst("img")?.attr("src")
                }
            }
        }.distinctBy { it.data }
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = poster; this.plot = plot
            addEpisodes(DubStatus.Subbed, eps)
        }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // ponytail: only play.zephyrix.org (HLS, signed link) is ported; the dub-player / watchanimeworld.top players need cookie handshakes + decryption and are skipped.
        val frames = get(data).document.select("iframe").map { it.attr("data-src").ifBlank { it.attr("src") } }.filter { "zephyrix.org/video/" in it }.distinct()
        var any = false
        for (f in frames) {
            val id = f.substringAfter("/video/").substringBefore('?').trim('/')
            val base = Regex("^https?://[^/]+").find(f)?.value ?: continue
            val j = runCatching {
                JSONObject(app.post("$base/player/index.php?data=$id&do=getVideo",
                    headers = mapOf("User-Agent" to UA, "Origin" to base, "Referer" to f, "X-Requested-With" to "XMLHttpRequest"),
                    data = mapOf("hash" to id, "r" to base)).text)
            }.getOrNull() ?: continue
            val u = j.optString("securedLink").ifBlank { j.optString("videoSource") }
            if (u.isBlank()) continue
            callback(newExtractorLink("ZephyrFlick", "ZephyrFlick", u, ExtractorLinkType.M3U8) {
                referer = base
                headers = mapOf("User-Agent" to UA, "Origin" to base, "Referer" to base)
            })
            any = true
        }
        return any
    }
}
