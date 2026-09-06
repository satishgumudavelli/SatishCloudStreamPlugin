package com.movies4u

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Movies4uProvider : MainAPI() {
    override var mainUrl = "https://movies4u.ag"
    override var name = "Movies4u"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(*categories.toTypedArray())

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = if (request.data.isEmpty()) mainUrl else "$mainUrl/category/${request.data}"
        val url = if (page == 1) "$base/" else "$base/page/$page/"
        val document = app.get(url).document
        val items = document.select(".entry-card.card-content").mapNotNull { it.toSearchResponse() }
        val hasNext = document.selectFirst("a.next.page-numbers") != null
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}").document
        return document.select(".entry-card.card-content").mapNotNull { it.toSearchResponse() }
    }

    // A post's own category-xxx classes double as its TvType tag, same convention on the
    // homepage/search article card and on the post's own content wrapper.
    private fun Element.isWebSeries() = classNames().contains("category-web-series")

    private fun Element.toSearchResponse(): SearchResponse? {
        val titleEl = selectFirst("h2.entry-title a") ?: return null
        val title = titleEl.text().trim()
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
        val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.ifBlank { null }
            ?: document.selectFirst("h1.entry-title")?.text()
            ?: return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst(".entry-content img")?.attr("src")
        val plot = document.selectFirst(".entry-content p")?.text()?.trim()?.ifBlank { null }
        val year = Regex("""(19|20)\d{2}""").find(title)?.value?.toIntOrNull()
        val postEl = document.selectFirst(".post.type-post")

        // Quality -> mdrive.cloud page, same for movies and series (h4 label, p > a link).
        val qualityLinks = document.select(".post.type-post p a")
            .filter { it.attr("href").contains("mdrive.cloud/mdisk") }
            .map { a ->
                val quality = a.parent()?.previousElementSibling()
                    ?.takeIf { it.tagName() == "h4" || it.tagName() == "h3" }
                    ?.text()?.trim()?.ifBlank { null } ?: title
                quality to a.attr("href")
            }
        if (qualityLinks.isEmpty()) return null

        return if (postEl?.isWebSeries() == true) {
            // A series' mdrive page doesn't show single-file mirrors like a movie's does - it
            // lists one "-:Episodes: N:-" heading + a downloads-btns-div of direct mirror links
            // per episode. Skip whole-season zip qualities (no per-episode breakdown to expand)
            // and merge the per-quality episode lists together by episode number.
            val perQuality = qualityLinks
                .filterNot { (quality, _) -> quality.contains("zip", ignoreCase = true) || quality.contains("batch", ignoreCase = true) }
                .amap { (quality, mdriveUrl) ->
                    val mdriveDoc = runCatching { app.get(mdriveUrl).document }.getOrNull()
                    mdriveDoc?.select(".downloads-btns-div")?.mapNotNull { div ->
                        val epNum = div.previousElementSibling()
                            ?.takeIf { it.tagName() == "h5" }
                            ?.text()?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }
                            ?: return@mapNotNull null
                        epNum to div.select("a[href^=http]").map { a -> Movies4uLink(quality, a.attr("href"), direct = true) }
                    } ?: emptyList()
                }
                .flatten()

            val episodeLinks = sortedMapOf<Int, MutableList<Movies4uLink>>()
            perQuality.forEach { (epNum, links) -> episodeLinks.getOrPut(epNum) { mutableListOf() }.addAll(links) }
            if (episodeLinks.isEmpty()) return null

            val episodes = episodeLinks.map { (epNum, links) ->
                newEpisode(links.toJson()) {
                    this.name = "Episode $epNum"
                    this.season = 1
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
                return@amap
            }
            val mdriveDoc = runCatching { app.get(link.url).document }.getOrNull() ?: return@amap
            mdriveDoc.select("#theme-main p a")
                .map { it.attr("href") }
                .filter { it.startsWith("http") }
                .distinct()
                .amap { mirrorUrl ->
                    Movies4uExtractor.resolve(mirrorUrl, link.quality, subtitleCallback, callback)
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
