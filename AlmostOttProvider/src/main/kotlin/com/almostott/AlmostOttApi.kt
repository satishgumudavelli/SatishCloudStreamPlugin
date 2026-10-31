package com.almostott

import com.lagradost.cloudstream3.app
import org.json.JSONObject
import java.net.URLEncoder

// almostott.aethoflix.world is a client-rendered React SPA whose catalogue is TMDB directly (route
// #/watch/{movie|tv}/{tmdbId}/{category}, see specs/008-almostott-provider/research.md), so the
// provider talks to TMDB with the key already used by the other TMDB-backed providers in this repo.
private const val tmdbKey = "ef311eb0b9b07b9c73e9fb0a732cc150"
private const val tmdbApi = "https://api.themoviedb.org/3"
const val tmdbImage = "https://image.tmdb.org/t/p"

private const val watchRegion = "US"

// Rows mirror the site's own sections: trending/popular, anime (ja + Animation), regional dramas
// (original_language + Drama genre, same rule the site's category() uses), and Hindi cinema since
// the site's server list is Hindi/English.
val homeRows = listOf(
    "trending/all/week" to "Trending This Week",
    "movie/popular" to "Popular Movies",
    "tv/popular" to "Popular TV Shows",
    "movie/top_rated" to "Top Rated Movies",
    "tv/top_rated" to "Top Rated TV Shows",
    "movie/now_playing" to "Now Playing",
    "tv/on_the_air" to "On The Air",
    "discover/tv?with_original_language=ja&with_genres=16&sort_by=popularity.desc" to "Anime",
    "discover/movie?with_original_language=hi&sort_by=popularity.desc" to "Hindi Movies",
    "discover/tv?with_original_language=hi&sort_by=popularity.desc" to "Hindi TV Shows",
    "discover/tv?with_original_language=ko&with_genres=18&sort_by=popularity.desc" to "K-Drama",
    "discover/tv?with_original_language=zh&with_genres=18&sort_by=popularity.desc" to "C-Drama",
    "discover/tv?with_original_language=ja&with_genres=18&sort_by=popularity.desc" to "J-Drama",
    "discover/tv?with_original_language=th&with_genres=18&sort_by=popularity.desc" to "Thai Drama",
    "discover/tv?with_original_language=tr&with_genres=18&sort_by=popularity.desc" to "Turkish Drama",
    "discover/tv?with_original_language=tl&with_genres=18&sort_by=popularity.desc" to "Filipino Drama",
    "discover/movie?with_watch_providers=8&watch_region=$watchRegion&sort_by=popularity.desc" to "Netflix",
    "discover/movie?with_watch_providers=337&watch_region=$watchRegion&sort_by=popularity.desc" to "Disney Plus",
    "discover/movie?with_watch_providers=9&watch_region=$watchRegion&sort_by=popularity.desc" to "Prime Video",
)

object AlmostOttApi {
    data class ListPage(val results: List<JSONObject>, val totalPages: Int)

    private suspend fun getJson(path: String, query: String = ""): JSONObject? =
        runCatching { JSONObject(app.get("$tmdbApi/$path?api_key=$tmdbKey&language=en-US$query").text) }.getOrNull()

    suspend fun browse(path: String, page: Int): ListPage {
        val (base, q) = path.split("?", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val json = getJson(base, "&page=$page" + if (q.isNotEmpty()) "&$q" else "") ?: return ListPage(emptyList(), 0)
        return json.toListPage(page)
    }

    suspend fun search(query: String): ListPage =
        getJson("search/multi", "&include_adult=false&query=${URLEncoder.encode(query, "UTF-8")}")
            ?.toListPage(1) ?: ListPage(emptyList(), 0)

    suspend fun detail(isTv: Boolean, id: Int): JSONObject? =
        getJson("${if (isTv) "tv" else "movie"}/$id", "&append_to_response=external_ids,credits")

    suspend fun seasonEpisodes(tvId: Int, season: Int): List<JSONObject> =
        getJson("tv/$tvId/season/$season")?.optJSONArray("episodes")
            ?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList()

    private fun JSONObject.toListPage(page: Int): ListPage {
        val arr = optJSONArray("results")
        return ListPage(arr?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList(), optInt("total_pages", page))
    }
}
