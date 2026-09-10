package com.pixelflix

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.net.URLEncoder

private const val TAG = "PixelflixExtractor"

// pixelflix.cc's own /watch/{movie|tv}/{id} pages embed a third-party player at
// embed.reelsdownload.online (live-verified, research.md Task 5 - a real headless-Chrome capture
// via a public Lighthouse/Microlink run, since no Playwright/browser-automation tool was
// available). That embed page itself calls four backend "extract" APIs in parallel - the site's
// own player shows each as a separate, switchable "server" (per user report after checking the
// live site), so all four are queried here too rather than just the one (`redflix-extract`) that
// was reliable in initial testing: `cs3-extract`/`cinemaos-extract`/`moviebox` were live-tested
// and found inconsistently available (one "runner unavailable", one timed out, one "not found"),
// but "inconsistently available" is exactly the case multiple independent servers are for - a
// later on-device failure (a real connect-timeout to the whole embed.reelsdownload.online host)
// confirmed this host itself does go down sometimes, so more independent sources reduce (not
// eliminate - see below) the odds every one is down at once.
object PixelflixExtractor {

    private const val embedBase = "https://embed.reelsdownload.online/api"

    // Each backend was captured with a slightly different query-param set (redflix needs only
    // type+tmdb_id(+season/episode); cs3/cinemaos/moviebox also take title+year) - passing every
    // known param to every backend is untested for the three that weren't live-verified on a
    // success response, but liberal/extra query params are standard REST tolerance, not a new
    // endpoint-shape guess; each backend's response is parsed with the same defensive
    // runCatching as before, so an unexpected shape yields zero sources from that one backend,
    // never a crash or a guessed link (Constitution II).
    // Path per backend, exactly as captured live (research.md Task 5) - "moviebox" has no
    // "-extract" suffix, unlike the other three; not a naming pattern to extrapolate from.
    private val backends = listOf(
        "redflix" to "redflix-extract",
        "cs3" to "cs3-extract",
        "cinemaos" to "cinemaos-extract",
        "moviebox" to "moviebox",
    )

    // Emits the raw m3u8 URL directly rather than running it through M3u8Helper.generateM3u8's
    // quality-splitter - matches the same helper (same name/shape) already used by
    // CinemaOsExtractor/VidboxExtractor in this repo (Constitution III: reuse before
    // reinventing). redflix-extract already marks its source "verified": true from a structured
    // JSON API (not a regex-matched network request that could snag a decoy asset, unlike the
    // WebView-intercepted case those two extractors guard against), so the extra fetch+parse
    // validation step generateM3u8 does isn't needed here. quality is left Unknown rather than
    // tagged from the JSON's own "quality" label (e.g. "720p") - that label doesn't describe the
    // master playlist as a whole (live-verified: the same master carries 480p/720p/1080p
    // variants), so tagging the single link with one resolution would be misleading; CloudStream's
    // own HLS player handles per-resolution adaptive selection from the master at playback time.
    private suspend fun directM3u8Link(
        source: String,
        streamUrl: String,
        referer: String = "",
        headers: Map<String, String> = emptyMap(),
        quality: Int? = null,
        name: String = source,
    ): ExtractorLink = newExtractorLink(source, name, streamUrl, ExtractorLinkType.M3U8) {
        this.referer = referer
        this.headers = headers
        this.quality = quality ?: Qualities.Unknown.value
    }

    suspend fun invoke(
        tmdbId: Int,
        isMovie: Boolean,
        season: Int?,
        episode: Int?,
        title: String?,
        year: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        // Independent backends, queried concurrently (amap) - a slow/down one only costs its own
        // timeout, not the sum of all four, and one failing never blocks the others' results.
        backends.amap { (backend, path) ->
            val url = buildString {
                append(embedBase).append("/").append(path)
                append("?type=").append(if (isMovie) "movie" else "tv")
                append("&tmdb_id=").append(tmdbId)
                title?.let { append("&title=").append(URLEncoder.encode(it, "UTF-8")) }
                year?.let { append("&year=").append(it) }
                if (!isMovie) {
                    append("&season=").append(season ?: 1)
                    append("&episode=").append(episode ?: 1)
                }
            }

            val json = runCatching { JSONObject(app.get(url).text) }.getOrElse {
                Log.e(TAG, "$backend-extract request failed for tmdb_id=$tmdbId: ${it.message}")
                return@amap
            }
            if (!json.optBoolean("found")) {
                Log.i(TAG, "$backend-extract found no source for tmdb_id=$tmdbId season=$season episode=$episode")
                return@amap
            }

            val sources = json.optJSONArray("sources") ?: return@amap
            for (i in 0 until sources.length()) {
                val source = sources.optJSONObject(i) ?: continue
                val streamUrl = source.optString("url").takeIf { it.isNotBlank() } ?: continue
                val label = source.optString("label").ifEmpty { source.optString("scraperName").ifEmpty { backend } }

                when (source.optString("type")) {
                    "hls" -> {
                        // The master playlist itself carries every quality variant (FR-009,
                        // live-verified: 480p/720p/1080p) and every audio track (FR-010,
                        // live-verified: Hindi default + English as separate EXT-X-MEDIA groups) -
                        // CloudStream's HLS player reads both directly from this one link.
                        val link = runCatching { directM3u8Link(label, streamUrl) }.onFailure {
                            Log.e(TAG, "directM3u8Link failed for $streamUrl: ${it.message}")
                        }.getOrNull() ?: continue
                        callback(link)
                    }
                    else -> Log.i(TAG, "Skipping unrecognized source type '${source.optString("type")}' from $backend for tmdb_id=$tmdbId")
                }
            }
        }
    }
}
