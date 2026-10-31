package com.almostott

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
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

// org.json's optString returns the literal "null" for a JSON null (TMDB sends null poster_path etc.).
private fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key)

class AlmostOttProvider : MainAPI() {
    override var mainUrl = "https://almostott.aethoflix.world"
    override var name = "AlmostOTT"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    override val mainPage = mainPageOf(*homeRows.toTypedArray())

    // domains.json lives on master (fetched fresh, not baked into the .cs3) so a domain rotation
    // needs no rebuild; only mainUrl-derived detail URLs depend on it (catalogue is TMDB).
    private val domainResolver = DomainResolver(
        domainsJsonUrl = "https://raw.githubusercontent.com/satishgumudavelli/SatishCloudStreamPlugin/master/domains.json",
        targetName = "almostott",
        fallbackDomain = mainUrl.removePrefix("https://"),
    )

    private fun impliedType(path: String): String? = when {
        path.startsWith("trending/all") -> null // mixed - use each item's media_type
        path.startsWith("tv") || path.startsWith("discover/tv") -> "tv"
        else -> "movie"
    }

    // Mirrors the site's own category rule: ja + Animation(16) = anime.
    private fun JSONObject.isAnime(): Boolean =
        str("original_language") == "ja" &&
            (optJSONArray("genre_ids")?.let { a -> (0 until a.length()).any { a.optInt(it) == 16 } } == true ||
                optJSONArray("genres")?.let { a -> (0 until a.length()).any { a.optJSONObject(it)?.optInt("id") == 16 } } == true)

    private fun JSONObject.toSearchResponse(defaultType: String?): SearchResponse? {
        val mediaType = str("media_type").ifEmpty { defaultType ?: if (has("first_air_date")) "tv" else "movie" }
        if (mediaType != "movie" && mediaType != "tv") return null
        val title = str("title").ifEmpty { str("name") }.ifEmpty { return null }
        val id = optInt("id", -1).takeIf { it != -1 } ?: return null
        val poster = str("poster_path").takeIf { it.isNotBlank() }?.let { "$tmdbImage/w342$it" }
        val score = optDouble("vote_average", 0.0).takeIf { it > 0 }?.let { Score.from10(it.toString()) }
        return if (mediaType == "tv") {
            newTvSeriesSearchResponse(title, "$mainUrl/tv/$id", if (isAnime()) TvType.Anime else TvType.TvSeries) {
                this.posterUrl = poster; this.score = score
            }
        } else {
            newMovieSearchResponse(title, "$mainUrl/movie/$id", TvType.Movie) {
                this.posterUrl = poster; this.score = score
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        mainUrl = domainResolver.resolveMainUrl()
        val r = AlmostOttApi.browse(request.data, page)
        return newHomePageResponse(request.name, r.results.mapNotNull { it.toSearchResponse(impliedType(request.data)) }, hasNext = page < r.totalPages)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        mainUrl = domainResolver.resolveMainUrl()
        return AlmostOttApi.search(query).results.mapNotNull { it.toSearchResponse(null) }
    }

    override suspend fun load(url: String): LoadResponse? {
        mainUrl = domainResolver.resolveMainUrl()
        val id = url.substringAfterLast("/").toIntOrNull() ?: return null
        val isTv = url.contains("/tv/")
        val d = AlmostOttApi.detail(isTv, id) ?: return null
        val title = d.str(if (isTv) "name" else "title").ifEmpty { return null }
        val poster = d.str("poster_path").takeIf { it.isNotBlank() }?.let { "$tmdbImage/w500$it" }
        val backdrop = d.str("backdrop_path").takeIf { it.isNotBlank() }?.let { "$tmdbImage/original$it" }
        val genres = d.optJSONArray("genres")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name") } }
        val year = d.str(if (isTv) "first_air_date" else "release_date").take(4).toIntOrNull()
        val plot = d.str("overview").takeIf { it.isNotBlank() }
        val score = d.optDouble("vote_average", 0.0).takeIf { it > 0 }?.let { Score.from10(it.toString()) }
        val cast = d.optJSONObject("credits")?.optJSONArray("cast")?.let { a ->
            (0 until minOf(a.length(), 10)).mapNotNull { a.optJSONObject(it)?.optString("name") }
        }?.map { ActorData(Actor(it)) }

        if (!isTv) {
            return newMovieLoadResponse(title, url, TvType.Movie, AlmostOttLoadData(id).toJson()) {
                this.posterUrl = poster; this.backgroundPosterUrl = backdrop; this.year = year
                this.plot = plot; this.tags = genres; this.score = score; this.actors = cast
                this.duration = d.optInt("runtime", 0).takeIf { it > 0 }
            }
        }

        val episodes = d.optJSONArray("seasons")?.let { seasons ->
            (0 until seasons.length()).mapNotNull { seasons.optJSONObject(it)?.optInt("season_number", 0) }
                .filter { it >= 1 } // season 0 = specials, the site's picker starts at 1
                .flatMap { s ->
                    AlmostOttApi.seasonEpisodes(id, s).map { e ->
                        val n = e.optInt("episode_number")
                        newEpisode(AlmostOttLoadData(id, s, n).toJson()) {
                            this.name = e.str("name")
                            this.season = s
                            this.episode = n
                            this.posterUrl = e.str("still_path").takeIf { it.isNotBlank() }?.let { "$tmdbImage/w300$it" }
                            this.description = e.str("overview").takeIf { it.isNotBlank() }
                        }
                    }
                }
        } ?: emptyList()

        return newTvSeriesLoadResponse(title, url, if (d.isAnime()) TvType.Anime else TvType.TvSeries, episodes) {
            this.posterUrl = poster; this.backgroundPosterUrl = backdrop; this.year = year
            this.plot = plot; this.tags = genres; this.score = score; this.actors = cast
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val link = parseJson<AlmostOttLoadData>(data)
        // No subtitle source is exposed by the download API (files are muxed MKVs / HLS without
        // sidecar tracks), so subtitleCallback is intentionally unused.
        return AlmostOttExtractor.invoke(link.season == null, link.id, link.season, link.episode, callback)
    }

    data class AlmostOttLoadData(val id: Int, val season: Int? = null, val episode: Int? = null)
}
