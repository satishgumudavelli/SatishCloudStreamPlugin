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

// Two independent embed backends observed live for the same pixelflix.cc watch page (captured on
// separate loads, 2026-09-10): embed.reelsdownload.online/player/{tmdbId} sometimes redirects to
// vidbolt.xyz (which then fans out to the ~10 scrapers below this comment), and sometimes serves
// its own native extract APIs directly (redflix-extract/cs3-extract/cinemaos-extract/moviebox,
// further down) without ever touching vidbolt.xyz. Since either can show up, both are queried.
// No response body was captured for any of these (Postman only saved the requests), so none of the
// JSON shapes are known - every provider's raw response text is scanned for media/subtitle URLs
// directly instead of guessing field names. That works regardless of the envelope shape, and for
// the vidbolt.xyz scrapers specifically the matched URLs are already fully self-contained CDN-proxy
// links (e.g. scraper.vidbolt.xyz/proxy/m3u8/{base64 origin url}?headers={referer/origin/UA as
// JSON}) with any Referer/Origin baked into their own query string, so no extra headers are needed
// fetching them here.
object PixelflixExtractor {

    private const val vidboltBase = "https://vidbolt.xyz/api"
    private const val reelsdownloadBase = "https://embed.reelsdownload.online/api"

    private val mediaRegex = Regex("""https?://[^\s"'\\]+?\.(?:m3u8|mpd|mp4|mkv|webm)[^\s"'\\]*""", RegexOption.IGNORE_CASE)
    private val subtitleRegex = Regex("""https?://[^\s"'\\]+?\.(?:vtt|srt)[^\s"'\\]*""", RegexOption.IGNORE_CASE)

