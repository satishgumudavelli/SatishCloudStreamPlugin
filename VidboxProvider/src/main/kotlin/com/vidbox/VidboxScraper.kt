package com.vidbox

import com.lagradost.cloudstream3.app
import org.json.JSONObject

const val vidboxMainUrl = "https://vidbox.vc"

// vidbox.vc's own TMDB key, lifted from its client bundle (common.*.js) - used only where
// vidbox itself has no server-side endpoint at all (per-season episode lists).
const val vidboxTmdbKey = "ef311eb0b9b07b9c73e9fb0a732cc150"
const val tmdbApi = "https://api.themoviedb.org/3"

// Query params for vidbox's own browse/filter endpoint (/api/search/discover), one per home
// row - these are exactly the "view all" links the site itself uses for each row.
val homeRows = listOf(
    "type=movie&now_playing=true" to "Now Playing",
    "type=movie&trending=true" to "Trending Movies",
    "type=tv&trending=true" to "Trending TV Shows",
    "type=tv&watch_provider=8" to "Netflix Originals",
    "type=tv&watch_provider=9" to "Amazon Prime Shows",
    "type=tv&watch_provider=350" to "Apple TV+ Shows",
    "type=tv&watch_provider=337" to "Disney+ Shows",
    "type=movie&country=IN" to "Indian Movies",
    "type=tv&watch_provider=386" to "Peacock TV Shows",
    "type=tv&watch_provider=1899" to "Max Shows",
)

object VidboxScraper {

    private val rscBlockRegex = Regex("""self\.__next_f\.push\(\[1,"(.*?)"\]\)""", RegexOption.DOT_MATCHES_ALL)

    private fun unescapeJs(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '"' -> { sb.append('"'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    '/' -> { sb.append('/'); i += 2 }
                    'u' -> {
                        val hex = s.substring(i + 2, minOf(i + 6, s.length))
                        sb.append(hex.toInt(16).toChar())
                        i += 6
                    }
                    else -> { sb.append(s[i + 1]); i += 2 }
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /** Fetches [url] and returns its server-rendered React payload, unescaped and concatenated. */
    suspend fun fetchRscText(url: String): String {
        val html = app.get(url).text
        return rscBlockRegex.findAll(html).joinToString("\n") { unescapeJs(it.groupValues[1]) }
    }

    /** Extracts the balanced `[...]` or `{...}` substring starting at index [start] (which must point at `[` or `{`). */
    fun extractBalanced(text: String, start: Int): String {
        var depth = 0
        var inStr = false
        var esc = false
        var i = start
        while (i < text.length) {
            val c = text[i]
            if (inStr) {
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
            } else {
                when (c) {
                    '"' -> inStr = true
                    '[', '{' -> depth++
                    ']', '}' -> {
                        depth--
                        if (depth == 0) return text.substring(start, i + 1)
                    }
                }
            }
            i++
        }
        return text.substring(start)
    }

    data class DiscoverPage(val results: List<JSONObject>, val totalPages: Int)

    /** vidbox's own browse/search/filter API - backs every "view all" row and the search box. */
    suspend fun discover(params: String, page: Int): DiscoverPage {
        val json = runCatching {
            JSONObject(app.get("$vidboxMainUrl/api/search/discover?$params&page=$page").text)
        }.getOrNull() ?: return DiscoverPage(emptyList(), 0)
        val results = json.optJSONArray("results")
        val items = results?.let { (0 until it.length()).mapNotNull { i -> it.optJSONObject(i) } } ?: emptyList()
        return DiscoverPage(items, json.optInt("total_pages", page))
    }

    // Matches the h1 title on a /movie/{id} detail page.
    private val movieTitleRegex = Regex(""""className":"mb-4 text-3xl font-bold md:text-4xl","children":"([^"]+)"""")
    private val movieRatingYearRegex = Regex(
        """fill-yellow-500 text-yellow-500".*?"span",null,\{"children":"([0-9.]+)"}[\]][\]]}[\]],\["\$","span",null,\{"children":"(\d{4})"}""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val movieGenreRegex = Regex(""""span","\d+",\{"className":"rounded-full bg-slate-900[^"]*","children":"([^"]+)"}]""")
    private val movieOverviewRegex = Regex(""""p",null,\{"className":"mb-6 text-lg","children":"([^"]*)"}]""")
    private val posterRegex = Regex("""image\.tmdb\.org%2Ft%2Fp%2Fw342%2F([^&"]+)""")
    private val backdropRegex = Regex("""image\.tmdb\.org%2Ft%2Fp%2Foriginal%2F([^&"]+)""")

    data class MovieDetail(
        val title: String,
        val year: Int?,
        val score: String?,
        val genres: List<String>,
        val overview: String?,
        val posterUrl: String?,
        val backdropUrl: String?,
    )

    suspend fun scrapeMovieDetail(id: Int): MovieDetail? {
        val text = fetchRscText("$vidboxMainUrl/movie/$id")
        val title = movieTitleRegex.find(text)?.groupValues?.get(1) ?: return null
        val ratingYear = movieRatingYearRegex.find(text)
        return MovieDetail(
            title = title,
            year = ratingYear?.groupValues?.get(2)?.toIntOrNull(),
            score = ratingYear?.groupValues?.get(1),
            genres = movieGenreRegex.findAll(text).map { it.groupValues[1] }.distinct().toList(),
            overview = movieOverviewRegex.find(text)?.groupValues?.get(1),
            posterUrl = posterRegex.find(text)?.groupValues?.get(1)?.let { "https://image.tmdb.org/t/p/w342/$it" },
            backdropUrl = backdropRegex.find(text)?.groupValues?.get(1)?.let { "https://image.tmdb.org/t/p/original/$it" },
        )
    }

    /** The /tv/{id} detail page server-renders the full raw TMDB `tv/{id}` response inside a `"props":{...}` blob. */
    suspend fun scrapeTvDetail(id: Int): JSONObject? {
        val text = fetchRscText("$vidboxMainUrl/tv/$id")
        val anchor = "\"props\":{\"adult\""
        val anchorIdx = text.indexOf(anchor)
        if (anchorIdx == -1) return null
        val objStart = anchorIdx + "\"props\":".length
        return runCatching { JSONObject(extractBalanced(text, objStart)) }.getOrNull()
    }

    // vidbox has no endpoint at all for per-season episode lists; it fetches these straight from
    // TMDB in the browser using its own embedded key. Mirroring that exactly (same endpoint, same
    // key) is the only way to get this data without inventing our own TMDB usage.
    suspend fun fetchSeasonEpisodes(tvId: Int, seasonNumber: Int): List<JSONObject> {
        val json = app.get("$tmdbApi/tv/$tvId/season/$seasonNumber?api_key=$vidboxTmdbKey&language=en-US").text
        val episodes = runCatching { JSONObject(json).optJSONArray("episodes") }.getOrNull() ?: return emptyList()
        return (0 until episodes.length()).mapNotNull { episodes.optJSONObject(it) }
    }
}
