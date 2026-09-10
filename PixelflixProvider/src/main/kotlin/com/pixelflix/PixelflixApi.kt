package com.pixelflix

import com.lagradost.cloudstream3.app
import org.json.JSONObject

// domains.json is shared across providers in this repo, keyed by "name" - see VidboxProvider's
// DomainResolver.kt for the design rationale.
const val domainsJsonUrl =
    "https://raw.githubusercontent.com/satishgumudavelli/SatishCloudStreamPlugin/master/domains.json"

const val pixelflixFallbackDomain = "pixelflix.cc"

// pixelflix.cc's own catalog/detail pages (/, /movies, /tv, /title/movie/{id}, /title/tv/{id})
// are server-rendered Next.js pages, not backed by a client-callable API of their own (confirmed
// live in research.md - no JSON endpoint was found, only rendered HTML). Its movie/show ids ARE
// TMDB ids directly (verified live: 278 = The Shawshank Redemption, 1399 = Game of Thrones), so
// this reuses CinemaOsProvider's exact pattern for the same situation: call TMDB directly with an
// already-lifted key rather than scrape pixelflix.cc's own markup with brittle selectors
// (Constitution III - reuse before reinventing; TMDB keys aren't site-specific).
private const val tmdbKey = "ef311eb0b9b07b9c73e9fb0a732cc150"
private const val tmdbApi = "https://api.themoviedb.org/3"

private val tmdbHeaders = mapOf(
    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
)

// Mirrors pixelflix.cc's own homepage rows (research.md Task 1: In Theatres, Top 10 Movies Today,
// Top 10 Series This Week, Trending Now, Studios, Popular Movies, Now Playing, On Air) via TMDB's
// closest equivalent endpoints - the exact curation algorithm behind pixelflix.cc's own rows
// isn't observable (no API of its own), so these are the standard TMDB analogues, not a scrape of
// its internal logic. "Studios" rows use the same TMDB watch-provider ids already live-verified in
// this repo (specs/004-cineapse-provider/data-model.md) for the subset of services pixelflix.cc's
// own "Studios" row actually listed live (Netflix, Disney+, HBO/Max, Prime Video, Hulu,
// Paramount+, Crunchyroll, Peacock).
private const val watchRegion = "US" // TMDB's with_watch_providers filter requires a watch_region
val homeRows = listOf(
    "movie/upcoming" to "In Theatres",
    "movie/now_playing" to "Now Playing",
    "trending/movie/day" to "Top 10 Movies Today",
    "trending/tv/week" to "Top 10 Series This Week",
    "trending/all/day" to "Trending Now",
    "movie/popular" to "Popular Movies",
    "tv/on_the_air" to "On Air",
    "discover/movie?with_watch_providers=8&watch_region=$watchRegion" to "Netflix",
    "discover/movie?with_watch_providers=337&watch_region=$watchRegion" to "Disney Plus",
    "discover/movie?with_watch_providers=1899&watch_region=$watchRegion" to "Max",
    "discover/movie?with_watch_providers=9&watch_region=$watchRegion" to "Prime Video",
    "discover/movie?with_watch_providers=15&watch_region=$watchRegion" to "Hulu",
    "discover/movie?with_watch_providers=531&watch_region=$watchRegion" to "Paramount Plus",
    "discover/movie?with_watch_providers=283&watch_region=$watchRegion" to "Crunchyroll",
    "discover/movie?with_watch_providers=386&watch_region=$watchRegion" to "Peacock",
)

object PixelflixApi {

    val domainResolver = DomainResolver(
        domainsJsonUrl = domainsJsonUrl,
        targetName = "pixelflix",
        fallbackDomain = pixelflixFallbackDomain,
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

    // append_to_response=external_ids,credits: cast list (spec.md FR-004/FR-005) and the imdb id
    // PixelflixExtractor needs for every vidbolt.xyz scrape call - TMDB's base movie response has
    // neither.
    suspend fun movieDetail(id: Int): JSONObject? =
        runCatching {
            JSONObject(app.get("$tmdbApi/movie/$id?api_key=$tmdbKey&append_to_response=external_ids,credits", headers = tmdbHeaders).text)
        }.getOrNull()

    // append_to_response=external_ids,credits: TV's base response has neither imdb_id nor cast.
    suspend fun tvDetail(id: Int): JSONObject? =
        runCatching {
            JSONObject(app.get("$tmdbApi/tv/$id?api_key=$tmdbKey&append_to_response=external_ids,credits", headers = tmdbHeaders).text)
        }.getOrNull()

    suspend fun seasonEpisodes(tvId: Int, seasonNumber: Int): List<JSONObject> {
        val json = app.get("$tmdbApi/tv/$tvId/season/$seasonNumber?api_key=$tmdbKey", headers = tmdbHeaders).text
        val episodes = runCatching { JSONObject(json).optJSONArray("episodes") }.getOrNull() ?: return emptyList()
        return (0 until episodes.length()).mapNotNull { episodes.optJSONObject(it) }
    }

    // Top-billed cast names (data-model.md Title.cast), from a "credits" append_to_response block.
    fun JSONObject.castNames(limit: Int = 10): List<String>? =
        optJSONObject("credits")?.optJSONArray("cast")?.let { arr ->
            (0 until minOf(arr.length(), limit)).mapNotNull { arr.optJSONObject(it)?.optString("name") }
        }?.takeIf { it.isNotEmpty() }
}
