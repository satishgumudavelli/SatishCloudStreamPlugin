package com.vegaprovider.providers

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.vegaprovider.core.VegaBase
import com.vegaprovider.extractors.DriveSeedResolver
import org.jsoup.nodes.Element

/** MoviesMod (urls.json key "Moviesmod"). Selectors/chain captured live 2026-11-13. */
class MoviesMod : VegaBase("Moviesmod", "https://moviesmod.tw") {
    override var name = "MoviesMod"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "" to "Latest",
        "ott/netflix" to "Netflix",
        "ott/hbo-max" to "HBO Max",
        "ott/amazon-prime-video" to "Amazon Prime",
        "ott/apple-tv" to "Apple TV+",
        "ott/disney-plus" to "Disney+",
    )

    private val h get() = mapOf("User-Agent" to UA)
    private fun clean(t: String) = t.replace("Download", "").trim()

    private fun cards(doc: org.jsoup.nodes.Document) = doc.select("article.latestPost").mapNotNull { a: Element ->
        val link = a.selectFirst("h2.title a") ?: return@mapNotNull null
        newMovieSearchResponse(clean(link.text()), link.attr("href"), TvType.Movie) { posterUrl = a.selectFirst("img")?.attr("src") }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        resolveHost()
        val path = if (request.data.isEmpty()) "" else "${request.data}/"
        return newHomePageResponse(request.name, cards(app.get("$mainUrl/$path" + "page/$page/", headers = h).document))
    }

    // ?s= is behind a Cloudflare challenge; the /search/<q>/ path is not.
    override suspend fun search(query: String): List<SearchResponse> {
        resolveHost()
        return cards(app.get("$mainUrl/search/$query/", headers = h).document)
    }

    /** (heading, modpro.blog url) pairs in page order: <h3|h4>heading</h3><p><a class=maxbutton href=..modpro.blog/archives/N>. */
    private fun blocks(doc: org.jsoup.nodes.Document): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var heading = ""
        doc.selectFirst(".thecontent")?.select("h3, h4, a[href*=modpro.blog/archives]")?.forEach { el ->
            if (el.tagName() == "a") {
                if (heading.isNotBlank() && out.none { it.second == el.attr("href") }) out.add(heading to el.attr("href"))
            } else heading = el.text().replace(Regex("\\s+"), " ").trim()
        }
        return out
    }

    override suspend fun load(url: String): LoadResponse? {
        resolveHost()
        val doc = app.get(url, headers = h).document
        val title = clean(doc.selectFirst("h1.entry-title")?.text() ?: return null)
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.select("h2").firstOrNull { it.text().contains("Storyline", true) }?.nextElementSibling()?.text()
        val bl = blocks(doc)
        val isSeries = bl.any { it.second.contains("episodes.modpro.blog") }

        if (!isSeries) return newMovieLoadResponse(title, url, TvType.Movie, bl.joinToString("\n") { "${it.first}|${it.second}" }) {
            posterUrl = poster; this.plot = plot
        }
        // ponytail: first quality block per season supplies the episode list; other qualities not offered per-episode.
        val seasonRe = Regex("Season\\s*(\\d+)", RegexOption.IGNORE_CASE)
        val episodes = ArrayList<Episode>()
        bl.filter { seasonRe.containsMatchIn(it.first) }.distinctBy { seasonRe.find(it.first)!!.groupValues[1] }.forEach { (heading, page) ->
            val season = seasonRe.find(heading)!!.groupValues[1].toInt()
            runCatching { app.get(page, headers = h).document }.getOrNull()
                ?.select("h3 a[href*=thenaukriadda]")?.forEachIndexed { i, a ->
                    episodes.add(newEpisode("$heading|${a.attr("href")}") { this.season = season; episode = i + 1; name = a.text().trim() })
                }
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) { posterUrl = poster; this.plot = plot }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var any = false
        val cb: (ExtractorLink) -> Unit = { any = true; callback(it) }
        for (line in data.lines().filter { it.isNotBlank() }) {
            val (label, link) = line.split("|", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            runCatching {
                if (link.contains("modpro.blog")) {
                    app.get(link, headers = h).document.select("a[href*=thenaukriadda]")
                        .filter { it.text().contains("Drive", true) }.map { it.attr("href") }.distinct()
                        .forEach { runCatching { DriveSeedResolver.resolve(it, label, subtitleCallback, cb) } }
                } else DriveSeedResolver.resolve(link, label, subtitleCallback, cb)
            }
        }
        return any
    }
}
