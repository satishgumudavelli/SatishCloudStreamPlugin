package com.framemovie

import com.lagradost.cloudstream3.app
import org.json.JSONObject

// domains.json is shared across providers in this repo, keyed by "name" - see PixelflixProvider's
// DomainResolver.kt for the design rationale.
const val domainsJsonUrl =
    "https://raw.githubusercontent.com/satishgumudavelli/SatishCloudStreamPlugin/master/domains.json"

const val framemovieFallbackDomain = "framemovie.online"

// framemovie.online is a client-rendered SPA with no catalogue API of its own for Movie/TV/
// Trending/Discover - live Playwright network capture (specs/006-framemovie-provider/research.md
// Decision 1) shows every one of those rows calling api.themoviedb.org directly from the browser
// with this embedded key, same pattern PixelflixApi.kt already uses for pixelflix.cc.
private const val tmdbKey = "ace01b69b64307f4db8fb4c9657764fa"
private const val tmdbApi = "https://api.themoviedb.org/3"

private val tmdbHeaders = mapOf(
    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
)

// Mirrors framemovie.online's own Movie/TV/Trending/Discover sections (research.md Decision 1,
// live-captured TMDB paths) - PixelflixApi.kt's homeRows shape.
val homeRows = listOf(
    "trending/all/day" to "Trending Now",
    "trending/all/week" to "Trending This Week",
    "trending/movie/day" to "Top 10 Movies Today",
    "movie/now_playing" to "Now Playing",
    "movie/popular" to "Popular Movies",
    "movie/top_rated" to "Top Rated Movies",
    "movie/upcoming" to "Upcoming Movies",
    "tv/popular" to "Popular TV Shows",
    "tv/airing_today" to "Airing Today",
    "tv/top_rated" to "Top Rated TV Shows",
    "discover/movie?sort_by=popularity.desc&include_adult=false" to "Discover Movies",
    "discover/tv?sort_by=popularity.desc&include_adult=false" to "Discover TV Shows",
)

object FrameMovieApi {

    val domainResolver = DomainResolver(
        domainsJsonUrl = domainsJsonUrl,
        targetName = "framemovie",
        fallbackDomain = framemovieFallbackDomain,
    )

    suspend fun resolveMainUrl(): String = domainResolver.resolveMainUrl()

    data class ListPage(val results: List<JSONObject>, val totalPages: Int)

    private suspend fun fetchList(path: String, page: Int, extraParams: String = ""): ListPage {
        val (basePath, query) = path.split("?", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val url = "$tmdbApi/$basePath?api_key=$tmdbKey&page=$page" +
            (if (query.isNotEmpty()) "&$query" else "") + extraParams
        val json = runCatching { JSONObject(app.get(url, headers = tmdbHeaders).text) }.getOrElse {
            return ListPage(emptyList(), 0)
        }
        val results = json.optJSONArray("results")
        val items = results?.let { (0 until it.length()).mapNotNull { i -> it.optJSONObject(i) } } ?: emptyList()
        return ListPage(items, json.optInt("total_pages", page))
    }

    suspend fun browse(path: String, page: Int): ListPage = fetchList(path, page)

    suspend fun searchMulti(query: String): ListPage =
        fetchList("search/multi", 1, "&query=${java.net.URLEncoder.encode(query, "UTF-8")}")

    // external_ids: the imdb id FrameMovieExtractor needs for VidRock/Peachify calls isn't in
    // framemovie's own live-captured append_to_response (contracts/tmdb-catalogue.md) - added here
    // the same way PixelflixApi.kt already does, since VidRock/Peachify need it regardless of what
    // framemovie's own frontend happens to request.
    suspend fun movieDetail(id: Int): JSONObject? =
        runCatching {
            JSONObject(app.get("$tmdbApi/movie/$id?api_key=$tmdbKey&append_to_response=external_ids,credits", headers = tmdbHeaders).text)
        }.getOrNull()

    suspend fun tvDetail(id: Int): JSONObject? =
        runCatching {
            JSONObject(app.get("$tmdbApi/tv/$id?api_key=$tmdbKey&append_to_response=external_ids,credits", headers = tmdbHeaders).text)
        }.getOrNull()

    suspend fun seasonEpisodes(tvId: Int, seasonNumber: Int): List<JSONObject> {
        val json = app.get("$tmdbApi/tv/$tvId/season/$seasonNumber?api_key=$tmdbKey", headers = tmdbHeaders).text
        val episodes = runCatching { JSONObject(json).optJSONArray("episodes") }.getOrNull() ?: return emptyList()
        return (0 until episodes.length()).mapNotNull { episodes.optJSONObject(it) }
    }

    fun JSONObject.castNames(limit: Int = 10): List<String>? =
        optJSONObject("credits")?.optJSONArray("cast")?.let { arr ->
            (0 until minOf(arr.length(), limit)).mapNotNull { arr.optJSONObject(it)?.optString("name") }
        }?.takeIf { it.isNotEmpty() }

    // ---------------------------------------------------------------------------------------
    // Short Drama - the ONE section framemovie.online serves from its own backend (research.md
    // Decision 2): a PHP proxy in front of a DramaBox-schema service, returning already-signed
    // CDN video URLs directly - no token/crypto exchange needed, unlike Movie/TV.
    // ---------------------------------------------------------------------------------------
    private suspend fun dramaGet(query: String): JSONObject? {
        val base = resolveMainUrl()
        val json = runCatching { JSONObject(app.get("$base/drama/index.php?$query").text) }.getOrNull()
            ?: return null
        return if (json.optBoolean("ok", false)) json.optJSONObject("data") else null
    }

    /** `columnVoList[]` rows for the drama home page's featured/curated carousels. */
    suspend fun dramaTheater(): List<JSONObject> =
        dramaGet("op=theater")?.optJSONArray("columnVoList")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        } ?: emptyList()

    /** The "All complete series" row - same envelope shape as `theater`, per research.md Decision 2. */
    suspend fun dramaComplete(): List<JSONObject> =
        dramaGet("op=complete")?.optJSONArray("columnVoList")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        } ?: emptyList()

    suspend fun dramaDetail(bookId: String): JSONObject? = dramaGet("op=detail&bookId=$bookId&boundary=1")

    /** `qualities[]` entries with `vip: true`, and any response with `isCharge: true`, are the
     * caller's responsibility to skip (data-model.md's exclusion rule) - this just returns the
     * raw chapter payload. */
    suspend fun dramaStream(bookId: String, chapterIndex: Int): JSONObject? =
        dramaGet("op=stream&bookId=$bookId&chapter=$chapterIndex")
}
