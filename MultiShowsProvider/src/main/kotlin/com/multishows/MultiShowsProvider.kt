package com.multishows

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element
import java.net.URLEncoder

class MultiShowsProvider : MainAPI() {
    override var mainUrl = "https://multishows.top"
    override var name = "MultiShows"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(*categories.toTypedArray())

    // domains.json lives on `master` (fetched fresh every resolve, not baked into the .cs3) so a
    // domain rotation can go live by editing this file alone, no plugin rebuild/republish needed.
    private val domainResolver = DomainResolver(
        domainsJsonUrl = "https://raw.githubusercontent.com/satishgumudavelli/SatishCloudStreamPlugin/master/domains.json",
        targetName = "multishows",
        fallbackDomain = mainUrl.removePrefix("https://"),
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        mainUrl = domainResolver.resolveMainUrl()
        val url = if (page <= 1) "$mainUrl${request.data}" else "$mainUrl${request.data}?page=$page"
        val document = app.get(url).document
        // Real cards are `div.relative.group.overflow-hidden` - the bare `.relative.group` the
        // site's own markup also uses matches both Livewire's `wire:loading` skeleton
        // placeholders (gated by wire:target="filter") and unrelated genre-filter pill `<a>`
        // chips on this same page that happen to share those 3 classes (verified live: without
        // the `div` tag qualifier, 5 of 29 "cards" on a real /browse page are actually those
        // chips, not posters).
        val items = document.select("div.relative.group.overflow-hidden").mapNotNull { it.toSearchResponse() }
        // No stable "last page" indicator is exposed without simulating the site's Livewire
        // pagination button - a page past the end verified live returns HTTP 200 with zero cards,
        // so treat "cards found" as the honest hasNext signal.
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        mainUrl = domainResolver.resolveMainUrl()
        val document = app.get("$mainUrl/search/${URLEncoder.encode(query, "UTF-8")}").document
        return document.select("div.relative.group.overflow-hidden").mapNotNull { it.toSearchResponse() }
    }

    // Movie vs TV Show is read straight from the card's own detail-url path prefix - simpler and
    // always-present, unlike the per-card text badge, and confirmed to agree with it live.
    private fun Element.toSearchResponse(): SearchResponse? {
        val a = selectFirst("a[href]") ?: return null
        val href = a.attr("href")
        val img = selectFirst("img")
        val title = img?.attr("alt")?.trim()?.ifBlank { null }
            ?: selectFirst("h3")?.text()?.trim()?.ifBlank { null }
            ?: return null
        // lazysizes lazy-loads posters: `src` is a 1x1 placeholder gif, the real url is `data-src`.
        val poster = img?.attr("data-src")?.ifBlank { null } ?: img?.attr("src")

        return if (href.contains("/tv-show/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h3.text-xl")?.text()?.trim()?.ifBlank { null } ?: return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
        val plot = document.selectFirst("p.text-gray-400.mt-3")?.text()?.trim()?.ifBlank { null }
        val tags = document.select("a[href*=/genre/]").mapNotNull { it.text().trim().ifBlank { null } }

        return if (url.contains("/tv-show/")) {
            // Only the season Livewire renders by default on a full page load is scrapable this
            // way - switching seasons is a `wire:click="updateSeason(...)"` AJAX action (a real
            // /livewire/update POST replaying that component's snapshot/checksum), not confirmed
            // live yet. Seasons beyond the default one are left out rather than guessed at.
            val episodes = document.select("a[href*=/episode/]").mapNotNull { a ->
                val href = a.attr("href")
                val (season, episode) = href.trimEnd('/').substringAfterLast('/').split("-")
                    .let { parts -> (parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null) to (parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null) }
                val container = a.parent()
                val epTitle = container?.selectFirst("h3")?.text()?.trim()?.ifBlank { null } ?: "Episode $episode"
                val epPoster = container?.selectFirst("img")?.attr("src")?.ifBlank { null }
                newEpisode(href) {
                    this.name = epTitle
                    this.season = season
                    this.episode = episode
                    this.posterUrl = epPoster
                }
            }
            if (episodes.isEmpty()) return null
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        MultiShowsExtractor.invoke(data, subtitleCallback, callback)
        return true
    }

    companion object {
        // (url path, display name) - matches the eleven category/genre pages in scope (spec.md).
        val categories = listOf(
            "/browse" to "Browse",
            "/trending" to "Trending",
            "/top-imdb" to "Top IMDB",
            "/movies" to "Movies",
            "/tv-shows" to "TV Shows",
            "/collections" to "Collections",
            "/genre/action" to "Action",
            "/genre/adventure" to "Adventure",
            "/genre/animation" to "Animation",
            "/genre/comedy" to "Comedy",
            "/genre/crime" to "Crime",
        )
    }
}
