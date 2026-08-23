package com.cinemaos

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
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
                val srcUrl = entry.optString("url").takeIf { it.isNotBlank() } ?: return@forEach
                val label = entry.optString("server").takeIf { it.isNotBlank() } ?: serverName

                if (entry.optString("type") == "hls" || srcUrl.contains(".m3u8", ignoreCase = true)) {
                    generateM3u8("CinemaOS-$serverName", srcUrl, "", headers = headers).forEach(callback)
                } else {
                    callback(
                        newExtractorLink("CinemaOS-$serverName", "CinemaOS [$label]", srcUrl, ExtractorLinkType.VIDEO) {
                            this.headers = headers
                        }
                    )
                }
            }
        }
    }
}
