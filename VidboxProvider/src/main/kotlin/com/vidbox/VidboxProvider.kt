package com.vidbox

import android.util.Log
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
import com.vidbox.VidboxExtractor.invokeMoviesApi
import com.vidbox.VidboxExtractor.invokeVidlink
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
        val result = VidboxScraper.discover(request.data, page)
        val shows = result.results.mapNotNull { it.toSearchResponse() }
        Log.d(
            "VidboxProvider",
            "getMainPage name=${request.name} data=${request.data} page=$page " +
                "rawResults=${result.results.size} shows=${shows.size} totalPages=${result.totalPages}"
        )
        return newHomePageResponse(request.name, shows, hasNext = page < result.totalPages)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        return VidboxScraper.discover("q=$q", 1).results.mapNotNull { it.toSearchResponse() }
    }

    private fun JSONObject.toSearchResponse(): SearchResponse? {
        val mediaType = optString("media_type").ifEmpty { if (has("first_air_date")) "tv" else "movie" }
        val title = optString("title").ifEmpty { optString("name") }
        if (title.isEmpty()) {
            Log.d("VidboxProvider", "toSearchResponse dropped item with no title/name: $this")
            return null
        }
        val id = optInt("id", -1)
        if (id == -1) {
            Log.d("VidboxProvider", "toSearchResponse dropped '$title' with no id: $this")
            return null
        }
        val poster = optString("poster_path").takeIf { it.isNotBlank() }
            ?.let { "https://image.tmdb.org/t/p/w342$it" }
        val score = optDouble("vote_average", 0.0).let { if (it > 0) Score.from10(it.toString()) else null }

        return if (mediaType == "tv") {
            newTvSeriesSearchResponse(title, "$vidboxMainUrl/tv/$id", TvType.TvSeries) {
                this.posterUrl = poster
                this.score = score
            }
        } else {
            newMovieSearchResponse(title, "$vidboxMainUrl/movie/$id", TvType.Movie) {
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
            newMovieLoadResponse(movie.title, url, TvType.Movie, VidLinkData(id = id).toJson()) {
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
            { invokeVidlink(link.id, link.season, link.episode, callback) },
            { invokeMoviesApi(link.id, link.season, link.episode, callback) },
        )
        return true
    }

    data class VidLinkData(
        val id: Int? = null,
        val season: Int? = null,
        val episode: Int? = null,
    )
}