    private fun linkTypeFor(url: String) = when {
        url.contains(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
        url.contains(".mpd", ignoreCase = true) -> ExtractorLinkType.DASH
        else -> ExtractorLinkType.VIDEO
    }

    // Scans a scrape response's raw text for playable/subtitle URLs and emits them - the one bit
    // shared by every provider below, vidbolt.xyz or reelsdownload.online alike.
    private suspend fun emitFromText(
        provider: String,
        body: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        mediaRegex.findAll(body).map { it.value }.distinct().forEach { streamUrl ->
            val link = runCatching {
                newExtractorLink(provider, provider, streamUrl, linkTypeFor(streamUrl)) {
                    this.quality = Qualities.Unknown.value
                }
            }.getOrNull() ?: return@forEach
            callback(link)
        }
        subtitleRegex.findAll(body).map { it.value }.distinct().forEach { subUrl ->
            subtitleCallback(SubtitleFile(provider, subUrl))
        }
    }

    // Shared plumbing every vidbolt.xyz invokeXxx below calls into - the only part that isn't
    // provider-specific. tv path/season/episode params are untested (the capture only covers a
    // movie) - ponytail: mirror the movie shape 1:1 until a TV capture confirms the real query names.
    private suspend fun scrapeProvider(
        provider: String,
        imdbId: String,
        isMovie: Boolean,
        season: Int?,
        episode: Int?,
        query: String,
        viaScraperEndpoint: Boolean = false,
        subtitleCallback: (SubtitleFile) -> Unit,
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
        emitFromText(provider, body, subtitleCallback, callback)
    }

    private fun titleYear(title: String?, year: Int?) = buildString {
        title?.let { append("&title=").append(URLEncoder.encode(it, "UTF-8")) }
        year?.let { append("&year=").append(it) }
    }

    suspend fun invokeFastVa(imdbId: String, tmdbId: Int, isMovie: Boolean, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) =
        scrapeProvider("FastVa", imdbId, isMovie, season, episode, "tmdbId=$tmdbId&imdbId=$imdbId", viaScraperEndpoint = true, subtitleCallback = subtitleCallback, callback = callback)

    suspend fun invokeQuasar(imdbId: String, tmdbId: Int, isMovie: Boolean, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) =
        scrapeProvider("Quasar", imdbId, isMovie, season, episode, "tmdbId=$tmdbId", viaScraperEndpoint = true, subtitleCallback = subtitleCallback, callback = callback)

    suspend fun invokeSaffron(imdbId: String, tmdbId: Int, isMovie: Boolean, season: Int?, episode: Int?, title: String?, year: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) =
        scrapeProvider("Saffron", imdbId, isMovie, season, episode, "tmdbId=$tmdbId&imdbId=$imdbId${titleYear(title, year)}", viaScraperEndpoint = true, subtitleCallback = subtitleCallback, callback = callback)

    suspend fun invokeNova(imdbId: String, tmdbId: Int, isMovie: Boolean, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) =
        scrapeProvider("Nova", imdbId, isMovie, season, episode, "tmdbId=$tmdbId", viaScraperEndpoint = true, subtitleCallback = subtitleCallback, callback = callback)

    suspend fun invokeVidRock(imdbId: String, tmdbId: Int, isMovie: Boolean, season: Int?, episode: Int?, title: String?, year: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) =
        scrapeProvider("VidRock", imdbId, isMovie, season, episode, "tmdbId=$tmdbId${titleYear(title, year)}", subtitleCallback = subtitleCallback, callback = callback)

    suspend fun invokeCineStream(imdbId: String, tmdbId: Int, isMovie: Boolean, season: Int?, episode: Int?, title: String?, year: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) =
        scrapeProvider("CineStream", imdbId, isMovie, season, episode, "tmdbId=$tmdbId${titleYear(title, year)}", subtitleCallback = subtitleCallback, callback = callback)

    suspend fun invokeFlaxmovies(imdbId: String, tmdbId: Int, isMovie: Boolean, season: Int?, episode: Int?, title: String?, year: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) =
        scrapeProvider("Flaxmovies", imdbId, isMovie, season, episode, "tmdbId=$tmdbId${titleYear(title, year)}", subtitleCallback = subtitleCallback, callback = callback)

    suspend fun invokeFSonic(imdbId: String, tmdbId: Int, isMovie: Boolean, season: Int?, episode: Int?, title: String?, year: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) =
        scrapeProvider("FSonic", imdbId, isMovie, season, episode, "tmdbId=$tmdbId${titleYear(title, year)}", subtitleCallback = subtitleCallback, callback = callback)

    suspend fun invoke4KHDHub(imdbId: String, tmdbId: Int, isMovie: Boolean, season: Int?, episode: Int?, title: String?, year: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) =
        scrapeProvider("4KHDHub", imdbId, isMovie, season, episode, "tmdbId=$tmdbId${titleYear(title, year)}", subtitleCallback = subtitleCallback, callback = callback)

    // Added from a second capture (Pixel server 2.postman_collection.json) - resolved a raw
    // movie.streamrip.fun m3u8 with no referer/auth needed, already covered by emitFromText.
    suspend fun invokeNinetta(imdbId: String, tmdbId: Int, isMovie: Boolean, season: Int?, episode: Int?, title: String?, year: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) =
        scrapeProvider("Ninetta", imdbId, isMovie, season, episode, "tmdbId=$tmdbId${titleYear(title, year)}", viaScraperEndpoint = true, subtitleCallback = subtitleCallback, callback = callback)

    // -------------------------------------------------------------------------------------------
    // embed.reelsdownload.online's OWN native extract APIs - a second, independent embed backend
    // (see the header comment). Captured live with real, working query shapes (Pixel Server
    // 1.postman_collection.json, 2026-09-10) - unlike the vidbolt.xyz scrapers above, these four
    // are NOT interchangeable: each backend was called with a different, specific param set, not
    // every param sent to every backend (cs3-extract in particular gets no tmdb_id at all).
    // -------------------------------------------------------------------------------------------
    private suspend fun reelsdownloadExtract(
        provider: String,
        path: String,
        query: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val body = runCatching { app.get("$reelsdownloadBase/$path?$query").text }.getOrElse {
            Log.e(TAG, "$provider failed: ${it.message}")
            return
        }
        emitFromText(provider, body, subtitleCallback, callback)
    }

    // ?type=movie|tv&tmdb_id={id} - season/episode for tv is unverified (capture only covers a movie).
    suspend fun invokeRedflix(tmdbId: Int, isMovie: Boolean, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val type = if (isMovie) "movie" else "tv"
        val query = "type=$type&tmdb_id=$tmdbId" + (if (!isMovie) "&season=${season ?: 1}&episode=${episode ?: 1}" else "")
        reelsdownloadExtract("Redflix", "redflix-extract", query, subtitleCallback, callback)
    }

    // ?type=movie|tv&title=...&year=... - captured with no tmdb_id/imdb_id at all, unlike the other three.
    suspend fun invokeCs3(isMovie: Boolean, title: String?, year: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val type = if (isMovie) "movie" else "tv"
        reelsdownloadExtract("CS3", "cs3-extract", "type=$type${titleYear(title, year)}", subtitleCallback, callback)
    }

    // ?type=movie|tv&tmdb_id={id}&imdb_id={imdbId}&title=...&year=...
    suspend fun invokeReelsdownloadCinemaos(tmdbId: Int, imdbId: String, isMovie: Boolean, title: String?, year: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val type = if (isMovie) "movie" else "tv"
        reelsdownloadExtract("Cinemaos", "cinemaos-extract", "type=$type&tmdb_id=$tmdbId&imdb_id=$imdbId${titleYear(title, year)}", subtitleCallback, callback)
    }

    // ?type=movie|tv&tmdb_id={id}&imdb_id={imdbId}&title=...&year=... - path has no "-extract" suffix.
    suspend fun invokeMoviebox(tmdbId: Int, imdbId: String, isMovie: Boolean, title: String?, year: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val type = if (isMovie) "movie" else "tv"
        reelsdownloadExtract("Moviebox", "moviebox", "type=$type&tmdb_id=$tmdbId&imdb_id=$imdbId${titleYear(title, year)}", subtitleCallback, callback)
    }

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
        val sources = mutableListOf<suspend () -> Unit>(
            { invokeRedflix(tmdbId, isMovie, season, episode, subtitleCallback, callback) },
            { invokeCs3(isMovie, title, year, subtitleCallback, callback) },
        )
        // These four need an imdb id (vidbolt.xyz's providers as a path segment, reelsdownload's
        // cinemaos/moviebox as a query param) - redflix/cs3 above don't, so they still run without one.
        if (!imdbId.isNullOrBlank()) {
            sources += { invokeReelsdownloadCinemaos(tmdbId, imdbId, isMovie, title, year, subtitleCallback, callback) }
            sources += { invokeMoviebox(tmdbId, imdbId, isMovie, title, year, subtitleCallback, callback) }
            sources += { invokeFastVa(imdbId, tmdbId, isMovie, season, episode, subtitleCallback, callback) }
            sources += { invokeQuasar(imdbId, tmdbId, isMovie, season, episode, subtitleCallback, callback) }
            sources += { invokeSaffron(imdbId, tmdbId, isMovie, season, episode, title, year, subtitleCallback, callback) }
            sources += { invokeNova(imdbId, tmdbId, isMovie, season, episode, subtitleCallback, callback) }
            sources += { invokeVidRock(imdbId, tmdbId, isMovie, season, episode, title, year, subtitleCallback, callback) }
            sources += { invokeCineStream(imdbId, tmdbId, isMovie, season, episode, title, year, subtitleCallback, callback) }
            sources += { invokeFlaxmovies(imdbId, tmdbId, isMovie, season, episode, title, year, subtitleCallback, callback) }
            sources += { invokeFSonic(imdbId, tmdbId, isMovie, season, episode, title, year, subtitleCallback, callback) }
            sources += { invoke4KHDHub(imdbId, tmdbId, isMovie, season, episode, title, year, subtitleCallback, callback) }
            sources += { invokeNinetta(imdbId, tmdbId, isMovie, season, episode, title, year, subtitleCallback, callback) }
        } else {
            Log.i(TAG, "No imdb id for tmdb_id=$tmdbId - skipping the vidbolt.xyz/cinemaos/moviebox sources that require it")
        }
        sources.amap { it() }
    }
}
