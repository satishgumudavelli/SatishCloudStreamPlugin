package com.pixelflix

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.net.URLEncoder

private const val TAG = "PixelflixExtractor"

// Two independent embed backends observed live for the same pixelflix.cc watch page (captured on
// separate loads, 2026-09-10): embed.reelsdownload.online/player/{tmdbId} sometimes redirects to
// vidbolt.xyz (which then fans out to the ~10 scrapers below this comment), and sometimes serves
// its own native extract APIs directly (redflix-extract/cs3-extract/cinemaos-extract/moviebox,
// further down) without ever touching vidbolt.xyz. Since either can show up, both are queried.
//
// The vidbolt.xyz scrapers' real JSON shape (user-supplied live response bodies, 2026-09-10) is
// {"name","sources":[{"name","url","quality","type","language","headers",...}],"subtitles":
// [{"url","label","lang"}]} - parsed directly below. Some "url"s are already fully wrapped in
// scraper.vidbolt.xyz's own CDN proxy with Referer/Origin/UA baked into that URL's own query
// string (e.g. scraper.vidbolt.xyz/proxy/m3u8/{base64 origin url}?headers=...); others (e.g.
// VidRock's raw dream.flamingo-e55.workers.dev link) are the unwrapped origin - vidbolt.xyz's own
// browser JS re-wraps only those through a CORS proxy because *browsers* enforce CORS, which
// OkHttp doesn't, so the JSON's own "headers" object is passed straight through as this
// ExtractorLink's headers either way and works for both cases without us needing to tell them apart.
//
// embed.reelsdownload.online's cinemaos-extract (a live response body was captured 2026-09-10) turns
// out to be a NDJSON stream of {"source": {id, scraperId, scraperName, label, quality, type, url,
// mirrors, verified, tracks: [{label, lang, url}]}} lines, one per scraper result, ending
// {"done":true,"found":true} - the scraperIds (va/vf/z2/s7/q4/mb2...) match this same repo's
// CinemaOsExtractor scraper codes, so this is that same CinemaOS backend, just proxied here.
// redflix-extract/cs3-extract/moviebox's shapes are still unconfirmed, so all four keep being
// regex-scanned rather than JSON-parsed - it works regardless of the exact envelope. The one thing
// that regex scan has to special-case: cinemaos-extract's own subtitle track "url"s are a *relative*
// path (e.g. "/api/subs/vtt?c=...", no scheme/host), unlike every media url which is absolute.
object PixelflixExtractor {

    private const val reelsdownloadOrigin = "https://embed.reelsdownload.online"
    private const val vidboltBase = "https://vidbolt.xyz/api"
    private const val reelsdownloadBase = "$reelsdownloadOrigin/api"

    private val mediaRegex = Regex("""https?://[^\s"'\\]+?\.(?:m3u8|mpd|mp4|mkv|webm)[^\s"'\\]*""", RegexOption.IGNORE_CASE)
    private val subtitleRegex = Regex("""https?://[^\s"'\\]+?\.(?:vtt|srt)[^\s"'\\]*""", RegexOption.IGNORE_CASE)
    private val relativeSubtitleRegex = Regex("""/api/subs/vtt\?c=[^\s"'\\]+""")

