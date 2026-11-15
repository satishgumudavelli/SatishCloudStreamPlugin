package com.vegaprovider.providers

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.vegaprovider.extractors.isZipUrl
import com.vegaprovider.core.LinkStreamer
import com.vegaprovider.core.VegaBase
import com.vegaprovider.extractors.GenericEmbed
import com.vegaprovider.extractors.GreenMotorsDecoder
import com.vegaprovider.extractors.MirrorResolver
import com.vegaprovider.extractors.PlaydriveResolver
import com.vegaprovider.extractors.VidaraResolver
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

/** hdhub4u (urls.json key "hdhub"). Selectors captured live 2026-11-13. */
class Hdhub4u : VegaBase("hdhub", "https://new13.hdhub4u.cl") {
    override var name = "HDHub4u (Vega)"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "" to "Latest", "category/web-series" to "Web Series", "category/hollywood-movies" to "Hollywood",
        "category/south-hindi-movies" to "South Movies",
    )

    private val h get() = mapOf("Cookie" to "xla=s4t", "Referer" to "https://google.com", "User-Agent" to UA)
    private val seriesRe = Regex("""(?i)\bseason\b|\bS\d{1,2}\b|web series""")
    private fun abs(href: String) = if (href.startsWith("http")) href else URI(mainUrl + "/").resolve(href).toString()
    private fun clean(t: String) = t.replace("Download", "", true).replace(Regex("""\s+"""), " ").trim()
    private fun card(title: String, href: String, poster: String?): SearchResponse =
        if (seriesRe.containsMatchIn(title)) newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = poster }
        else newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        resolveHost()
        val base = if (request.data.isEmpty()) mainUrl else "$mainUrl/${request.data}"
        val doc = app.get(if (page == 1) "$base/" else "$base/page/$page/", headers = h).document
        val items = doc.select("ul.recent-movies > li.thumb").mapNotNull { li ->
            val a = li.selectFirst("a[href]") ?: return@mapNotNull null
            val img = li.selectFirst("img")
            val title = clean(img?.attr("alt")?.ifBlank { null } ?: a.attr("aria-label")).ifBlank { return@mapNotNull null }
            card(title, abs(a.attr("href")), img?.attr("src"))
        }
        return newHomePageResponse(request.name, items)
    }

    // site search is a typesense collection on search.pingora.fyi (same one the site's own JS calls)
    override suspend fun search(query: String): List<SearchResponse> {
        resolveHost()
        val q = URLEncoder.encode(query, "UTF-8")
        val json = JSONObject(app.get(
            "https://search.pingora.fyi/collections/post/documents/search?q=$q&query_by=post_title,category,stars,director,imdb_id" +
                "&query_by_weights=4,2,2,2,4&sort_by=sort_by_date:desc&limit=15&highlight_fields=none&page=1",
            headers = h + mapOf("Referer" to "$mainUrl/", "Accept" to "application/json"),
        ).text)
        val hits = json.optJSONArray("hits") ?: return emptyList()
        return (0 until hits.length()).mapNotNull { i ->
            val d = hits.getJSONObject(i).optJSONObject("document") ?: return@mapNotNull null
            val title = clean(d.optString("post_title")).ifBlank { return@mapNotNull null }
            val link = d.optString("permalink").ifBlank { return@mapNotNull null }
            card(title, abs(link), d.optString("post_thumbnail").ifBlank { null })
        }
    }

    private fun quality(t: String) = Regex("""(?i)\b(480|720|1080|2160)p\b|\b4k\b""").find(t)?.value ?: ""
    private val epRe = Regex("""(?i)^(?:episodes?\s*[:.-]?\s*|E)(\d+)\b""")
    private fun isHost(href: String) = href.contains("hubdrive") || href.contains("hubcloud") || GreenMotorsDecoder.isShort(href) || VidaraResolver.isVidara(href) || href.contains("hubstream.") || href.contains("hubcdn.")

    override suspend fun load(url: String): LoadResponse? {
        resolveHost()
        val doc = app.get(url, headers = h).document
        val c = doc.selectFirst(".page-body") ?: doc.selectFirst(".entry-content") ?: return null
        val raw = doc.selectFirst("h1")?.text().orEmpty()
        val title = clean(raw.split(Regex("""(?i)\s+(?:WEB-DL|WEBRip|BluRay|HDRip|480p|720p|1080p)\b|\s*\["""))[0]).ifBlank { clean(raw) }
        val poster = c.selectFirst("img")?.let { it.attr("data-src").ifBlank { it.attr("src") } }?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
        val year = Regex("""\((\d{4})\)""").find(raw)?.groupValues?.get(1)?.toIntOrNull()

        // series: after a "Single Episode" heading, links appear per episode/quality in page order.
        // ponytail: episode number = nth occurrence of that quality unless an "Episode N" heading says otherwise.
        // bonus rows ("BONUS EP 1", "BONUS CLiP OF EP04") carry no episode number; give each its own entry instead of letting
        // them inherit the previous episode and show up as extra "qualities" of it.
        val epNames = HashMap<Int, String>()
        fun collect(startInSingle: Boolean): java.util.SortedMap<Int, MutableList<String>> {
            val eps = sortedMapOf<Int, MutableList<String>>()
            val counters = HashMap<String, Int>()
            var inSingle = startInSingle; var curEp = 0; var q = ""; var bonusIdx = 0
            c.select("h2,h3,h4,h5,a[href]").forEach { el ->
                if (el.tagName() != "a") {
                    val t = el.ownText().ifBlank { el.text() }.replace(Regex("""\s+"""), " ").trim()
                    if (el.tagName() == "h2" || el.tagName() == "h3") {
                        if (Regex("""(?i)single episode|episode.*(links|index)""").containsMatchIn(t)) { inSingle = true; curEp = 0; counters.clear() }
                        else if (Regex("""(?i)storyline|review|description""").containsMatchIn(t)) inSingle = false
                    }
                    if (Regex("""(?i)\bbonus\b""").containsMatchIn(t)) { curEp = 100 + ++bonusIdx; epNames[curEp] = t.take(50) }
                    else epRe.find(t)?.let { curEp = it.groupValues[1].toInt() }
                    quality(t).takeIf { it.isNotEmpty() }?.let { q = it }
                    return@forEach
                }
                val href = el.attr("href")
                // a "WATCH" anchor is an embed link on any host; everything else must be a known download host
                val isWatch = Regex("""(?i)^\s*watch""").containsMatchIn(el.text())
                if (!inSingle || !(isHost(href) || isWatch)) return@forEach
                val ql = quality(el.text()).ifEmpty { q }
                val ep = if (curEp > 0) curEp else (counters[ql] ?: 0).plus(1).also { counters[ql] = it }
                eps.getOrPut(ep) { mutableListOf() }.add("${if (isWatch || VidaraResolver.isVidara(href) || href.contains("hubstream.")) "Watch" else ql.ifEmpty { "Unknown" }}|${abs(href)}")
            }
            return eps
        }
        // Some posts (e.g. "India's Got Latent") have no "Single Episode" heading: one "EPiSODE N | WATCH" row per episode
        // under "DOWNLOAD LINKS", whose EPiSODE link is the greenmotors short link. Retry treating the whole body as the list.
        val eps = collect(false).ifEmpty { if (seriesRe.containsMatchIn(raw)) collect(true) else collect(false) }
        if (eps.isNotEmpty() || seriesRe.containsMatchIn(raw)) {
            if (eps.isEmpty()) return null
            val episodes = eps.map { (n, v) -> newEpisode(v.distinct().joinToString("\n")) { name = epNames[n] ?: "Episode $n"; season = 1; episode = n } }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) { posterUrl = poster; this.year = year }
        }
        val data = c.select("a[href]").filter { isHost(it.attr("href")) && quality(it.text()).isNotEmpty() }
            .joinToString("\n") { "${it.text().trim().replace("|", "/")}|${abs(it.attr("href"))}" }.ifBlank { return null }
        return newMovieLoadResponse(title, url, TvType.Movie, data) { posterUrl = poster; this.year = year }
    }

    /** hubdrive -> hubcloud (btn-success1); greenmotors -> hblinks/hubdrive/hubcloud (verified 2026-11-13). */
    private suspend fun resolveHop(link: String, label: String, sub: (SubtitleFile) -> Unit, cb: (ExtractorLink) -> Unit, depth: Int = 0) {
        if (depth > 3) return
        when {
            VidaraResolver.isVidara(link) -> VidaraResolver.resolve(link, label, sub, cb)
            PlaydriveResolver.isPlaydrive(link) -> PlaydriveResolver.resolve(link, label, "$mainUrl/", cb)
            // hubstream.art/#<id> is a thin redirector: its public resolve api returns the real embed (vidara, turboembed, ...)
            link.contains("hubstream.") -> link.substringAfter("#", "").takeIf { it.isNotBlank() }?.let { id ->
                JSONObject(app.get("https://hubstream.art/api/public/resolve?file1=$id", headers = h).text).optString("url")
                    .takeIf { it.startsWith("http") }?.let { resolveHop(it, label, sub, cb, depth + 1) }
            }
            // hubcdn "Instant": redirect page with var reurl = "...?r=<b64(https://hubcdn.club/dl/?link=<r2.dev mkv>)>" (verified 2026-11-15,
            // the r2.dev file answers range requests).
            link.contains("hubcdn.") -> {
                val reurl = Regex("""var reurl\s*=\s*"([^"]+)"""").find(app.get(link, headers = h).text)?.groupValues?.get(1)
                val r = reurl?.substringAfter("?r=", "")?.substringBefore("&")?.takeIf { it.isNotBlank() }
                val decoded = r?.let { runCatching { String(java.util.Base64.getDecoder().decode(java.net.URLDecoder.decode(it, "UTF-8"))) }.getOrNull() }
                decoded?.substringAfter("link=", "")?.takeIf { it.startsWith("http") && !isZipUrl(it) }?.let { final ->
                    cb(newExtractorLink("HubCDN", "HubCDN $label", final, ExtractorLinkType.VIDEO) { quality = getQualityFromName(label) })
                }
            }
            link.contains("hubcloud") -> MirrorResolver.resolve(link, label, sub, cb)
            link.contains("hubdrive") -> app.get(link, headers = h).document.selectFirst("a.btn-success1[href]")
                ?.attr("href")?.let { resolveHop(it, label, sub, cb, depth + 1) }
            GreenMotorsDecoder.isShort(link) -> GreenMotorsDecoder.decode(app.get(link, headers = h).text)
                ?.let { resolveHop(it, label, sub, cb, depth + 1) }
            // hblinks episode/file page: one heading per quality ("480p - Drive | Instant | Direct"); "Direct" is the hubcloud link.
            link.contains("hblinks") -> {
                val doc = app.get(link, headers = h).document
                val rows = doc.select("h3, h4, h5").filter { r -> r.select("a[href*=hubcloud], a[href*=hubcdn]").isNotEmpty() }
                if (rows.isEmpty()) doc.select("a[href]").map { it.attr("href") }.firstOrNull { it.contains("hubcloud") }
                    ?.let { resolveHop(it, label, sub, cb, depth + 1) }
                else rows.amap { r ->
                    val ql = r.text().substringBefore("–").substringBefore("Drive").trim().ifBlank { label }
                    r.select("a[href*=hubcloud], a[href*=hubcdn]").amap { a -> resolveHop(a.attr("href"), ql, sub, cb, depth + 1) }
                }
            }
            // any other embed host (e.g. what hubstream resolves to): built-in extractors take it if they know the host
            else -> {
                var got = false
                runCatching { loadExtractor(link, "$mainUrl/", sub) { got = true; cb(it) } }
                if (!got) runCatching { GenericEmbed.resolve(link, label, "$mainUrl/", cb) }
            }
        }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ): Boolean = LinkStreamer.stream(data.lines().filter { it.contains("|") }, callback) { l, cb ->
        resolveHop(l.substringAfter("|"), l.substringBefore("|"), subtitleCallback, cb)
    }
}
