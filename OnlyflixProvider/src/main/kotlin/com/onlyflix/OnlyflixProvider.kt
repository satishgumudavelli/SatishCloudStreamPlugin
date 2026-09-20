package com.onlyflix

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.runAllAsync
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element

// 23 slugs confirmed live from onlyflix.to's own genre nav (research.md Decision 3). "reality-tv"
// only has a "/best/genre/reality-tv/{year}/" listing, no plain "/genre/reality-tv/" page, so it's
// excluded here rather than guessed.
private val genreSlugs = listOf(
    "animation", "adventure", "action", "biography", "comedy", "crime", "documentary", "drama",
    "family", "fantasy", "history", "horror", "kids", "music", "musical", "mystery", "reality",
    "romance", "sci-fi", "sport", "thriller", "war", "western",
)

class OnlyflixProvider : MainAPI() {
    override var mainUrl = "https://onlyflix.to"
    override var name = "Onlyflix"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // domains.json lives on `master` (fetched fresh every resolve, not baked into the .cs3) so a
    // domain rotation can go live by editing this file alone, no plugin rebuild/republish needed.
    private val domainResolver = DomainResolver(
        domainsJsonUrl = "https://raw.githubusercontent.com/satishgumudavelli/SatishCloudStreamPlugin/master/domains.json",
        targetName = "onlyflix",
        fallbackDomain = mainUrl.removePrefix("https://"),
    )

    override val mainPage = mainPageOf(
        *(
            listOf("home" to "Home", "/movies/" to "Movies", "/series/" to "TV Shows") +
                genreSlugs.map { "/genre/$it/" to it.replace('-', ' ').replaceFirstChar(Char::uppercase) }
            ).toTypedArray()
    )

    // Homepage rail card (research.md Decision 2 / contracts/html-catalogue-scrape.md): the
    // visible <img> is a transparent title-logo, not the poster - the real poster lives in the
    // article's own data-of-lazy-poster attribute.
    private fun Element.toRailSearchResponse(): SearchResponse? {
        val href = selectFirst("a.of-media-card__link")?.attr("href")?.takeIf { it.isNotBlank() } ?: return null
        val title = selectFirst(".of-media-card__name")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val poster = attr("data-of-lazy-poster").takeIf { it.isNotBlank() }
        return if (attr("data-kind") == "tvshows") {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
        }
    }

