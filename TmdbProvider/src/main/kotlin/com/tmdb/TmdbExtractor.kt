package com.tmdb

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.json.JSONObject

/**
 * TmdbProvider resolves nothing itself - it only ever calls CloudStream's own loadExtractor,
 * which dispatches to whichever of the ~250 extractors already built into the app (Uqload, Voe,
 * Dood, StreamWish, MixDrop, Playmogo, Vido, ...) is registered for a given URL's host.
 *
 * The one piece TmdbProvider needs is something that turns a tmdbId into a URL actually landing
 * on one of those hosts. Checked this against the real bundled extractor class list (unzipped
 * ~/.gradle/caches/cloudstream/cloudstream/cloudstream.jar, the exact dependency this repo
 * compiles against) before trusting it: none of the well-known TMDB-aggregator sites (vidsrc.*,
 * 2embed, vidlink, moviesapi, ...) have a matching bundled extractor - those are themselves
 * scrapers a plugin has to write from scratch (as VidboxProvider's other ~12 sources do).
 * frembed.casa is different: it's a redirect aggregator - its own API hands back a 302 straight
 * to the underlying embed host, and *those* hosts (uqload.vc, playmogo.com, vido.lol, ...) are
 * exactly the kind loadExtractor already knows how to handle. Live-verified: /api/films and
 * /api/series' response shape has drifted from what VidboxExtractor's own invokeFrembed expects
 * (no more "links" array - now flat "link"/"link1".."link7"[+"vostfr" audio variants] fields,
 * each a path to /api/stream?...&server=<field> that 302s to the real embed host) - updated for
 * that here rather than copying the stale version.
 */
private const val frembedApi = "https://frembed.casa"

object TmdbExtractor {

    suspend fun resolve(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        title: String?,
        year: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (tmdbId == null) return
        val headers = mapOf("Referer" to "$frembedApi/", "Origin" to frembedApi)
        val url = if (season == null) "$frembedApi/api/films?id=$tmdbId&idType=tmdb"
        else "$frembedApi/api/series?id=$tmdbId&idType=tmdb&sa=$season&epi=$episode"

        val json = runCatching { JSONObject(app.get(url, headers = headers).text) }.getOrNull() ?: return
        val linkPaths = json.keys().asSequence()
            .filter { it == "link" || it.startsWith("link") }
            .mapNotNull { json.optString(it).takeIf { path -> path.isNotBlank() } }
            .toList()

        linkPaths.amap { path ->
            val resolved = runCatching { app.get("$frembedApi$path", headers = headers, allowRedirects = false) }.getOrNull() ?: return@amap
            val target = resolved.headers["location"] ?: return@amap
            loadExtractor(target, frembedApi, subtitleCallback, callback)
        }
    }
}
