package com.cineapse

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
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.json.JSONObject

class CineapseProvider : MainAPI() {
    override var mainUrl = "https://$cineapseFallbackDomain"
    override var name = "Cineapse"
    override val hasMainPage = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    // homeRows (Top 10s + genre/quality rows, FR-002) and providerRows ("Browse by Provider",
    // FR-003) share one MainAPI row list - both are just discover-query rows, no separate
    // wiring needed.
    override val mainPage = mainPageOf(*(homeRows + providerRows).toTypedArray())

    private fun impliedType(path: String) = if (path.startsWith("discover/tv")) "tv" else "movie"

    // Maps a discover/search result to a Movie or TvSeries SearchResponse, dropping any card
    // missing a title or id (FR-008 / data-model.md validation rule).
    private fun JSONObject.toSearchResponse(defaultType: String?): SearchResponse? {
        val mediaType = optString("media_type").ifEmpty { defaultType ?: (if (has("first_air_date")) "tv" else "movie") }
        if (mediaType == "person") return null
        val title = optString("title").ifEmpty { optString("name") }
        if (title.isEmpty()) return null
        val id = optInt("id", -1)
        if (id == -1) return null
        val poster = optString("poster_path").takeIf { it.isNotBlank() }
            ?.let { "https://image.tmdb.org/t/p/w342$it" }
        val score = optDouble("vote_average", 0.0).let { if (it > 0) Score.from10(it.toString()) else null }

        return if (mediaType == "tv") {
            newTvSeriesSearchResponse(title, "$mainUrl/tv/$id", TvType.TvSeries) {
                this.posterUrl = poster
                this.score = score
            }
        } else {
            newMovieSearchResponse(title, "$mainUrl/movie/$id", TvType.Movie) {
                this.posterUrl = poster
                this.score = score
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        mainUrl = CineapseApi.resolveMainUrl()
        val result = CineapseApi.browse(request.data, page)
        val shows = result.results.mapNotNull { it.toSearchResponse(impliedType(request.data)) }
        return newHomePageResponse(request.name, shows, hasNext = page < result.totalPages)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        mainUrl = CineapseApi.resolveMainUrl()
        return CineapseApi.searchMulti(query).results.mapNotNull { it.toSearchResponse(null) }
    }

    override suspend fun load(url: String): LoadResponse? {
        mainUrl = CineapseApi.resolveMainUrl()
        val id = url.substringAfterLast("/").toIntOrNull() ?: return null

        return if (url.contains("/tv/")) {
            val tv = CineapseApi.tvDetail(id) ?: return null
            val title = tv.optString("name").ifEmpty { return null }
            val poster = tv.optString("poster_path").takeIf { it.isNotBlank() }
                ?.let { "https://image.tmdb.org/t/p/w342$it" }
            val backdrop = tv.optString("backdrop_path").takeIf { it.isNotBlank() }
                ?.let { "https://image.tmdb.org/t/p/original$it" }
            val genres = tv.optJSONArray("genres")
                ?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name") } }
            val year = tv.optString("first_air_date").split("-").firstOrNull()?.toIntOrNull()
            val imdbId = tv.optJSONObject("external_ids")?.optString("imdb_id")?.takeIf { it.isNotBlank() }

            val episodes = tv.optJSONArray("seasons")?.let { seasons ->
                (0 until seasons.length()).mapNotNull { i ->
                    val season = seasons.optJSONObject(i) ?: return@mapNotNull null
                    val seasonNumber = season.optInt("season_number", -1)
                    if (seasonNumber < 1) return@mapNotNull null
                    CineapseApi.seasonEpisodes(id, seasonNumber).map { eps ->
                        newEpisode(
                            CineapseLoadData(
                                id = id,
                                imdbId = imdbId,
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
            val movie = CineapseApi.movieDetail(id) ?: return null
            val title = movie.optString("title").ifEmpty { return null }
            val poster = movie.optString("poster_path").takeIf { it.isNotBlank() }
                ?.let { "https://image.tmdb.org/t/p/w342$it" }
            val backdrop = movie.optString("backdrop_path").takeIf { it.isNotBlank() }
                ?.let { "https://image.tmdb.org/t/p/original$it" }
            val genres = movie.optJSONArray("genres")
                ?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name") } }
            val year = movie.optString("release_date").split("-").firstOrNull()?.toIntOrNull()
            val imdbId = movie.optString("imdb_id").takeIf { it.isNotBlank() }

            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                CineapseLoadData(id = id, imdbId = imdbId, title = title, year = year).toJson(),
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = movie.optString("overview").takeIf { it.isNotBlank() }
                this.tags = genres
                this.score = movie.optDouble("vote_average", 0.0).let { if (it > 0) Score.from10(it.toString()) else null }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val link = parseJson<CineapseLoadData>(data)
        val id = link.id ?: return false
        CineapseExtractor.invoke(
            mediaType = if (link.season != null) "tv" else "movie",
            tmdbId = id,
            season = link.season,
            episode = link.episode,
            callback = callback,
        )
        return true
    }

    data class CineapseLoadData(
        val id: Int? = null,
        val imdbId: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val title: String? = null,
        val year: Int? = null,
    )
}
