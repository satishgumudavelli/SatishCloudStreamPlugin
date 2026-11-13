package com.vegaprovider.providers

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.vegaprovider.core.VegaBase
import com.vegaprovider.extractors.MirrorResolver
import com.vegaprovider.extractors.ZeeDlResolver
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/** zeefliz (urls.json key "zeefliz"). WordPress + nexdrive.store link pages; selectors captured live 2026-11-13. */
class Zeefliz : VegaBase("zeefliz", "https://zeefliz1.xyz") {
    override var name = "Zeefliz (Vega)"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "" to "Trending",
        "category/web-series" to "Shows",
        "category/movies" to "Movies",
    )

    private suspend fun doc(url: String) = app.get(url, headers = mapOf("User-Agent" to UA)).document

    private fun clean(t: String) = t.replace(Regex("^\\s*Download\\s*", RegexOption.IGNORE_CASE), "").trim()

    private fun Element.toCard(): SearchResponse? {
        val a = selectFirst("h3.entry-title a") ?: return null
        val img = selectFirst("img")
        return newMovieSearchResponse(clean(a.text()), a.attr("href"), TvType.Movie) {
            posterUrl = img?.attr("data-src")?.ifBlank { null } ?: img?.attr("src")
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        resolveHost()
        val path = if (request.data.isEmpty()) "" else "/${request.data}"
        val d = doc("$mainUrl$path" + if (page > 1) "/page/$page/" else "/")
        return newHomePageResponse(request.name, d.select("section.site-main article.post").mapNotNull { it.toCard() })
    }

    override suspend fun search(query: String): List<SearchResponse> {
        resolveHost()
        return doc("$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}").select("section.site-main article.post").mapNotNull { it.toCard() }
    }

    /** (heading text, nexdrive url) pairs in page order; buttons sit in the <p> after each h3/h4/h5. */
    private fun blocks(d: Document): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var heading = ""
        d.select("h3, h4, h5, a[href*=nexdrive]").forEach { el ->
            if (el.tagName() == "a") {
                if (heading.isNotBlank() && out.none { it.second == el.attr("href") }) out.add(heading to el.attr("href"))
            } else heading = el.text().trim()
        }
        return out
    }

    override suspend fun load(url: String): LoadResponse? {
        resolveHost()
        val d = doc(url)
        val title = clean(d.selectFirst(".single-service-contents h3")?.text() ?: return null)
        val img = d.selectFirst(".single-service-contents img")
        val poster = img?.attr("data-src")?.ifBlank { null } ?: img?.attr("src")
        val plot = d.select("h4").firstOrNull { it.text().contains("synopsis", true) }?.nextElementSibling()?.text()
        val bl = blocks(d)
        val seasonRe = Regex("Season\\s*(\\d+)|\\bS(\\d{2})\\b", RegexOption.IGNORE_CASE)
        val isSeries = seasonRe.containsMatchIn(title) || Regex("web\\s*series", RegexOption.IGNORE_CASE).containsMatchIn(title)

        if (!isSeries) {
            val data = bl.joinToString("\n") { "${it.first}|${it.second}" }
            return newMovieLoadResponse(title, url, TvType.Movie, data) { posterUrl = poster; this.plot = plot }
        }
        // ponytail: one nexdrive page (first listed quality) per season supplies the episodes; "Episodes: 17 - 20" range packs skipped.
        val episodes = ArrayList<Episode>()
        bl.distinctBy { seasonRe.find(it.first)?.groupValues?.drop(1)?.firstOrNull { g -> g.isNotEmpty() } }
            .forEachIndexed { idx, (heading, nex) ->
                val season = seasonRe.find(heading)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toIntOrNull() ?: (idx + 1)
                val nd = runCatching { doc(nex) }.getOrNull() ?: return@forEachIndexed
                nd.select("h4").forEach { h ->
                    val ep = Regex("Episodes?:\\s*(\\d+)\\s*:?-?\\s*$").find(h.text().trim())?.groupValues?.get(1)?.toIntOrNull()
                        ?: return@forEach
                    val links = h.nextElementSibling()?.select("a[href]")?.joinToString("\n") { "$heading|${it.attr("href")}" } ?: return@forEach
                    episodes.add(newEpisode(links) { this.season = season; episode = ep; name = "Episode $ep" })
                }
            }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) { posterUrl = poster; this.plot = plot }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var any = false
        val cb: (ExtractorLink) -> Unit = { any = true; callback(it) }
        suspend fun hop(label: String, link: String) {
            when {
                link.contains("zee-dl") -> ZeeDlResolver.resolve(link, label, cb)
                // zcloud.lat sits behind a Cloudflare challenge and vegadrive isn't ported: skipped
                link.contains("zcloud") || link.contains("vegadrive") -> {}
                else -> MirrorResolver.resolve(link, label, subtitleCallback, cb)
            }
        }
        for (line in data.lines().filter { it.isNotBlank() }) {
            val (label, link) = line.split("|", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            runCatching {
                if (link.contains("nexdrive")) {
                    doc(link).select("a[href*=zee-dl], a[href*=filebee], a[href*=filepress], a[href*=hubcloud], a[href*=vcloud], a[href*=gdflix]")
                        .map { it.attr("href") }.distinct().forEach { runCatching { hop(label, it) } }
                } else hop(label, link)
            }
        }
        return any
    }
}
