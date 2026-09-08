package com.movies4u

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Movies4uProvider : MainAPI() {
    override var mainUrl = "https://movies4u.ag"
    override var name = "Movies4uAG"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(*categories.toTypedArray())

    // domains.json lives on `master` (fetched fresh every resolve, not baked into the .cs3) so a
    // domain rotation can go live by editing this file alone, no plugin rebuild/republish needed.
    private val domainResolver = DomainResolver(
        domainsJsonUrl = "https://raw.githubusercontent.com/satishgumudavelli/SatishCloudStreamPlugin/master/domains.json",
        targetName = "movies4u",
        fallbackDomain = mainUrl.removePrefix("https://"),
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        mainUrl = domainResolver.resolveMainUrl()
        val base = if (request.data.isEmpty()) mainUrl else "$mainUrl/category/${request.data}"
        val url = if (page == 1) "$base/" else "$base/page/$page/"
        val document = app.get(url).document
        val items = document.select(".entry-card.card-content").mapNotNull { it.toSearchResponse() }
        val hasNext = document.selectFirst("a.next.page-numbers") != null
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        mainUrl = domainResolver.resolveMainUrl()
        val document = app.get("$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}").document
        return document.select(".entry-card.card-content").mapNotNull { it.toSearchResponse() }
    }

    // A post's own category-xxx classes double as its TvType tag, same convention on the
    // homepage/search article card and on the post's own content wrapper. That tag alone misses
    // real series posted under a different category (e.g. "Khatron Ke Khiladi" is tagged only
    // category-tv-show, confirmed live) - when the caller has the post's info text on hand
    // (only available on the detail page, not the listing card), also match it against the same
    // "Season:"/"Episode:"/"SHOW Name:" phrases the reference implementation falls back to.
    private val seriesTextSignal = Regex("""(?i)Season:|Episode:|SHOW Name:""")

    private fun Element.isWebSeries(infoText: String? = null): Boolean {
        if (classNames().contains("category-web-series")) return true
        return infoText != null && seriesTextSignal.containsMatchIn(infoText)
    }

    // Truncating at the first quality/format marker (rather than removing markers everywhere
    // and trimming only the ends) avoids leaving debris behind mid-title - e.g. "Mirzapur: The
    // Movie (2026) HQ-HDTC [Hindi + Telugu] (ORG) 480p | 720p | 1080p" would leave a dangling
    // "HQ- (ORG) | |" if tokens were stripped in place instead of just cutting the title short.
    private val titleNoisePattern = Regex("""(?i)\[|\b\d+p\b|\b4k\b|HDTC|HDTS|HDRip|BluRay|WEB-DL|Full Movie""")

    private fun cleanTitle(raw: String): String {
        val cut = titleNoisePattern.find(raw)?.range?.first ?: return raw
        return raw.substring(0, cut).trim(' ', '-', '|', ':').ifBlank { raw }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val titleEl = selectFirst("h2.entry-title a") ?: return null
        val title = cleanTitle(titleEl.text().trim())
        val href = titleEl.attr("href")
        val poster = selectFirst("img")?.attr("src")
        return if (isWebSeries()) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = cleanTitle(
            document.selectFirst("meta[property=og:title]")?.attr("content")?.ifBlank { null }
                ?: document.selectFirst("h1.entry-title")?.text()
                ?: return null
        )
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst(".entry-content img")?.attr("src")
        val plot = document.selectFirst(".entry-content p")?.text()?.trim()?.ifBlank { null }
        val year = Regex("""(19|20)\d{2}""").find(title)?.value?.toIntOrNull()
        val postEl = document.selectFirst(".post.type-post")
        val infoText = document.selectFirst(".entry-content")?.text()

        // Quality -> mdrive.cloud page (h4 label followed by the link). The site inconsistently
        // wraps that link in a <p> or a <div class="downloads-btns-div"> from post to post, so
        // match the mdrive href directly and walk up to whichever wrapper actually holds it
        // instead of assuming <p> - the old p-only selector silently dropped every mdrive link
        // that happened to sit in a downloads-btns-div (e.g. movies with several quality tiers).
        val qualityLinks = postEl?.select("a[href*=mdrive.cloud/mdisk]")
            ?.map { a ->
                val quality = a.closest("p, div.downloads-btns-div")?.previousElementSibling()
                    ?.takeIf { it.tagName() == "h4" || it.tagName() == "h3" }
                    ?.text()?.trim()?.ifBlank { null } ?: title
                quality to a.attr("href")
            }
            ?.distinctBy { it.second } // several quality buttons can point at the same mdrive page
            ?: emptyList()
        if (qualityLinks.isEmpty()) return null

        return if (postEl?.isWebSeries(infoText) == true) {
            // A series' mdrive page usually lists one "-:Episodes: N:-" heading + its mirror
            // links right after (heading tag h3/h4/h5, wrapper <p> or div.downloads-btns-div -
            // both vary per series, so match the heading by its text and take whatever its next
            // sibling is). Some series are only ever released as a whole-season batch though -
            // their mdrive page has no "Episodes:" heading at all, just the movie-style h4
            // quality tiers - so fall back to treating the entire page as one episode, keeping
            // each of ITS OWN quality headers (if any) as alternate sources for that episode.
            // Skip whole-season zip qualities when a real per-episode breakdown also exists
            // elsewhere in the same post, and key everything by (season, episode) - the post's
            // own quality label carries the season number for multi-season posts.
            val perSeasonEpisodes = qualityLinks
                .filterNot { (quality, _) -> quality.contains("zip", ignoreCase = true) || quality.contains("batch", ignoreCase = true) }
                .amap { (quality, mdriveUrl) ->
                    val season = Regex("""(?i)season\s*(\d+)""").find(quality)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    val container = runCatching { app.get(mdriveUrl).document }.getOrNull()
                        ?.selectFirst("#container-content-single")

                    val episodeHeadings = container?.select("h3, h4, h5")?.mapNotNull { heading ->
                        val epNum = Regex("""(?i)episodes?[:\s]*(\d+)""").find(heading.text())?.groupValues?.get(1)?.toIntOrNull()
                            ?: return@mapNotNull null
                        val mirrors = heading.nextElementSibling()?.select("a[href^=http]") ?: return@mapNotNull null
                        Triple(season, epNum, mirrors.map { a -> Movies4uLink(quality, a.attr("href"), direct = true) })
                    } ?: emptyList()

                    if (episodeHeadings.isNotEmpty()) {
                        episodeHeadings
                    } else {
                        val qualityHeaders = container?.select("h4") ?: emptyList()
                        val links = if (qualityHeaders.isEmpty()) {
                            container?.select("a[href^=http]")?.map { a -> Movies4uLink(quality, a.attr("href"), direct = true) } ?: emptyList()
                        } else {
                            qualityHeaders.flatMap { h4 ->
                                val qLabel = h4.text().trim().ifBlank { quality }
                                h4.nextElementSibling()?.select("a[href^=http]")?.map { a -> Movies4uLink(qLabel, a.attr("href"), direct = true) } ?: emptyList()
                            }
                        }
                        if (links.isEmpty()) emptyList() else listOf(Triple(season, 1, links))
                    }
                }
                .flatten()

            val episodeLinks = linkedMapOf<Pair<Int, Int>, MutableList<Movies4uLink>>()
            perSeasonEpisodes.forEach { (season, epNum, links) -> episodeLinks.getOrPut(season to epNum) { mutableListOf() }.addAll(links) }
            if (episodeLinks.isEmpty()) return null

            val episodes = episodeLinks.entries
                .sortedWith(compareBy({ it.key.first }, { it.key.second }))
                .map { (key, links) ->
                    val (season, epNum) = key
                    newEpisode(links.toJson()) {
                        this.name = "Episode $epNum"
                        this.season = season
                        this.episode = epNum
                    }
                }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        } else {
            val links = qualityLinks.map { (quality, mdriveUrl) -> Movies4uLink(quality, mdriveUrl) }
            newMovieLoadResponse(title, url, TvType.Movie, links.toJson()) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val links = parseJson<List<Movies4uLink>>(data)
        links.amap { link ->
            if (link.direct) {
                Movies4uExtractor.resolve(link.url, link.quality, subtitleCallback, callback)
            } else {
                Movies4uExtractor.resolveMdrivePage(link.url, link.quality, subtitleCallback, callback)
            }
        }
        return true
    }

    data class Movies4uLink(val quality: String, val url: String, val direct: Boolean = false)

    companion object {
        // WordPress category slugs, taken from the site's own homepage category list.
        // Pair(slug, display name) - slug doubles as MainPageRequest.data.
        val categories = listOf(
            "" to "Latest",
            "18" to "18+",
            "bangali" to "Bangali",
            "bollywood" to "Bollywood",
            "chinese" to "Chinese",
            "dual-audio" to "Dual Audio",
            "english" to "English",
            "gujarati" to "Gujarati",
            "hindi" to "Hindi",
            "hollywood" to "Hollywood",
            "kannada-movie" to "Kannada",
            "korean" to "Korean",
            "malayalam" to "Malayalam",
            "marathi" to "Marathi",
            "odia" to "Odia",
            "punjabi" to "Punjabi",
            "south-indian" to "South Indian",
            "tamil" to "Tamil",
            "telugu" to "Telugu",
            "tv-show" to "TV Show",
            "urdu" to "Urdu",
            "web-series" to "Web Series",
        )
    }
}
