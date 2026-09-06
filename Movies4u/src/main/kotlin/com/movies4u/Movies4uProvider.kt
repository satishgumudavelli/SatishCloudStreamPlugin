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

    private fun Element.toSearchResponse(): SearchResponse? {
        val titleEl = selectFirst("h2.entry-title a") ?: return null
        val title = titleEl.text().trim()
        val href = titleEl.attr("href")
        val poster = selectFirst("img")?.attr("src")
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
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

        val links = document.select(".post.type-post p a")
            .filter { it.attr("href").contains("mdrive.cloud/mdisk") }
            .map { a ->
                val quality = a.parent()?.previousElementSibling()
                    ?.takeIf { it.tagName() == "h4" || it.tagName() == "h3" }
                    ?.text()?.trim()?.ifBlank { null } ?: title
                Movies4uLink(quality, a.attr("href"))
            }
        if (links.isEmpty()) return null

        return newMovieLoadResponse(title, url, TvType.Movie, links.toJson()) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
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

    data class Movies4uLink(val quality: String, val url: String)

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
