package com.vegaprovider.providers

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.vegaprovider.core.VegaBase
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

/** MovieLinkBD (urls.json keys "movielinkdb"/"movielinkbd"). Selectors captured live 2026-11-13. */
class MovieLinkDb : VegaBase("movielinkdb", "https://ex5wfv.movielinkbd.li") {
    override var name = "MovieLinkDb (Vega)"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "" to "Recently Updated",
        "type/movies" to "Movies",
        "type/series" to "Series",
        "language/bangla" to "Bangla",
        "language/indonesian" to "Indonesian",
    )

    private suspend fun get(url: String) = app.get(url, headers = mapOf("User-Agent" to UA))

    private fun Element.toCard(): SearchResponse? {
        val a = selectFirst("a.title[href]") ?: return null
        val href = fixUrl(a.attr("href"))
        val poster = selectFirst("img")?.attr("data-src")?.ifBlank { null }
        val type = if (href.contains("/series/")) TvType.TvSeries else TvType.Movie
        return newMovieSearchResponse(a.text().trim(), href, type) { posterUrl = poster }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        resolveHost()
        val path = if (request.data.isEmpty()) "" else "/${request.data}"
        val url = "$mainUrl$path" + if (page > 1) "/page/$page" else ""
        val items = get(url).document.select(".movie-card").mapNotNull { it.toCard() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        resolveHost()
        return get("$mainUrl/search?q=${URLEncoder.encode(query, "UTF-8")}").document
            .select(".movie-card").mapNotNull { it.toCard() }
    }

    /** Page JS (inline script on every detail page) hides each /file/ path in data-dl: base64, XOR with an LCG keystream seeded by data-ds, skipping data-dr rounds. */
    private fun decode(a: Element): String {
        val m = 0xFFFFFFFFL
        var x = a.attr("data-ds").toLong(16) and m
        repeat(a.attr("data-dr").toIntOrNull() ?: 0) { x = (x * 1103515245L + 12345L) and m }
        val sb = StringBuilder()
        for (b in Base64.decode(a.attr("data-dl"), Base64.DEFAULT)) {
            x = (x * 1103515245L + 12345L) and m
            sb.append(((b.toInt() and 0xFF) xor ((x shr 16) and 255).toInt()).toChar())
        }
        return sb.toString()
    }

    /** "label|/file/..." lines for every data-dl button under [root]. */
    private fun links(root: Element): String = root.select("a[data-dl]").joinToString("\n") {
        val label = Regex("\\[(.*?)]").find(it.text())?.groupValues?.get(1) ?: it.text().trim()
        "$label|${decode(it)}"
    }

    override suspend fun load(url: String): LoadResponse? {
        resolveHost()
        val doc = get(url).document
        val title = doc.selectFirst("h1")?.text()?.replace(Regex("\\s+"), " ")?.trim() ?: return null
        val poster = doc.selectFirst(".image-container-view img")?.attr("src")
        val cards = doc.select(".ep-card")
        if (!url.contains("/series/") && cards.isEmpty()) {
            val data = links(doc.selectFirst(".mlbd-download-button-wrap")?.parent() ?: doc)
            return newMovieLoadResponse(title, url, TvType.Movie, data) { posterUrl = poster }
        }
        // ponytail: whole-season packs (outside .ep-card) and multi-episode range cards are skipped (zips).
        val episodes = cards.filter { it.attr("data-range-start") == it.attr("data-range-end") }.mapNotNull { c ->
            val ep = c.attr("data-episode-number").toIntOrNull() ?: return@mapNotNull null
            newEpisode(links(c)) {
                season = c.attr("data-season-number").toIntOrNull() ?: 1
                episode = ep
                name = c.selectFirst(".mlbd-episode-title")?.ownText()?.trim()
            }
        }.sortedBy { it.episode }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) { posterUrl = poster }
    }

    private fun abs(base: String, href: String) = URI(base.replace(" ", "%20")).resolve(href).toString()

    override suspend fun loadLinks(
        data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ): Boolean {
        resolveHost()
        var any = false
        for (line in data.lines().filter { it.contains("|/") }) {
            val (label, path) = line.split("|", limit = 2)
            runCatching {
                // /file/<id> (redirects to the current mirror domain) -> FSL entry button -> /file/<id> with a.fsl-download -> direct mkv
                val r1 = get(fixUrl(path))
                val entry = r1.document.selectFirst("a[data-host=fsl][href^='/file']")?.attr("href") ?: return@runCatching
                val r2 = get(abs(r1.url, entry))
                val fin = r2.document.selectFirst("a.fsl-download")?.attr("href") ?: return@runCatching
                // ponytail: only the FSL host is ported; gphotos/mp4/telegram/xcloud hosts not verified.
                val q = Regex("(\\d{3,4})p").find(label)?.groupValues?.get(1)?.toIntOrNull() ?: if (label.contains("4K", true)) 2160 else 0
                callback(newExtractorLink("FSL", "FSL $label", fin, ExtractorLinkType.VIDEO) { quality = q })
                any = true
            }
        }
        return any
    }
}