    // Grid card used by /movies/, /series/, and /genre/{slug}/ (contracts/html-catalogue-scrape.md)
    // - fields ride along as data-* attributes on the article itself, no nested-element parsing needed.
    private fun Element.toGridSearchResponse(): SearchResponse? {
        val href = attr("data-url").takeIf { it.isNotBlank() } ?: return null
        val title = attr("data-title").takeIf { it.isNotBlank() } ?: return null
        val poster = selectFirst(".of-title-card-v2__poster")?.attr("src")?.takeIf { it.isNotBlank() }
        return if (attr("data-ofa-kind") == "tvshows") {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        mainUrl = domainResolver.resolveMainUrl()

        if (request.data == "home") {
            // All 6 rails (Featured/Trending/Best-of-year x Movie/TV Shows) come off one homepage
            // fetch - they aren't individually paged (research.md Decision 2), so this single
            // "home" entry fans out into one HomePageList per rail, similar to FrameMovie's drama
            // fan-out pattern.
            val document = app.get(mainUrl).document
            val lists = document.select("div.of-browse-rail").mapNotNull { rail ->
                val title = rail.selectFirst(".of-browse-rail__title")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val items = rail.select("article.of-media-card").mapNotNull { it.toRailSearchResponse() }
                if (items.isEmpty()) return@mapNotNull null
                HomePageList(title, items, isHorizontalImages = true)
            }
            if (lists.isEmpty()) return newHomePageResponse(emptyList(), hasNext = false)
            return newHomePageResponse(lists, hasNext = false)
        }

        val base = "$mainUrl${request.data}"
        val url = if (page <= 1) base else "${base}page/$page/"
        val document = app.get(url).document
        val items = document.select("article.of-title-card-v2").mapNotNull { it.toGridSearchResponse() }
        val hasNext = document.selectFirst("li.mcs-light-pagination__arrow a.page-link") != null
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    // No live-reachable search endpoint was found for onlyflix.to (Constitution II: leave out
    // rather than guess) - both the native WordPress `?s=` query and the site's own
    // `/search/{query}/` canonical URL render an empty client-side-only shell with no
    // server-rendered results and no discoverable AJAX/REST call firing on input, confirmed via a
    // live Playwright network capture while typing into the site's own search box. Revisit once a
    // real request/response pair is captured live.
    override suspend fun search(query: String): List<SearchResponse> {
        mainUrl = domainResolver.resolveMainUrl()
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        mainUrl = domainResolver.resolveMainUrl()
        val document = app.get(url).document

        val title = document.selectFirst("h1.mb-4")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val poster = document.selectFirst("img.movie-poster")?.attr("src")?.takeIf { it.isNotBlank() }
        val plot = document.selectFirst(".movie-overview p")?.text()?.trim()?.takeIf { it.isNotBlank() }

        val infoRows = document.select(".movie-info-row").associate { row ->
            val label = row.selectFirst(".movie-info-label")?.text()?.trim()?.trimEnd(':') ?: ""
            label to row
        }
        val genres = infoRows["Genre"]?.let { row ->
            row.select(".movie-info-value a").map { it.text().trim() }.filter { it.isNotBlank() }
                .ifEmpty { row.selectFirst(".movie-info-value")?.text()?.split(",")?.map { it.trim() } ?: emptyList() }
        }
        val year = infoRows["Release Year"]?.selectFirst(".movie-info-value")?.text()?.trim()?.toIntOrNull()

        return if (url.contains("/series/")) {
            // Only the season the server chose to render (typically the latest) is present in the
            // DOM on a plain GET - season switching is JS-driven and its request shape wasn't
            // captured live (contracts/html-catalogue-scrape.md), so only that one season's
            // episodes are returned rather than guessing the others' URLs.
            val season = document.selectFirst("#seasonSelect option[selected]")?.attr("value")?.toIntOrNull() ?: 1
            val episodes = document.select(".episode-card-glass").mapNotNull { card ->
                val epUrl = card.selectFirst("a.episode-watch-link")?.attr("href")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val epNumber = Regex("""\d+""").find(card.selectFirst(".episode-card-kicker")?.text() ?: "")?.value?.toIntOrNull()
                val epName = card.selectFirst(".episode-card-title")?.text()?.trim()
                newEpisode(epUrl) {
                    this.name = epName
                    this.season = season
                    this.episode = epNumber
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genres
                this.year = year
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genres
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
        mainUrl = domainResolver.resolveMainUrl()
        val document = app.get(data).document
        val players = OnlyflixExtractor.getPlayers(mainUrl, document)
        if (players.isEmpty()) return false

        runAllAsync(
            *players.mapNotNull { player ->
                val embedUrl = player.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val quality = player.optString("quality").takeIf { it.isNotBlank() }
                val name = player.optString("name")
                val task: suspend () -> Unit = {
                    when {
                        name.contains("nontongo", ignoreCase = true) ->
                            OnlyflixExtractor.invokeNontongo(embedUrl, quality, subtitleCallback, callback)
                        name.contains("cdnm", ignoreCase = true) ->
                            OnlyflixExtractor.invokeCdnm(embedUrl, quality, callback)
                        // vidapi.xyz and vidfast.vc are deferred - no independently verified
                        // protocol yet (research.md Decision 6, Constitution II).
                        else -> Unit
                    }
                }
                task
            }.toTypedArray()
        )
        return true
    }
}
