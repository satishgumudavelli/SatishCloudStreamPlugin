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

// Mirrors cinemaos.tech's own homepage sections (Top 10 Movies/Shows, Trending in the UK,
// Streaming Providers, Top Rated, Browse by Genre) via TMDB's equivalent endpoints, since the
// site's own feeds for these aren't exposed as a client-callable API - see the comment on
// tmdbKey above. Row "data" is "path" or "path?extraQueryParams" (fetchList splits on the first
// "?"), matching this repo's other providers rather than TMDB's raw path/query split.
private const val watchRegion = "US" // TMDB's with_watch_providers filter requires a watch_region
val homeRows = listOf(
    "trending/movie/day" to "Top 10 Movies",
    "trending/tv/day" to "Top 10 Shows",
    "discover/movie?region=GB&sort_by=popularity.desc" to "Trending in the UK",
    "discover/movie?with_watch_providers=8&watch_region=$watchRegion" to "Netflix",
    "discover/movie?with_watch_providers=350&watch_region=$watchRegion" to "Apple TV+",
    "discover/movie?with_watch_providers=9&watch_region=$watchRegion" to "Amazon Prime Video",
    "discover/movie?with_watch_providers=15&watch_region=$watchRegion" to "Hulu",
    "discover/movie?with_watch_providers=1899&watch_region=$watchRegion" to "Max",
    "discover/movie?with_watch_providers=531&watch_region=$watchRegion" to "Paramount+",
    "discover/movie?with_watch_providers=337&watch_region=$watchRegion" to "Disney+",
    // Shudder's TMDB watch-provider id wasn't live-verified (TMDB was unreachable from the
    // research sandbox) - double check this against a live /watch/providers/movie response.
    "discover/movie?with_watch_providers=502&watch_region=$watchRegion" to "Shudder",
    "movie/top_rated" to "Top Rated Movies",
    "tv/top_rated" to "Top Rated TV Shows",
    "discover/movie?with_genres=28" to "Action Movies",
    "discover/movie?with_genres=12" to "Adventure Movies",
    "discover/movie?with_genres=16" to "Animation Movies",
    "discover/movie?with_genres=35" to "Comedy Movies",
    "discover/movie?with_genres=80" to "Crime Movies",
    "discover/movie?with_genres=99" to "Documentary Movies",
    "discover/movie?with_genres=18" to "Drama Movies",
    "discover/movie?with_genres=10751" to "Family Movies",
    "discover/movie?with_genres=14" to "Fantasy Movies",
    "discover/movie?with_genres=36" to "History Movies",
    "discover/movie?with_genres=27" to "Horror Movies",
    "discover/movie?with_genres=10402" to "Music Movies",
    "discover/movie?with_genres=9648" to "Mystery Movies",
    "discover/movie?with_genres=10749" to "Romance Movies",
    "discover/movie?with_genres=878" to "Sci-Fi Movies",
    "discover/movie?with_genres=53" to "Thriller Movies",
    "discover/movie?with_genres=10752" to "War Movies",
    "discover/movie?with_genres=37" to "Western Movies",
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
