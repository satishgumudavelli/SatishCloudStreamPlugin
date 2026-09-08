package com.cineapse

import com.lagradost.cloudstream3.app
import org.json.JSONObject
import java.net.URLEncoder

// domains.json is shared across providers in this repo, keyed by "name" - see VidboxProvider's
// DomainResolver.kt for the design rationale.
const val domainsJsonUrl =
    "https://raw.githubusercontent.com/satishgumudavelli/SatishCloudStreamPlugin/master/domains.json"

const val cineapseFallbackDomain = "cineapse.net"

private val browserHeaders = mapOf(
    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
)

// cineapse.net's own /tmdb/* path is a thin, unauthenticated proxy in front of TMDB - unlike
// cinemaos.tech (see CinemaOsScraper), it needs no lifted API key, it's client-callable as-is
// (live-verified: specs/004-cineapse-provider/research.md Task 1).
//
// The row list below mirrors the site's own homepage sections captured from a live production
// session (specs/004-cineapse-provider/research.md Task 2) - it isn't exposed by any
// discoverable "homepage config" endpoint, so this fixed list is the source of truth. Row
// "data" is "path" or "path?query" (fetchList splits on the first "?"), matching this repo's
// other providers' convention rather than TMDB's raw path/query split.
val homeRows = listOf(
    "discover/movie?watch_region=US&with_origin_country=US&sort_by=popularity.desc" to "Top 10 Movies in the U.S. Today",
    "discover/tv?watch_region=US&with_origin_country=US&sort_by=popularity.desc" to "Top 10 TV Shows in the U.S. Today",
    "discover/movie?with_genres=878&sort_by=vote_average.desc&vote_count.gte=1000" to "Top-Rated Sci-Fi",
    "discover/movie?with_genres=53&sort_by=popularity.desc" to "Edge of Your Seat Thrillers",
    "discover/movie?with_genres=16&sort_by=vote_average.desc&vote_count.gte=2000" to "Best Motion Picture - Animated",
    "discover/tv?with_genres=10765&watch_region=US&sort_by=popularity.desc" to "Sci-Fi & Fantasy Series",
    "discover/tv?with_keywords=11322&watch_region=US&sort_by=popularity.desc" to "Heartwarming Romance",
    "discover/tv?with_keywords=11322&watch_region=US&sort_by=popularity.desc" to "Women Who Rule The Screen",
    "discover/movie?with_genres=27&sort_by=vote_average.desc&vote_count.gte=1000" to "Horror Classics",
    "discover/movie?with_genres=35|10402&sort_by=vote_average.desc&vote_count.gte=2000" to "Best Motion Picture - Musical or Comedy",
    "discover/movie?with_genres=35&sort_by=popularity.desc" to "Comedy Movies",
    "discover/tv?with_genres=18&sort_by=vote_average.desc&vote_count.gte=1500" to "Best Television Series - Drama",
    "discover/tv?with_genres=18&watch_region=US&sort_by=popularity.desc" to "Binge-Worthy TV Dramas",
    "discover/tv?with_genres=18,9648&watch_region=US&sort_by=popularity.desc" to "Bingeworthy Suspenseful TV Shows",
    "discover/movie?with_genres=10751&sort_by=popularity.desc" to "Family Movie Night",
    "discover/movie?with_genres=14&sort_by=popularity.desc" to "Epic Fantasy Worlds",
    "discover/movie?with_genres=9648,80&sort_by=popularity.desc" to "Need a Good Laugh?",
    "discover/movie?with_genres=35&sort_by=popularity.desc&vote_average.gte=6" to "Best Motion Picture - Drama",
    "discover/movie?with_genres=18&sort_by=vote_average.desc&vote_count.gte=3000" to "Feel All the Feels (Movies)",
    "discover/tv?with_genres=18&with_keywords=10683|10235&watch_region=US&sort_by=popularity.desc" to "Feel All the Feels (TV Shows)",
    "discover/tv?with_genres=10765|10759&with_keywords=2343|612|1422&watch_region=US&sort_by=popularity.desc" to "Water, Earth, Fire, Air",
    "discover/movie?with_genres=18&sort_by=vote_average.desc&vote_count.gte=1000" to "Critically Acclaimed Dramas",
    "discover/movie?without_original_language=en&sort_by=vote_average.desc&vote_count.gte=1000" to "Best Non-English Language Film",
    "discover/tv?with_genres=18&with_keywords=6270|296608&watch_region=US&sort_by=popularity.desc" to "Teen Angsty Drama",
    "discover/tv?with_genres=35|10402&sort_by=vote_average.desc&vote_count.gte=1000" to "Best Television Series - Musical or Comedy",
    "discover/tv?with_genres=35&watch_region=US&sort_by=popularity.desc" to "TV Comedies",
)

