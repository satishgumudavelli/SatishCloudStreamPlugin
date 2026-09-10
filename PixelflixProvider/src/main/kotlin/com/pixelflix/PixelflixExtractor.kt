package com.pixelflix

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

private const val TAG = "PixelflixExtractor"

// pixelflix.cc's own /watch/{movie|tv}/{id} pages embed a third-party player at
// embed.reelsdownload.online (live-verified, research.md Task 5 - a real headless-Chrome capture
// via a public Lighthouse/Microlink run, since no Playwright/browser-automation tool was
// available). That embed page itself calls several backend "extract" APIs in parallel; only
// `redflix-extract` is used here - it was the one confirmed live to (a) work with just
// type+tmdb_id(+season/episode), no key/session/referer required, and (b) return a real, valid
// HLS master playlist (multiple quality variants + audio tracks). The other three backends seen
// in the capture (`cs3-extract`, `cinemaos-extract`, `moviebox`) were live-tested and found
// inconsistently available (one "runner unavailable", one timed out, one "not found" on both
// samples tried) - skipped for now rather than wired in on top of an already-working source
// (Constitution III: minimal diff; add them later only if redflix-extract alone proves
// insufficient in practice, not speculatively).
object PixelflixExtractor {

    private const val embedApi = "https://embed.reelsdownload.online/api/redflix-extract"

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
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val url = buildString {
            append(embedApi)
            append("?type=").append(if (isMovie) "movie" else "tv")
            append("&tmdb_id=").append(tmdbId)
            if (!isMovie) {
                append("&season=").append(season ?: 1)
                append("&episode=").append(episode ?: 1)
            }
        }

        val json = runCatching { JSONObject(app.get(url).text) }.getOrElse {
            Log.e(TAG, "redflix-extract request failed for tmdb_id=$tmdbId: ${it.message}")
            return
        }
        if (!json.optBoolean("found")) {
            Log.i(TAG, "redflix-extract found no source for tmdb_id=$tmdbId season=$season episode=$episode")
            return
        }

        val sources = json.optJSONArray("sources") ?: return
        for (i in 0 until sources.length()) {
            val source = sources.optJSONObject(i) ?: continue
            val streamUrl = source.optString("url").takeIf { it.isNotBlank() } ?: continue
            val label = source.optString("label").ifEmpty { source.optString("scraperName").ifEmpty { "Pixelflix" } }

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
                else -> Log.i(TAG, "Skipping unrecognized source type '${source.optString("type")}' for tmdb_id=$tmdbId")
            }
        }
    }
}
