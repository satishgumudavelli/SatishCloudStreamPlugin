package com.cinemaos

import com.lagradost.cloudstream3.app
import org.json.JSONObject

// domains.json is shared across providers in this repo, keyed by "name" - see VidboxProvider's
// DomainResolver.kt for the design rationale.
const val domainsJsonUrl =
    "https://raw.githubusercontent.com/satishgumudavelli/SatishCloudStreamPlugin/master/domains.json"

const val cinemaosFallbackDomain = "cinemaos.tech"

// cinemaos.tech/.in/.live don't expose a client-callable TMDB key (their own catalog pages are
// server-rendered, not backed by a public API) - this reuses the TMDB key already lifted from
// vidbox.vc's client bundle elsewhere in this repo. TMDB keys aren't site-specific, so this is
// just "a working TMDB API key", not a vidbox-specific dependency.
private const val tmdbKey = "ef311eb0b9b07b9c73e9fb0a732cc150"
private const val tmdbApi = "https://api.themoviedb.org/3"

private val tmdbHeaders = mapOf(
    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
)

// TMDB's own well-known browse buckets - one per home row.
val homeRows = listOf(
    "trending/movie/week" to "Trending Movies",
    "trending/tv/week" to "Trending TV Shows",
    "movie/now_playing" to "Now Playing",
    "movie/popular" to "Popular Movies",
    "tv/popular" to "Popular TV Shows",
    "movie/top_rated" to "Top Rated Movies",
    "tv/top_rated" to "Top Rated TV Shows",
    "tv/on_the_air" to "On The Air",
)

object CinemaOsScraper {

    val domainResolver = DomainResolver(
        domainsJsonUrl = domainsJsonUrl,
        targetName = "cinemaos",
        fallbackDomain = cinemaosFallbackDomain,
        headers = tmdbHeaders,
    )

    suspend fun resolveMainUrl(): String = domainResolver.resolveMainUrl()

    data class ListPage(val results: List<JSONObject>, val totalPages: Int)

    private suspend fun fetchList(path: String, page: Int, extraParams: String = ""): ListPage {
        val url = "$tmdbApi/$path?api_key=$tmdbKey&page=$page$extraParams"
        val json = runCatching { JSONObject(app.get(url, headers = tmdbHeaders).text) }.getOrElse {
            return ListPage(emptyList(), 0)
        }
        val results = json.optJSONArray("results")
        val items = results?.let { (0 until it.length()).mapNotNull { i -> it.optJSONObject(i) } } ?: emptyList()
        return ListPage(items, json.optInt("total_pages", page))
    }

    suspend fun browse(path: String, page: Int): ListPage = fetchList(path, page)

    suspend fun searchMulti(query: String): ListPage = fetchList("search/multi", 1, "&query=${java.net.URLEncoder.encode(query, "UTF-8")}")

    suspend fun movieDetail(id: Int): JSONObject? =
        runCatching { JSONObject(app.get("$tmdbApi/movie/$id?api_key=$tmdbKey", headers = tmdbHeaders).text) }.getOrNull()

    suspend fun tvDetail(id: Int): JSONObject? =
        runCatching { JSONObject(app.get("$tmdbApi/tv/$id?api_key=$tmdbKey", headers = tmdbHeaders).text) }.getOrNull()

    suspend fun seasonEpisodes(tvId: Int, seasonNumber: Int): List<JSONObject> {
        val json = app.get("$tmdbApi/tv/$tvId/season/$seasonNumber?api_key=$tmdbKey", headers = tmdbHeaders).text
        val episodes = runCatching { JSONObject(json).optJSONArray("episodes") }.getOrNull() ?: return emptyList()
        return (0 until episodes.length()).mapNotNull { episodes.optJSONObject(it) }
    }
}
