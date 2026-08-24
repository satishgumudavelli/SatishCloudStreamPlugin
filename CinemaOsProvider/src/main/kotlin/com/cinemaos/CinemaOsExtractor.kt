package com.cinemaos

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.net.URLEncoder

/**
 * cinemaos.tech/.in's providerv5/scrape API - the real backend behind the "CinemaOS
 * V1/V2/V3/4K" private-servers panel in the site's own player, confirmed live via a captured
 * Postman/browser request (the earlier vyla-stream/goku findings turned out to be either dead
 * or unrelated side endpoints; this is the one the site's own client actually calls).
 *
 * The HMAC secret scheme itself was already correct (same primary/secondary keys as before) -
 * what was missing the whole time was `imdbId`: the secret's signed content is
 * "tmdbId:{id}|imdbId:{imdb}[|seasonId:{s}|episodeId:{e}]", not just tmdbId. Without it the
 * request still decrypts fine (no crypto error) but every scraper returns `sources: {}` - which
 * is exactly the "confirmed round-trip, always empty" symptom seen throughout earlier attempts.
 * The gate-token query param is also now named `_ck` rather than `_gt` (same constant value).
 *
 * GET {base}/api/providerv5/scrape?type=movie|tv&tmdbId=X&imdbId=ttX&t=title&ry=year
 *     &secret=...&_ck=...&scraper=CODE[&seasonId=S&episodeId=E]
 * -> encrypted (AES-256-GCM + PBKDF2, same as before) to
 *    {"name": providerName, "sources": {ServerName: {url, server, type, bitrate, speed}, ...}, "captions": [...]}
 *
 * Live-verified against a real title (tmdbId 1323244, scraper "va") and got real HLS sources
 * keyed "Helios"/"Selene"/"Eos" - the exact server names shown in the site's own UI. "vf"/
 * "rive"/"v2" are valid scrapers too but just have no data for every title tried so far.
 */
private const val cinemaosPrimaryKey = "a7f3b9c2e8d4f1a6b5c9e2d7f4a8b3c6e1d9f7a4b2c8e5d3f9a6b4c1e7d2f8a5"
private const val cinemaosSecondaryKey = "d3f8a5b2c9e6d1f7a4b8c5e2d9f3a6b1c7e4d8f2a9b5c3e7d4f1a8b6c2e9d5f3"
private const val cinemaosEncKeyHex = "a1b2c3d4e4f6477658455678901477567890abcdef1234567890abcdef123456"
private const val cinemaosGateToken = "6775dc8e702c08643385273df088c14952c590ddda02d14f"

private val cinemaosScrapers = listOf(
    "va", "vf", "rive", "v2", "vdn", "zm", "lm", "vs", "vl", "vz", "cs", "mt", "hexa",
    "ms", "vp", "ts", "vy", "fx", "py", "fz", "fs", "cz", "fn", "sc", "vdy", "nhd", "vx",
    // Found in a live capture of the site's own "CinemaOS V3" player - human names unknown.
    "h0", "mb2", "q4", "s3", "vc", "vn", "z2",
)

object CinemaOsExtractor {

    private fun cinemaosSecret(tmdbId: Int, imdbId: String, season: Int?, episode: Int?): String {
        val parts = mutableListOf("tmdbId:$tmdbId", "imdbId:$imdbId")
        if (season != null) parts.add("seasonId:$season")
        if (episode != null) parts.add("episodeId:$episode")
        val content = parts.joinToString("|")
        return hmacSha256Hex(cinemaosSecondaryKey, hmacSha256Hex(cinemaosPrimaryKey, content))
    }