// "Browse by Provider" - a Movies row and a Series row per streaming service (28 rows total),
// mirroring the site's own per-provider Movies/Series toggle (confirmed live on
// cineapse.net/provider/hulu: the Series tab fires discover/tv, not discover/movie). All 14
// watch-provider ids live-verified against
// GET /tmdb/discover/movie?with_watch_providers=<id>&watch_region=US (research.md Task 3).
private const val watchRegion = "US"
val providerRows = listOf(
    8 to "Netflix",
    9 to "Prime Video",
    337 to "Disney Plus",
    350 to "Apple TV+",
    15 to "Hulu",
    1899 to "Max",
    531 to "Paramount Plus",
    386 to "Peacock Premium",
    283 to "Crunchyroll",
    43 to "Starz",
    526 to "AMC+",
    34 to "MGM Plus",
    188 to "YouTube Premium",
    73 to "Tubi TV",
).flatMap { (id, name) ->
    listOf(
        "discover/movie?with_watch_providers=$id&watch_region=$watchRegion" to "$name Movies",
        "discover/tv?with_watch_providers=$id&watch_region=$watchRegion" to "$name Series",
    )
}

object CineapseApi {

    private val domainResolver = DomainResolver(
        domainsJsonUrl = domainsJsonUrl,
        targetName = "cineapse",
        fallbackDomain = cineapseFallbackDomain,
        headers = browserHeaders,
    )

    suspend fun resolveMainUrl(): String = domainResolver.resolveMainUrl()

    data class ListPage(val results: List<JSONObject>, val totalPages: Int)

    private suspend fun fetchList(path: String, page: Int): ListPage {
        val base = resolveMainUrl()
        val (basePath, query) = path.split("?", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val url = "$base/tmdb/$basePath?page=$page" + (if (query.isNotEmpty()) "&$query" else "")
        val json = runCatching {
            JSONObject(app.get(url, headers = browserHeaders + ("Referer" to "$base/")).text)
        }.getOrElse { return ListPage(emptyList(), 0) }
        val results = json.optJSONArray("results")
        val items = results?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it) } } ?: emptyList()
        return ListPage(items, json.optInt("total_pages", page))
    }

    suspend fun browse(path: String, page: Int): ListPage = fetchList(path, page)

    suspend fun searchMulti(query: String): ListPage =
        fetchList("search/multi?query=${URLEncoder.encode(query, "UTF-8")}", 1)

    suspend fun movieDetail(id: Int): JSONObject? {
        val base = resolveMainUrl()
        return runCatching {
            JSONObject(app.get("$base/tmdb/movie/$id", headers = browserHeaders + ("Referer" to "$base/")).text)
        }.getOrNull()
    }

    // append_to_response=external_ids: TV's base response has no imdb_id field otherwise.
    suspend fun tvDetail(id: Int): JSONObject? {
        val base = resolveMainUrl()
        return runCatching {
            JSONObject(
                app.get(
                    "$base/tmdb/tv/$id?append_to_response=external_ids",
                    headers = browserHeaders + ("Referer" to "$base/"),
                ).text
            )
        }.getOrNull()
    }

    suspend fun seasonEpisodes(tvId: Int, seasonNumber: Int): List<JSONObject> {
        val base = resolveMainUrl()
        val json = runCatching {
            app.get("$base/tmdb/tv/$tvId/season/$seasonNumber", headers = browserHeaders + ("Referer" to "$base/")).text
        }.getOrNull() ?: return emptyList()
        val episodes = runCatching { JSONObject(json).optJSONArray("episodes") }.getOrNull() ?: return emptyList()
        return (0 until episodes.length()).mapNotNull { episodes.optJSONObject(it) }
    }
}
