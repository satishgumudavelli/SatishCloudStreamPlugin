package com.pixelflix

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

private const val TAG = "PixelflixExtractor"

// Real call chain, live-captured via Postman/DevTools 2026-09-10 (user-supplied
// Pixelflix.postman_collection.json) - replaces the earlier embed.reelsdownload.online/api/*-extract
// guess, which is no longer what the site actually calls. pixelflix.cc's watch page embeds
// embed.reelsdownload.online/player/{tmdbId}, which itself loads the real player at vidbolt.xyz.
// vidbolt.xyz's frontend fans a single title out to ~9 independent scraper backends through its own
// same-origin passthrough (/api/proxy or /api/scraper, both just forward "path" server-side, adding
// whatever auth the backend needs so the browser never sees an API key) at
// /scrape/{Provider}/{movie|tv}/{imdbId}?tmdbId=...&title=...&year=... - every provider needs the
// imdb id as a path segment even when it's not repeated in the query.
// No response body was captured (Postman only saved the requests), so the exact JSON shape of a
// scrape response is unknown. What IS known from the captured follow-up requests: the m3u8 links
// vidbolt.xyz actually fetches are already fully self-contained CDN-proxy URLs
// (scraper.vidbolt.xyz/proxy/m3u8/{base64 origin url}?headers={referer/origin/UA as JSON} or
// wormhole.filmu.in/proxy/m3u8?url=...&headers=...) - so rather than guess field names, every
// provider's raw response text is scanned for m3u8 URLs directly. That works regardless of the
// envelope shape and needs no extra Referer/Origin from us, since it's already baked into the
// matched URL's own query string.
object PixelflixExtractor {

    private const val vidboltBase = "https://vidbolt.xyz/api"

    private val m3u8Regex = Regex("""https?://[^\s"'\\]+?\.m3u8[^\s"'\\]*""")

    private suspend fun directM3u8Link(source: String, streamUrl: String): ExtractorLink =
        newExtractorLink(source, source, streamUrl, ExtractorLinkType.M3U8) {
            this.quality = Qualities.Unknown.value
        }

    // Shared plumbing every invokeXxx below calls into - the only part that isn't provider-specific.
    // tv path/season/episode params are untested (the capture only covers a movie) - ponytail: mirror
    // the movie shape 1:1 until a TV capture confirms the real query names.
    private suspend fun scrapeProvider(
        provider: String,
        imdbId: String,
        isMovie: Boolean,
        season: Int?,
        episode: Int?,
        query: String,
        viaScraperEndpoint: Boolean = false,
        callback: (ExtractorLink) -> Unit,
    ) {
        val type = if (isMovie) "movie" else "tv"
        val scrapePath = "/scrape/$provider/$type/$imdbId?$query" +
            (if (!isMovie) "&season=${season ?: 1}&episode=${episode ?: 1}" else "")
        val endpoint = if (viaScraperEndpoint) "scraper" else "proxy"
        val url = "$vidboltBase/$endpoint?path=" + URLEncoder.encode(scrapePath, "UTF-8")

        val body = runCatching { app.get(url).text }.getOrElse {
            Log.e(TAG, "$provider scrape failed for imdb_id=$imdbId: ${it.message}")
            return
        }
        m3u8Regex.findAll(body).map { it.value }.distinct().forEach { streamUrl ->
            runCatching { directM3u8Link(provider, streamUrl) }.getOrNull()?.let(callback)
        }
    }

    private fun titleYear(title: String?, year: Int?) = buildString {
        title?.let { append("&title=").append(URLEncoder.encode(it, "UTF-8")) }
        year?.let { append("&year=").append(it) }
    }

    suspend fun invokeFastVa(imdbId: String, tmdbId: Int, isMovie: Boolean, season: Int?, episode: Int?, callback: (ExtractorLink) -> Unit) =
        scrapeProvider("FastVa", imdbId, isMovie, season, episode, "tmdbId=$tmdbId&imdbId=$imdbId", viaScraperEndpoint = true, callback = callback)

    suspend fun invokeQuasar(imdbId: String, tmdbId: Int, isMovie: Boolean, season: Int?, episode: Int?, callback: (ExtractorLink) -> Unit) =
        scrapeProvider("Quasar", imdbId, isMovie, season, episode, "tmdbId=$tmdbId", viaScraperEndpoint = true, callback = callback)