    suspend fun invokeCinemaos(
        tmdbId: Int?,
        imdbId: String?,
        season: Int?,
        episode: Int?,
        title: String?,
        year: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (tmdbId == null || imdbId.isNullOrBlank()) return
        val base = CinemaOsScraper.resolveMainUrl()
        val type = if (season == null) "movie" else "tv"
        val secret = cinemaosSecret(tmdbId, imdbId, season, episode)
        val headers = mapOf("Referer" to "$base/watch/$type/$tmdbId")
        // 34 scrapers run in parallel and can return the same mirror/URL more than once - dedupe
        // across all of them so only unique sources reach the user.
        val seenUrls = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        cinemaosScrapers.amap { scraper ->
            val params = mutableListOf(
                "type" to type, "tmdbId" to "$tmdbId", "imdbId" to imdbId, "t" to (title ?: ""),
                "ry" to (year?.toString() ?: ""), "secret" to secret, "_ck" to cinemaosGateToken, "scraper" to scraper
            )
            if (season != null) params.add("seasonId" to "$season")
            if (episode != null) params.add("episodeId" to "$episode")
            val qs = params.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }

            val response = runCatching { app.get("$base/api/providerv5/scrape?$qs", headers = headers).text }.getOrNull() ?: return@amap
            val json = runCatching { JSONObject(response) }.getOrNull() ?: return@amap

            val resolved: JSONObject = if (json.optBoolean("encrypted", false) && json.has("data")) {
                val data = json.optJSONObject("data") ?: return@amap
                val keyBytes = if (data.has("salt") && data.optInt("version", 0) >= 1) {
                    pbkdf2Sha256(cinemaosEncKeyHex, data.optString("salt"), 100000, 32)
                } else hexToBytes(cinemaosEncKeyHex)
                val pt = runCatching {
                    aesGcmDecrypt(keyBytes, data.optString("cin"), data.optString("encrypted"), data.optString("mao"))
                }.getOrNull() ?: return@amap
                runCatching { JSONObject(pt) }.getOrNull() ?: return@amap
            } else json

            resolved.optJSONArray("captions")?.let { caps ->
                for (i in 0 until caps.length()) {
                    val cap = caps.optJSONObject(i) ?: continue
                    val subUrl = cap.optString("url").takeIf { it.isNotBlank() } ?: continue
                    val lang = cap.optString("language").takeIf { it.isNotBlank() } ?: cap.optString("label", "Unknown")
                    subtitleCallback(SubtitleFile(lang, subUrl))
                }
            }

            val sourcesObj = resolved.optJSONObject("sources") ?: return@amap
            sourcesObj.keys().asSequence().toList().forEach { serverName ->
                val entry = sourcesObj.optJSONObject(serverName) ?: return@forEach
                val label = entry.optString("server").takeIf { it.isNotBlank() } ?: serverName

                suspend fun emit(srcUrl: String, type: String, qualityTag: String) {
                    if (!seenUrls.add(srcUrl)) return
                    val name = "CinemaOS [$label]" + if (qualityTag.isNotBlank()) " $qualityTag" else ""
                    if (type == "hls" || srcUrl.contains(".m3u8", ignoreCase = true)) {
                        generateM3u8("CinemaOS-$serverName$qualityTag", srcUrl, "", headers = headers, name = name).forEach(callback)
                    } else {
                        callback(
                            newExtractorLink("CinemaOS-$serverName$qualityTag", name, srcUrl, ExtractorLinkType.VIDEO) {
                                this.headers = headers
                            }
                        )
                    }
                }

                val flatUrl = entry.optString("url").takeIf { it.isNotBlank() }
                if (flatUrl != null) {
                    emit(flatUrl, entry.optString("type"), "")
                } else {
                    val qualities = entry.optJSONObject("qualities") ?: return@forEach
                    qualities.keys().asSequence().toList().forEach { q ->
                        val q0 = qualities.optJSONObject(q) ?: return@forEach
                        val qUrl = q0.optString("url").takeIf { it.isNotBlank() } ?: return@forEach
                        emit(qUrl, q0.optString("type"), "-$q")
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------------------------
    // cinemaos.tech's /player/{tmdbId} page - a third, independent fallback that doesn't call
    // providerv5/cinemaosv2pro's APIs at all. It renders the real embed page in an actual WebView
    // and reads off whatever m3u8/mp4 request the page's own client JS fires, same mechanism the
    // site's real UI uses. Unlike a plain curl/app.get, this only works because a WebView executes
    // the page's JS - confirmed live by the user in a real browser.
    suspend fun invokeCinemaosWebview(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = tmdbId ?: return
        val base = CinemaOsScraper.resolveMainUrl()
        val url = if (season == null) "$base/player/$id" else "$base/player/$id/$season/$episode"

        val mediaRes = runCatching {
            app.get(
                url,
                interceptor = WebViewResolver(
                    Regex("""https?://[^"'\s]+?\.(?:m3u8|mp4)(?:\?[^"'\s]*)?"""),
                    useOkhttp = false,
                    timeout = 20_000L,
                )
            )
        }.getOrNull() ?: return

        val mediaUrl = mediaRes.url
        val type = when {
            mediaUrl.contains(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
            mediaUrl.contains(".mp4", ignoreCase = true) -> ExtractorLinkType.VIDEO
            else -> return
        }

        callback(
            newExtractorLink("CinemaOS-WebView", "CinemaOS [WebView]", mediaUrl, type) {
                this.referer = "$base/"
                this.headers = mapOf("Referer" to "$base/", "Origin" to base)
            }
        )
    }

    // -------------------------------------------------------------------------------------------
    // /api/cinemaosv2pro - a second, independent CinemaOS backend (MovieBox-sourced DASH/MP4),
    // found live in a capture of the site's own "CinemaOS V2" player. Simpler than providerv5:
    // one request returns every stream at once, no imdbId, no per-scraper loop, no AES-GCM.
    // Just a per-minute anti-scraping hash (`_vh`) - same djb2-style fold this exact site used
    // for its older (now-renamed) /api/cinemaosv2 endpoint, confirmed by porting that algorithm
    // unchanged and having it work first try: only the endpoint name (cinemaosv2 -> cinemaosv2pro)
    // and hash param name (h -> _vh) had changed, not the secret or the fold itself.
    //
    // GET {base}/api/cinemaosv2pro?tmdbId=X&type=movie|tv&title=T&_vh=hash-bucket36&_ck=...
    // -> {"streams": [{"name": "MovieBox (English dub) 1080p [DASH]", "title", "url", "quality"}, ...]}
    //
    // Live-verified: 14 real streams (mixed DASH .mpd and MP4, several dub languages) for one
    // movie. TV support (season/episode as plain query params, not part of the hash - the hash
    // payload has never included them even pre-rename) confirmed via a live TV capture too
    // (?type=tv&season=1&episode=1 alongside the same unchanged _vh shape).
    // -------------------------------------------------------------------------------------------
    private const val cinemaosV2ProHashSecret = "a53ce07ac6250a232ec81d256d3a9db8e399f883cfc5370995388b683882f572"

    private fun cinemaosV2ProHash(tmdbId: Int, minuteBucket: Long): String {
        val payload = "$tmdbId:$minuteBucket:$cinemaosV2ProHashSecret"
        var x = 0
        for (c in payload) {
            x = (x shl 5) - x + c.code
        }
        val hex = kotlin.math.abs(x.toLong()).toString(16).padStart(8, '0')
        return "$hex-${minuteBucket.toString(36)}"
    }

    suspend fun invokeCinemaosV2Pro(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        title: String?,
        callback: (ExtractorLink) -> Unit
    ) {
        if (tmdbId == null) return
        val base = CinemaOsScraper.resolveMainUrl()
        val type = if (season == null) "movie" else "tv"
        val minuteBucket = System.currentTimeMillis() / 60_000
        val vh = cinemaosV2ProHash(tmdbId, minuteBucket)
        val headers = mapOf("Referer" to "$base/watch/$type/$tmdbId")

        val params = mutableListOf("tmdbId" to "$tmdbId", "type" to type, "title" to (title ?: ""), "_vh" to vh, "_ck" to cinemaosGateToken)
        if (season != null) params.add("season" to "$season")
        if (episode != null) params.add("episode" to "$episode")
        val qs = params.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }

        val response = runCatching { app.get("$base/api/cinemaosv2pro?$qs", headers = headers).text }.getOrNull() ?: return
        val streams = runCatching { JSONObject(response).optJSONArray("streams") }.getOrNull() ?: return

        for (i in 0 until streams.length()) {
            val stream = streams.optJSONObject(i) ?: continue
            val srcUrl = stream.optString("url").takeIf { it.isNotBlank() } ?: continue
            val name = stream.optString("name").takeIf { it.isNotBlank() } ?: "CinemaOS"

            when {
                srcUrl.contains(".m3u8", ignoreCase = true) ->
                    generateM3u8("CinemaOS-v2pro-$i", srcUrl, "", headers = headers, name = name).forEach(callback)
                srcUrl.contains(".mpd", ignoreCase = true) ->
                    callback(newExtractorLink("CinemaOS-v2pro-$i", name, srcUrl, ExtractorLinkType.DASH) { this.headers = headers })
                else ->
                    callback(newExtractorLink("CinemaOS-v2pro-$i", name, srcUrl, ExtractorLinkType.VIDEO) { this.headers = headers })
            }
        }
    }
}
