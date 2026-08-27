package com.vidbox

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.runAllAsync
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.vidbox.VidboxExtractor.invoke111Movies
import com.vidbox.VidboxExtractor.invoke2Embed
import com.vidbox.VidboxExtractor.invokeBravo
import com.vidbox.VidboxExtractor.invokeCinemaos
import com.vidbox.VidboxExtractor.invokeFrembed
import com.vidbox.VidboxExtractor.invokeMax
import com.vidbox.VidboxExtractor.invokeMplay
import com.vidbox.VidboxExtractor.invokeNxsha
import com.vidbox.VidboxExtractor.invokePeachify
import com.vidbox.VidboxExtractor.invokeRive
import com.vidbox.VidboxExtractor.invokeTongo
import com.vidbox.VidboxExtractor.invokeVideasy
import com.vidbox.VidboxExtractor.invokeVidlink
import com.vidbox.VidboxExtractor.invokeVidnest
import com.vidbox.VidboxExtractor.invokeXpass
import com.vidbox.VidboxExtractor.invokevidrock
import org.json.JSONObject

class VidboxProvider : MainAPI() {
    override var mainUrl = vidboxMainUrl
    override var name = "Vidbox"
    override val hasMainPage = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    override val mainPage = mainPageOf(*homeRows.toTypedArray())

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        mainUrl = VidboxScraper.resolveMainUrl()
        val result = VidboxScraper.discover(request.data, page)
        val shows = result.results.mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, shows, hasNext = page < result.totalPages)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        mainUrl = VidboxScraper.resolveMainUrl()
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        return VidboxScraper.discover("q=$q", 1).results.mapNotNull { it.toSearchResponse() }
    }

    private suspend fun JSONObject.toSearchResponse(): SearchResponse? {
        val mediaType = optString("media_type").ifEmpty { if (has("first_air_date")) "tv" else "movie" }
        val title = optString("title").ifEmpty { optString("name") }
        if (title.isEmpty()) {
            return null
        }
        val id = optInt("id", -1)
        if (id == -1) {
            return null
        }
        val poster = optString("poster_path").takeIf { it.isNotBlank() }
            ?.let { "https://image.tmdb.org/t/p/w342$it" }
        val score = optDouble("vote_average", 0.0).let { if (it > 0) Score.from10(it.toString()) else null }
        val base = VidboxScraper.resolveMainUrl()

        return if (mediaType == "tv") {
            newTvSeriesSearchResponse(title, "$base/tv/$id", TvType.TvSeries) {
                this.posterUrl = poster
                this.score = score
            }
        } else {
            newMovieSearchResponse(title, "$base/movie/$id", TvType.Movie) {
                this.posterUrl = poster
                this.score = score
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = url.substringAfterLast("/").toIntOrNull() ?: return null

        return if (url.contains("/tv/")) {
            val tv = VidboxScraper.scrapeTvDetail(id) ?: return null
            val title = tv.optString("name").ifEmpty { return null }
            val poster = tv.optString("poster_path").takeIf { it.isNotBlank() }
                ?.let { "https://image.tmdb.org/t/p/w342$it" }
            val backdrop = tv.optString("backdrop_path").takeIf { it.isNotBlank() }
                ?.let { "https://image.tmdb.org/t/p/original$it" }
            val genres = tv.optJSONArray("genres")
                ?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name") } }
            val year = tv.optString("first_air_date").split("-").firstOrNull()?.toIntOrNull()

            val episodes = tv.optJSONArray("seasons")?.let { seasons ->
                (0 until seasons.length()).mapNotNull { i ->
                    val season = seasons.optJSONObject(i) ?: return@mapNotNull null
                    val seasonNumber = season.optInt("season_number", -1)
                    if (seasonNumber < 1) return@mapNotNull null
                    VidboxScraper.fetchSeasonEpisodes(id, seasonNumber).map { eps ->
                        newEpisode(
                            VidLinkData(
                                id = id,
                                season = seasonNumber,
                                episode = eps.optInt("episode_number"),
                                title = title,
                                year = year,
                            ).toJson()
                        ) {
                            this.name = eps.optString("name")
                            this.season = seasonNumber
                            this.episode = eps.optInt("episode_number")
                            this.posterUrl = eps.optString("still_path").takeIf { it.isNotBlank() }
                                ?.let { "https://image.tmdb.org/t/p/w300$it" }
                            this.description = eps.optString("overview").takeIf { it.isNotBlank() }
                        }
                    }
                }.flatten()
            } ?: emptyList()

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = tv.optString("overview").takeIf { it.isNotBlank() }
                this.tags = genres
                this.score = tv.optDouble("vote_average", 0.0).let { if (it > 0) Score.from10(it.toString()) else null }
            }
        } else {
            val movie = VidboxScraper.scrapeMovieDetail(id) ?: return null
            newMovieLoadResponse(
                movie.title,
                url,
                TvType.Movie,
                VidLinkData(id = id, title = movie.title, year = movie.year).toJson(),
            ) {
                this.posterUrl = movie.posterUrl
                this.backgroundPosterUrl = movie.backdropUrl
                this.year = movie.year
                this.plot = movie.overview
                this.tags = movie.genres
                this.score = movie.score?.let { Score.from10(it) }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val link = parseJson<VidLinkData>(data)
        runAllAsync(
            { invokevidrock(link.id, link.season, link.episode, callback) },
            { invokeVidlink(link.id, link.season, link.episode, subtitleCallback, callback) },
            { invokeVideasy(link.id, link.season, link.episode, link.title, link.year, subtitleCallback, callback) },
            { invoke111Movies(link.id, link.season, link.episode, subtitleCallback, callback) },
            { invokePeachify(link.id, link.season, link.episode, subtitleCallback, callback) },
            { invokeFrembed(link.id, link.season, link.episode, subtitleCallback, callback) },
            { invokeVidnest(link.id, link.season, link.episode, callback) },
            { invokeMplay(link.id, link.season, link.episode, callback) },
            { invokeXpass(link.id, link.season, link.episode, callback) },
            { invoke2Embed(link.id, link.season, link.episode, callback) },
            { invokeCinemaos(link.id, link.season, link.episode, link.title, link.year, subtitleCallback, callback) },
            { invokeBravo(link.id, link.season, link.episode, callback) },
            { invokeNxsha(link.id, link.season, link.episode, subtitleCallback, callback) },
            { invokeMax(link.id, link.season, link.episode, subtitleCallback, callback) },
            { invokeTongo(link.id, link.season, link.episode, callback) },
            { invokeRive(link.id, link.season, link.episode, subtitleCallback, callback) },
        )
        return true
    }

    data class VidLinkData(
        val id: Int? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val title: String? = null,
        val year: Int? = null,
    )
}
