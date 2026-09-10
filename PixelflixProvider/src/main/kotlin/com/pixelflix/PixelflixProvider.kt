package com.pixelflix

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

class PixelflixProvider : MainAPI() {
    override var mainUrl = "https://$pixelflixFallbackDomain"
    override var name = "Pixelflix"
    override val hasMainPage = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    override val mainPage = mainPageOf(*homeRows.toTypedArray())

    private fun impliedType(path: String): String? = when {
        path.startsWith("tv") || path.startsWith("trending/tv") -> "tv"
        path.startsWith("trending/all") -> null // mixed row - rely on each item's own media_type
        else -> "movie"
    }

    // Maps a discover/search/trending result to a Movie or TvSeries SearchResponse, dropping any
    // card missing a title or id (FR-008 / data-model.md validation rule).
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
        mainUrl = PixelflixApi.resolveMainUrl()
        val result = PixelflixApi.browse(request.data, page)
        val shows = result.results.mapNotNull { it.toSearchResponse(impliedType(request.data)) }
        return newHomePageResponse(request.name, shows, hasNext = page < result.totalPages)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        mainUrl = PixelflixApi.resolveMainUrl()
        return PixelflixApi.searchMulti(query).results.mapNotNull { it.toSearchResponse(null) }
    }

    override suspend fun load(url: String): LoadResponse? {
        mainUrl = PixelflixApi.resolveMainUrl()
        val id = url.substringAfterLast("/").toIntOrNull() ?: return null

        return if (url.contains("/tv/")) {
            val tv = PixelflixApi.tvDetail(id) ?: return null
            val title = tv.optString("name").ifEmpty { return null }
            val poster = tv.optString("poster_path").takeIf { it.isNotBlank() }
                ?.let { "https://image.tmdb.org/t/p/w342$it" }
            val backdrop = tv.optString("backdrop_path").takeIf { it.isNotBlank() }
                ?.let { "https://image.tmdb.org/t/p/original$it" }
            val genres = tv.optJSONArray("genres")
                ?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name") } }
            val year = tv.optString("first_air_date").split("-").firstOrNull()?.toIntOrNull()
            val imdbId = tv.optJSONObject("external_ids")?.optString("imdb_id")?.takeIf { it.isNotBlank() }
            val cast = with(PixelflixApi) { tv.castNames() }

            val episodes = tv.optJSONArray("seasons")?.let { seasons ->
                (0 until seasons.length()).mapNotNull { i ->
                    val season = seasons.optJSONObject(i) ?: return@mapNotNull null
                    val seasonNumber = season.optInt("season_number", -1)
                    if (seasonNumber < 1) return@mapNotNull null
                    PixelflixApi.seasonEpisodes(id, seasonNumber).map { eps ->
                        newEpisode(
                            PixelflixLoadData(
                                id = id,
                                season = seasonNumber,
                                episode = eps.optInt("episode_number"),
                                title = title,
                                year = year,
                                imdbId = imdbId,
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
                this.actors = cast?.map { com.lagradost.cloudstream3.ActorData(com.lagradost.cloudstream3.Actor(it)) }
                this.score = tv.optDouble("vote_average", 0.0).let { if (it > 0) Score.from10(it.toString()) else null }
            }
        } else {
            val movie = PixelflixApi.movieDetail(id) ?: return null
            val title = movie.optString("title").ifEmpty { return null }
            val poster = movie.optString("poster_path").takeIf { it.isNotBlank() }
                ?.let { "https://image.tmdb.org/t/p/w342$it" }
            val backdrop = movie.optString("backdrop_path").takeIf { it.isNotBlank() }
                ?.let { "https://image.tmdb.org/t/p/original$it" }
            val genres = movie.optJSONArray("genres")
                ?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name") } }
            val year = movie.optString("release_date").split("-").firstOrNull()?.toIntOrNull()
            val duration = movie.optInt("runtime", -1).takeIf { it > 0 }
            val cast = with(PixelflixApi) { movie.castNames() }
            val imdbId = movie.optJSONObject("external_ids")?.optString("imdb_id")?.takeIf { it.isNotBlank() }

            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                PixelflixLoadData(id = id, title = title, year = year, imdbId = imdbId).toJson(),
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = movie.optString("overview").takeIf { it.isNotBlank() }
                this.tags = genres
                this.duration = duration
                this.actors = cast?.map { com.lagradost.cloudstream3.ActorData(com.lagradost.cloudstream3.Actor(it)) }
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
        val link = parseJson<PixelflixLoadData>(data)
        val id = link.id ?: return false
        // Pixelflix.cc's own /watch/{movie|tv}/{id} pages embed a third-party player (vidbolt.xyz,
        // via an embed.reelsdownload.online wrapper) whose scrape API is directly callable
        // (Postman-captured 2026-09-10) - no need to render pixelflix.cc's own watch page at all.
        PixelflixExtractor.invoke(id, link.imdbId, isMovie = link.season == null, link.season, link.episode, link.title, link.year, subtitleCallback, callback)
        return true
    }

    data class PixelflixLoadData(
        val id: Int? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val title: String? = null,
        val year: Int? = null,
        val imdbId: String? = null,
    )
}