    suspend fun invokeSaffron(imdbId: String, tmdbId: Int, isMovie: Boolean, season: Int?, episode: Int?, title: String?, year: Int?, callback: (ExtractorLink) -> Unit) =
        scrapeProvider("Saffron", imdbId, isMovie, season, episode, "tmdbId=$tmdbId&imdbId=$imdbId${titleYear(title, year)}", viaScraperEndpoint = true, callback = callback)

    suspend fun invokeNova(imdbId: String, tmdbId: Int, isMovie: Boolean, season: Int?, episode: Int?, callback: (ExtractorLink) -> Unit) =
        scrapeProvider("Nova", imdbId, isMovie, season, episode, "tmdbId=$tmdbId", viaScraperEndpoint = true, callback = callback)

    suspend fun invokeVidRock(imdbId: String, tmdbId: Int, isMovie: Boolean, season: Int?, episode: Int?, title: String?, year: Int?, callback: (ExtractorLink) -> Unit) =
        scrapeProvider("VidRock", imdbId, isMovie, season, episode, "tmdbId=$tmdbId${titleYear(title, year)}", callback = callback)

    suspend fun invokeCineStream(imdbId: String, tmdbId: Int, isMovie: Boolean, season: Int?, episode: Int?, title: String?, year: Int?, callback: (ExtractorLink) -> Unit) =
        scrapeProvider("CineStream", imdbId, isMovie, season, episode, "tmdbId=$tmdbId${titleYear(title, year)}", callback = callback)

    suspend fun invokeFlaxmovies(imdbId: String, tmdbId: Int, isMovie: Boolean, season: Int?, episode: Int?, title: String?, year: Int?, callback: (ExtractorLink) -> Unit) =
        scrapeProvider("Flaxmovies", imdbId, isMovie, season, episode, "tmdbId=$tmdbId${titleYear(title, year)}", callback = callback)

    suspend fun invokeFSonic(imdbId: String, tmdbId: Int, isMovie: Boolean, season: Int?, episode: Int?, title: String?, year: Int?, callback: (ExtractorLink) -> Unit) =
        scrapeProvider("FSonic", imdbId, isMovie, season, episode, "tmdbId=$tmdbId${titleYear(title, year)}", callback = callback)

    suspend fun invoke4KHDHub(imdbId: String, tmdbId: Int, isMovie: Boolean, season: Int?, episode: Int?, title: String?, year: Int?, callback: (ExtractorLink) -> Unit) =
        scrapeProvider("4KHDHub", imdbId, isMovie, season, episode, "tmdbId=$tmdbId${titleYear(title, year)}", callback = callback)

    // 10th provider, added from a second capture (Pixel server 2.postman_collection.json) -
    // resolved a raw movie.streamrip.fun m3u8 with no referer/auth needed, already covered by the
    // regex scan in scrapeProvider with no extra handling.
    suspend fun invokeNinetta(imdbId: String, tmdbId: Int, isMovie: Boolean, season: Int?, episode: Int?, title: String?, year: Int?, callback: (ExtractorLink) -> Unit) =
        scrapeProvider("Ninetta", imdbId, isMovie, season, episode, "tmdbId=$tmdbId${titleYear(title, year)}", viaScraperEndpoint = true, callback = callback)

    suspend fun invoke(
        tmdbId: Int,
        imdbId: String?,
        isMovie: Boolean,
        season: Int?,
        episode: Int?,
        title: String?,
        year: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        if (imdbId.isNullOrBlank()) {
            Log.i(TAG, "No imdb id for tmdb_id=$tmdbId - every vidbolt.xyz provider keys off it, skipping")
            return
        }
        listOf<suspend () -> Unit>(
            { invokeFastVa(imdbId, tmdbId, isMovie, season, episode, callback) },
            { invokeQuasar(imdbId, tmdbId, isMovie, season, episode, callback) },
            { invokeSaffron(imdbId, tmdbId, isMovie, season, episode, title, year, callback) },
            { invokeNova(imdbId, tmdbId, isMovie, season, episode, callback) },
            { invokeVidRock(imdbId, tmdbId, isMovie, season, episode, title, year, callback) },
            { invokeCineStream(imdbId, tmdbId, isMovie, season, episode, title, year, callback) },
            { invokeFlaxmovies(imdbId, tmdbId, isMovie, season, episode, title, year, callback) },
            { invokeFSonic(imdbId, tmdbId, isMovie, season, episode, title, year, callback) },
            { invoke4KHDHub(imdbId, tmdbId, isMovie, season, episode, title, year, callback) },
            { invokeNinetta(imdbId, tmdbId, isMovie, season, episode, title, year, callback) },
        ).amap { it() }
    }
}
