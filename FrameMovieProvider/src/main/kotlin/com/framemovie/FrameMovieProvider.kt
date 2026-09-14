package com.framemovie

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.HomePageList
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
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

private val shortDramaRow = "drama" to "Short Drama"

class FrameMovieProvider : MainAPI() {
    override var mainUrl = "https://$framemovieFallbackDomain"
    override var name = "FrameMovie"
    override val hasMainPage = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
    )

    override val mainPage = mainPageOf(*(homeRows + shortDramaRow).toTypedArray())

    private fun impliedType(path: String): String? = when {
        path.startsWith("tv") || path.startsWith("discover/tv") -> "tv"
        path.startsWith("trending/all") -> null // mixed row - rely on each item's own media_type
        else -> "movie"
    }

    // Maps a TMDB movie/tv/trending/discover result to a Movie or TvSeries SearchResponse,
    // dropping any card missing a title or id (FR-009 / spec.md edge cases).
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

    // Maps a drama proxy `bookList[]` item to a SearchResponse (data-model.md Title, drama-backed
    // shape) - dropping any card missing a bookId/bookName the same way toSearchResponse does.
    private fun JSONObject.toDramaSearchResponse(): SearchResponse? {
        val bookId = optString("bookId").takeIf { it.isNotBlank() } ?: return null
        val bookName = optString("bookName").takeIf { it.isNotBlank() } ?: return null
        val poster = optString("coverWap").takeIf { it.isNotBlank() }
        // op=detail (loadDrama below) doesn't repeat bookName, so it rides along in the load URL.
        val encodedName = java.net.URLEncoder.encode(bookName, "UTF-8")
        return newTvSeriesSearchResponse(bookName, "$mainUrl/drama/$bookId?name=$encodedName", TvType.AsianDrama) {
            this.posterUrl = poster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        mainUrl = FrameMovieApi.resolveMainUrl()

        if (request.data == "drama") {
            // A single mainPage entry fans out into one HomePageList per drama column
            // (research.md Decision 2) - framemovie's own curated carousels, not paged further.
            val columns = FrameMovieApi.dramaTheater() + FrameMovieApi.dramaComplete()
            val lists = columns.mapNotNull { column ->
                val title = column.optString("title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val books = column.optJSONArray("bookList") ?: return@mapNotNull null
                val shows = (0 until books.length()).mapNotNull { books.optJSONObject(it)?.toDramaSearchResponse() }
                if (shows.isEmpty()) return@mapNotNull null
                HomePageList(title, shows, isHorizontalImages = false)
            }
            return newHomePageResponse(lists, hasNext = false)
        }

        val result = FrameMovieApi.browse(request.data, page)
        val shows = result.results.mapNotNull { it.toSearchResponse(impliedType(request.data)) }
        return newHomePageResponse(request.name, shows, hasNext = page < result.totalPages)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        mainUrl = FrameMovieApi.resolveMainUrl()
        return FrameMovieApi.searchMulti(query).results.mapNotNull { it.toSearchResponse(null) }
    }

    override suspend fun load(url: String): LoadResponse? {
        mainUrl = FrameMovieApi.resolveMainUrl()

        return when {
            url.contains("/drama/") -> loadDrama(url)
            url.contains("/tv/") -> loadTv(url)
            else -> loadMovie(url)
        }
    }

    private suspend fun loadMovie(url: String): LoadResponse? {
        val id = url.substringAfterLast("/").toIntOrNull() ?: return null
        val movie = FrameMovieApi.movieDetail(id) ?: return null
        val title = movie.optString("title").ifEmpty { return null }
        val poster = movie.optString("poster_path").takeIf { it.isNotBlank() }
            ?.let { "https://image.tmdb.org/t/p/w342$it" }
        val backdrop = movie.optString("backdrop_path").takeIf { it.isNotBlank() }
            ?.let { "https://image.tmdb.org/t/p/original$it" }
        val genres = movie.optJSONArray("genres")
            ?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name") } }
        val year = movie.optString("release_date").split("-").firstOrNull()?.toIntOrNull()
        val duration = movie.optInt("runtime", -1).takeIf { it > 0 }
        val cast = with(FrameMovieApi) { movie.castNames() }
        val imdbId = movie.optJSONObject("external_ids")?.optString("imdb_id")?.takeIf { it.isNotBlank() }

        return newMovieLoadResponse(
            title,
            url,
            TvType.Movie,
            FrameMovieLoadData(id = id, title = title, year = year, imdbId = imdbId).toJson(),
        ) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backdrop
            this.year = year
            this.plot = movie.optString("overview").takeIf { it.isNotBlank() }
            this.tags = genres
            this.duration = duration
            this.actors = cast?.map { ActorData(Actor(it)) }
            this.score = movie.optDouble("vote_average", 0.0).let { if (it > 0) Score.from10(it.toString()) else null }
        }
    }

    private suspend fun loadTv(url: String): LoadResponse? {
        val id = url.substringAfterLast("/").toIntOrNull() ?: return null
        val tv = FrameMovieApi.tvDetail(id) ?: return null
        val title = tv.optString("name").ifEmpty { return null }
        val poster = tv.optString("poster_path").takeIf { it.isNotBlank() }
            ?.let { "https://image.tmdb.org/t/p/w342$it" }
        val backdrop = tv.optString("backdrop_path").takeIf { it.isNotBlank() }
            ?.let { "https://image.tmdb.org/t/p/original$it" }
        val genres = tv.optJSONArray("genres")
            ?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name") } }
        val year = tv.optString("first_air_date").split("-").firstOrNull()?.toIntOrNull()
        val imdbId = tv.optJSONObject("external_ids")?.optString("imdb_id")?.takeIf { it.isNotBlank() }
        val cast = with(FrameMovieApi) { tv.castNames() }

        val episodes = tv.optJSONArray("seasons")?.let { seasons ->
            (0 until seasons.length()).mapNotNull { i ->
                val season = seasons.optJSONObject(i) ?: return@mapNotNull null
                val seasonNumber = season.optInt("season_number", -1)
                if (seasonNumber < 1) return@mapNotNull null
                FrameMovieApi.seasonEpisodes(id, seasonNumber).map { eps ->
                    newEpisode(
                        FrameMovieLoadData(
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

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backdrop
            this.year = year
            this.plot = tv.optString("overview").takeIf { it.isNotBlank() }
            this.tags = genres
            this.actors = cast?.map { ActorData(Actor(it)) }
            this.score = tv.optDouble("vote_average", 0.0).let { if (it > 0) Score.from10(it.toString()) else null }
        }
    }

    // Short Drama detail: `op=detail`'s chapter `list[]` (research.md Decision 2 / contracts -
    // field name `list`, live-confirmed, each item `{chapterId, chapterIndex, isCharge, isPay,
    // chapterSizeVoList}`) becomes one Episode per chapter, addressed by 0-based chapterIndex.
    private suspend fun loadDrama(url: String): LoadResponse? {
        val path = url.substringAfterLast("/")
        val bookId = path.substringBefore("?").takeIf { it.isNotBlank() } ?: return null
        val detail = FrameMovieApi.dramaDetail(bookId) ?: return null
        val chapters = detail.optJSONArray("list") ?: return null
        // op=detail doesn't repeat bookName (only bookStatus/chapterCount/etc.) - it rides along
        // in the URL's own "name" query param instead (set in toDramaSearchResponse above).
        val title = path.substringAfter("name=", "").takeIf { it.isNotBlank() }
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: "Short Drama"

        val episodes = (0 until chapters.length()).mapNotNull { i ->
            val chapter = chapters.optJSONObject(i) ?: return@mapNotNull null
            val chapterIndex = chapter.optInt("chapterIndex", -1)
            if (chapterIndex < 0) return@mapNotNull null
            newEpisode(
                FrameMovieLoadData(bookId = bookId, chapterIndex = chapterIndex).toJson()
            ) {
                this.name = "Episode ${chapterIndex + 1}"
                this.episode = chapterIndex + 1
            }
        }
        if (episodes.isEmpty()) return null

        return newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes)
    }

    // Short Drama playback: `op=stream` returns already-signed CDN URLs directly, no
    // extractor/crypto needed (research.md Decision 2/3) - `vip`/`isCharge` entries are skipped
    // per data-model.md's exclusion rule (a payment-gated link isn't a working source).
    private suspend fun loadDramaLinks(bookId: String, chapterIndex: Int, callback: (ExtractorLink) -> Unit): Boolean {
        val stream = FrameMovieApi.dramaStream(bookId, chapterIndex) ?: return false
        if (stream.optBoolean("isCharge", false)) return false
        val qualities = stream.optJSONArray("qualities") ?: return false

        var found = false
        for (i in 0 until qualities.length()) {
            val q = qualities.optJSONObject(i) ?: continue
            if (q.optBoolean("vip", false)) continue
            val videoUrl = q.optString("url").takeIf { it.isNotBlank() } ?: continue
            val quality = q.optInt("quality", 0)
            callback(
                newExtractorLink("FrameMovie", "FrameMovie ${quality}p", videoUrl, ExtractorLinkType.VIDEO) {
                    if (quality > 0) this.quality = quality
                }
            )
            found = true
        }
        return found
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        mainUrl = FrameMovieApi.resolveMainUrl()
        val link = parseJson<FrameMovieLoadData>(data)

        if (link.bookId != null && link.chapterIndex != null) {
            return loadDramaLinks(link.bookId, link.chapterIndex, callback)
        }

        val id = link.id ?: return false
        runAllAsync(
            { FrameMovieExtractor.invokeVidRock(id, link.season, link.episode, callback) },
            { FrameMovieExtractor.invokePeachify(id, link.season, link.episode, subtitleCallback, callback) },
        )
        return true
    }

    data class FrameMovieLoadData(
        val id: Int? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val title: String? = null,
        val year: Int? = null,
        val imdbId: String? = null,
        val bookId: String? = null,
        val chapterIndex: Int? = null,
    )
}