    private fun linkTypeFor(url: String) = when {
        url.contains(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
        url.contains(".mpd", ignoreCase = true) -> ExtractorLinkType.DASH
        else -> ExtractorLinkType.VIDEO
    }

    private fun JSONObject.toStringMap(): Map<String, String> = keys().asSequence().associateWith { optString(it) }

    // vidbolt.xyz's real, known schema - see the header comment. Every source's "url"/"headers"
    // are used verbatim; "quality" (e.g. "1080p", "Auto", or even a language name like "Hindi") is
    // parsed with the same helper every other extractor in CloudStream uses for this, rather than
    // hand-rolling a "1080p" -> 1080 parser.
    private suspend fun emitFromVidboltJson(
        provider: String,
        body: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val json = runCatching { JSONObject(body) }.getOrElse {
            Log.e(TAG, "$provider: response wasn't JSON: ${it.message}")
            return
        }
        val sources = json.optJSONArray("sources")
        if (sources == null) {
            Log.i(TAG, "$provider: no sources in response")
        }
        for (i in 0 until (sources?.length() ?: 0)) {
            val src = sources?.optJSONObject(i) ?: continue
            val streamUrl = src.optString("url").takeIf { it.isNotBlank() } ?: continue
            val label = src.optString("name").takeIf { it.isNotBlank() }?.let { "$provider - $it" } ?: provider
            val headers = src.optJSONObject("headers")?.toStringMap() ?: emptyMap()
            val type = when (src.optString("type").lowercase()) {
                "m3u8", "hls" -> ExtractorLinkType.M3U8
                "dash", "mpd" -> ExtractorLinkType.DASH
                else -> ExtractorLinkType.VIDEO
            }
            val link = runCatching {
                newExtractorLink(provider, label, streamUrl, type) {
                    this.headers = headers
                    this.quality = getQualityFromName(src.optString("quality"))
                }
            }.getOrNull() ?: continue
            callback(link)
        }
        json.optJSONArray("subtitles")?.let { subs ->
            for (i in 0 until subs.length()) {
                val sub = subs.optJSONObject(i) ?: continue
                val subUrl = sub.optString("url").takeIf { it.isNotBlank() } ?: continue
                val label = sub.optString("label").ifBlank { sub.optString("lang") }.ifBlank { provider }
                subtitleCallback(SubtitleFile(label, subUrl))
            }
        }
    }

    // Scans a scrape response's raw text for playable/subtitle URLs and emits them - only used for
    // embed.reelsdownload.online below, whose JSON shape (unlike vidbolt.xyz's) was never captured.
    // A single provider response can carry several mirrors of the same title (multiple m3u8 URLs) -
    // numbered when there's more than one so they don't all show up in CloudStream's source list as
    // identical, indistinguishable entries.
    private suspend fun emitFromText(
        provider: String,
        body: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val streamUrls = mediaRegex.findAll(body).map { it.value }.distinct().toList()
        streamUrls.forEachIndexed { i, streamUrl ->
            val name = if (streamUrls.size > 1) "$provider ${i + 1}" else provider
            val link = runCatching {
                newExtractorLink(provider, name, streamUrl, linkTypeFor(streamUrl)) {
                    this.quality = Qualities.Unknown.value
                }
            }.getOrNull() ?: return@forEachIndexed
            callback(link)
        }
        subtitleRegex.findAll(body).map { it.value }.distinct().forEach { subUrl ->
            subtitleCallback(SubtitleFile(provider, subUrl))
        }
        // cinemaos-extract's own subtitle tracks are relative (no scheme/host) - resolve against
        // reelsdownload.online's own origin, the only host these paths are ever served from.
        relativeSubtitleRegex.findAll(body).map { it.value }.distinct().forEach { path ->
            subtitleCallback(SubtitleFile(provider, "$reelsdownloadOrigin$path"))
        }
    }

    // cinemaos-extract's real schema (user-supplied live NDJSON body, 2026-09-10): one JSON object
    // per line, {"source": {id, scraperId, scraperName, label, quality, type, url, mirrors,
    // verified, latencyMs, tracks: [{label, lang, url}]}}, ending with a final {"done","found"}
    // line that has no "source" key (skipped). Replaces emitFromText's regex scan now that a real
    // body confirmed the shape - per feedback_verify_extractor_response_before_coding.
    private suspend fun emitFromCinemaosJson(
        provider: String,
        body: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        body.lineSequence().forEach { line ->
            if (line.isBlank()) return@forEach
            val source = runCatching { JSONObject(line) }.getOrNull()?.optJSONObject("source") ?: return@forEach
            // ponytail: skip unverified mirrors - most quality dupes for a given label are unverified
            // and slow (latencyMs near the 6000ms timeout ceiling), verified ones cover the same labels.
            if (!source.optBoolean("verified", true)) return@forEach
            val rawUrl = source.optString("url").takeIf { it.isNotBlank() } ?: return@forEach
            val streamUrl = if (rawUrl.startsWith("/")) "$reelsdownloadOrigin$rawUrl" else rawUrl
            val scraperName = source.optString("scraperName").ifBlank { source.optString("scraperId") }
            val label = source.optString("label").takeIf { it.isNotBlank() }
            val name = listOfNotNull(scraperName.takeIf { it.isNotBlank() }, label).joinToString(" - ").ifBlank { provider }
            val type = when (source.optString("type").lowercase()) {
                "dash", "mpd" -> ExtractorLinkType.DASH
                "hls", "m3u8" -> ExtractorLinkType.M3U8
                else -> ExtractorLinkType.VIDEO
            }
            val link = runCatching {
                newExtractorLink(provider, name, streamUrl, type) {
                    this.quality = getQualityFromName(source.optString("quality"))
                }
            }.getOrNull()
            if (link != null) callback(link)

            source.optJSONArray("tracks")?.let { tracks ->
                for (i in 0 until tracks.length()) {
                    val track = tracks.optJSONObject(i) ?: continue
                    val rawSubUrl = track.optString("url").takeIf { it.isNotBlank() } ?: continue
                    val subUrl = if (rawSubUrl.startsWith("/")) "$reelsdownloadOrigin$rawSubUrl" else rawSubUrl
                    val subLabel = track.optString("label").ifBlank { track.optString("lang") }.ifBlank { provider }
                    subtitleCallback(SubtitleFile(subLabel, subUrl))
                }
            }
        }
    }

    // Shared plumbing every vidbolt.xyz invokeXxx below calls into - the only part that isn't
    // provider-specific. tv path/season/episode params are untested (the capture only covers a
    // movie) - ponytail: mirror the movie shape 1:1 until a TV capture confirms the real query names.
    private suspend fun scrapeProvider(
        provider: String,
        imdbId: String?,
        isMovie: Boolean,
        season: Int?,
        episode: Int?,
        query: String,
        viaScraperEndpoint: Boolean = false,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        if (imdbId.isNullOrBlank()) {
            Log.i(TAG, "$provider needs an imdb id, skipping")
            return
        }
        val type = if (isMovie) "movie" else "tv"
        val scrapePath = "/scrape/$provider/$type/$imdbId?$query" +
            (if (!isMovie) "&season=${season ?: 1}&episode=${episode ?: 1}" else "")
        val endpoint = if (viaScraperEndpoint) "scraper" else "proxy"
        val url = "$vidboltBase/$endpoint?path=" + URLEncoder.encode(scrapePath, "UTF-8")

        val body = runCatching { app.get(url).text }.getOrElse {
            Log.e(TAG, "$provider scrape failed for imdb_id=$imdbId: ${it.message}")
            return
        }
        emitFromVidboltJson(provider, body, subtitleCallback, callback)
    }

    private fun titleYear(title: String?, year: Int?) = buildString {
        title?.let { append("&title=").append(URLEncoder.encode(it, "UTF-8")) }
        year?.let { append("&year=").append(it) }
    }

    suspend fun invokeFastVa(imdbId: String?, tmdbId: Int, isMovie: Boolean, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) =
        scrapeProvider("FastVa", imdbId, isMovie, season, episode, "tmdbId=$tmdbId&imdbId=$imdbId", viaScraperEndpoint = true, subtitleCallback = subtitleCallback, callback = callback)

    suspend fun invokeQuasar(imdbId: String?, tmdbId: Int, isMovie: Boolean, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) =
        scrapeProvider("Quasar", imdbId, isMovie, season, episode, "tmdbId=$tmdbId", viaScraperEndpoint = true, subtitleCallback = subtitleCallback, callback = callback)

    suspend fun invokeSaffron(imdbId: String?, tmdbId: Int, isMovie: Boolean, season: Int?, episode: Int?, title: String?, year: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) =
        scrapeProvider("Saffron", imdbId, isMovie, season, episode, "tmdbId=$tmdbId&imdbId=$imdbId${titleYear(title, year)}", viaScraperEndpoint = true, subtitleCallback = subtitleCallback, callback = callback)

    suspend fun invokeNova(imdbId: String?, tmdbId: Int, isMovie: Boolean, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) =
        scrapeProvider("Nova", imdbId, isMovie, season, episode, "tmdbId=$tmdbId", viaScraperEndpoint = true, subtitleCallback = subtitleCallback, callback = callback)

    suspend fun invokeVidRock(imdbId: String?, tmdbId: Int, isMovie: Boolean, season: Int?, episode: Int?, title: String?, year: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) =
        scrapeProvider("VidRock", imdbId, isMovie, season, episode, "tmdbId=$tmdbId${titleYear(title, year)}", subtitleCallback = subtitleCallback, callback = callback)

    suspend fun invokeCineStream(imdbId: String?, tmdbId: Int, isMovie: Boolean, season: Int?, episode: Int?, title: String?, year: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) =
        scrapeProvider("CineStream", imdbId, isMovie, season, episode, "tmdbId=$tmdbId${titleYear(title, year)}", subtitleCallback = subtitleCallback, callback = callback)

    suspend fun invokeFlaxmovies(imdbId: String?, tmdbId: Int, isMovie: Boolean, season: Int?, episode: Int?, title: String?, year: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) =
        scrapeProvider("Flaxmovies", imdbId, isMovie, season, episode, "tmdbId=$tmdbId${titleYear(title, year)}", subtitleCallback = subtitleCallback, callback = callback)

    suspend fun invokeFSonic(imdbId: String?, tmdbId: Int, isMovie: Boolean, season: Int?, episode: Int?, title: String?, year: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) =
        scrapeProvider("FSonic", imdbId, isMovie, season, episode, "tmdbId=$tmdbId${titleYear(title, year)}", subtitleCallback = subtitleCallback, callback = callback)

    suspend fun invoke4KHDHub(imdbId: String?, tmdbId: Int, isMovie: Boolean, season: Int?, episode: Int?, title: String?, year: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) =
        scrapeProvider("4KHDHub", imdbId, isMovie, season, episode, "tmdbId=$tmdbId${titleYear(title, year)}", subtitleCallback = subtitleCallback, callback = callback)

    // Added from a second capture (Pixel server 2.postman_collection.json) - resolved a raw
    // movie.streamrip.fun m3u8 with no referer/auth needed, same JSON shape as every other provider.
    suspend fun invokeNinetta(imdbId: String?, tmdbId: Int, isMovie: Boolean, season: Int?, episode: Int?, title: String?, year: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) =
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
        emit: suspend (String, String, (SubtitleFile) -> Unit, (ExtractorLink) -> Unit) -> Unit = ::emitFromText,
    ) {
        val body = runCatching { app.get("$reelsdownloadBase/$path?$query").text }.getOrElse {
            Log.e(TAG, "$provider failed: ${it.message}")
            return
        }
        emit(provider, body, subtitleCallback, callback)
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
    suspend fun invokeReelsdownloadCinemaos(tmdbId: Int, imdbId: String?, isMovie: Boolean, title: String?, year: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        if (imdbId.isNullOrBlank()) return
        val type = if (isMovie) "movie" else "tv"
        reelsdownloadExtract("Cinemaos", "cinemaos-extract", "type=$type&tmdb_id=$tmdbId&imdb_id=$imdbId${titleYear(title, year)}", subtitleCallback, callback, ::emitFromCinemaosJson)
    }

    // ?type=movie|tv&tmdb_id={id}&imdb_id={imdbId}&title=...&year=... - path has no "-extract" suffix.
    suspend fun invokeMoviebox(tmdbId: Int, imdbId: String?, isMovie: Boolean, title: String?, year: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        if (imdbId.isNullOrBlank()) return
        val type = if (isMovie) "movie" else "tv"
        reelsdownloadExtract("Moviebox", "moviebox", "type=$type&tmdb_id=$tmdbId&imdb_id=$imdbId${titleYear(title, year)}", subtitleCallback, callback)
    }
}
