package com.vegaprovider.providers

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.vegaprovider.core.VegaBase
import com.vegaprovider.extractors.MirrorResolver
import org.jsoup.nodes.Element

/** skymovieshd (urls.json key "skymovieshd"). Old-school non-WordPress site; selectors captured live 2026-11-13.
 *  Hop: /movie/X.html -> howblogs.xyz/<id> (plain list of hosts) -> hubcloud.pw / gdflix.vip / gofile. */
class SkyMovieHD : VegaBase("skymovieshd", "https://new4.skymovieshd.clinic") {
    override var name = "SkyMoviesHD (Vega)"
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "" to "Latest",
        "category/Bollywood-Movies.html" to "Bollywood",
        "category/South-Indian-Hindi-Dubbed-Movies.html" to "South Hindi Dubbed",
        "category/Hollywood-Hindi-Dubbed-Movies.html" to "Hollywood Hindi Dubbed",
        "category/Hollywood-English-Movies.html" to "Hollywood English",
        "category/TV-Serial-Episodes.html" to "TV Serial Episodes",
    )

    private val hosts = Regex("hubcloud|vcloud|gdflix|gdlink|gofile\\.io|pixeldrain")

    private suspend fun doc(url: String) = app.get(url, headers = mapOf("User-Agent" to UA)).document

    private fun Element.toCard(): SearchResponse? {
        val a = selectFirst("a[href*=movie/]") ?: return null
        val href = a.absUrl("href").ifBlank { return null }
        val title = a.text().trim().ifBlank { return null }
        // home "popular" cards carry a poster; list rows only have an icon
        val poster = a.selectFirst("img")?.attr("src")?.takeIf { it.startsWith("http") }
        return newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
    }

    private fun cards(d: org.jsoup.nodes.Document) = d.select("div.Let, div.Fmvideo, div.L").mapNotNull { it.toCard() }.distinctBy { it.url }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        resolveHost()
        // ponytail: site only pages the home feed via /index.php?page; categories/home return page 1 only
        if (page > 1) return null
        val url = if (request.data.isEmpty()) "$mainUrl/" else "$mainUrl/${request.data}"
        return newHomePageResponse(request.name, cards(doc(url)))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        resolveHost()
        return cards(doc("$mainUrl/search.php?search=${java.net.URLEncoder.encode(query, "UTF-8")}&cat=All"))
    }

    override suspend fun load(url: String): LoadResponse? {
        resolveHost()
        val d = doc(url)
        val title = d.selectFirst("title")?.text()?.replace("Full Movie Download", "")?.trim()?.ifBlank { null } ?: return null
        val poster = d.select("img[src^=http]").firstOrNull { !it.attr("src").contains("imageflix") }?.attr("src")
        val links = d.select("a[href*=howblogs]").map { it.text().trim() to it.attr("href") }
            .filter { it.second.isNotBlank() }.distinctBy { it.second }
        if (links.isEmpty()) return null
        return newMovieLoadResponse(title, url, TvType.Movie, links.joinToString("\n") { "${it.first}|${it.second}" }) {
            posterUrl = poster
        }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var any = false
        val cb: (ExtractorLink) -> Unit = { any = true; callback(it) }
        // ponytail: howblogs also lists filezz/buzzheavier/megaup/etc.; only hosts MirrorResolver can resolve are followed
        val quality = Regex("\\d{3,4}p").find(data)?.value ?: ""
        for (line in data.lines().filter { it.isNotBlank() }) {
            val link = line.substringAfter("|")
            runCatching {
                doc(link).select("a[href]").map { it.attr("href") }.filter { hosts.containsMatchIn(it) }.distinct()
                    .forEach { runCatching { MirrorResolver.resolve(it, quality, subtitleCallback, cb) } }
            }
        }
        return any
    }
}
