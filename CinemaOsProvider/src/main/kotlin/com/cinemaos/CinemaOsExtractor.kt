package com.cinemaos

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import org.json.JSONObject
import java.net.URLEncoder

/**
 * CinemaOS's providerv5/scrape API - HMAC-derived per-request secret, AES-256-GCM+PBKDF2-encrypted
 * response. Ported from VidboxProvider's invokeCinemaos, confirmed still live against
 * cinemaos.in as of this writing: the request decrypts successfully (GCM auth tag verifies) for
 * both scraper names below, but `sources` has come back empty for every title tried so far -
 * either those two providers are genuinely down right now, or (more likely, since it's
 * consistent across many titles) a required param is stale - `_gt` below was reverse-engineered
 * from a webpack chunk at a point in time and may need to be fetched fresh per-request rather
 * than hardcoded, same as the `secret` already is. Flagging rather than blocking on it: the
 * request/decrypt plumbing itself is verified correct, so once the right `_gt` (or whatever else
 * is missing) is found, wiring in real sources is a small change here, not a rewrite.
 */
private const val cinemaosPrimaryKey = "a7f3b9c2e8d4f1a6b5c9e2d7f4a8b3c6e1d9f7a4b2c8e5d3f9a6b4c1e7d2f8a5"
private const val cinemaosSecondaryKey = "d3f8a5b2c9e6d1f7a4b8c5e2d9f3a6b1c7e4d8f2a9b5c3e7d4f1a8b6c2e9d5f3"
private const val cinemaosEncKeyHex = "a1b2c3d4e4f6477658455678901477567890abcdef1234567890abcdef123456"
private const val cinemaosGt = "6775dc8e702c08643385273df088c14952c590ddda02d14f"

// Confirmed valid against the live site (others 502 - the endpoint validates scraper names
// against a server-side allowlist); there are more we haven't found yet since the site fetches
// its scraper list client-side rather than embedding it in the static JS chunks. Note: this
// endpoint is unrelated to the FebBox-token-gated "Premium/Standard/Free" server picker in the
// site's own player UI (that one requires the end user's own FebBox account, not something we
// can resolve anonymously) - "vf"/"rive"/"v2" are a separate, smaller set of anonymous sources.
private val cinemaosScrapers = listOf("vf", "rive", "v2")

object CinemaOsExtractor {

    private fun cinemaosSecret(tmdbId: Int, season: Int?, episode: Int?): String {
        val parts = mutableListOf("tmdbId:$tmdbId")
        if (season != null) parts.add("seasonId:$season")
        if (episode != null) parts.add("episodeId:$episode")
        val content = parts.joinToString("|")
        return hmacSha256Hex(cinemaosSecondaryKey, hmacSha256Hex(cinemaosPrimaryKey, content))
    }

    suspend fun invokeCinemaos(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        title: String?,
        year: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (tmdbId == null) return
        val base = CinemaOsScraper.resolveMainUrl()
        val type = if (season == null) "movie" else "tv"
        val secret = cinemaosSecret(tmdbId, season, episode)
        val headers = mapOf("Referer" to "$base/watch/$type/$tmdbId")

        cinemaosScrapers.amap { scraper ->
            val params = mutableListOf(
                "type" to type, "tmdbId" to "$tmdbId", "scraper" to scraper, "secret" to secret, "_gt" to cinemaosGt,
                "t" to (title ?: ""), "ry" to (year?.toString() ?: "")
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

            val scraperName = resolved.optString("name", scraper)

            resolved.optJSONArray("captions")?.let { caps ->
                for (i in 0 until caps.length()) {
                    val cap = caps.optJSONObject(i) ?: continue
                    val subUrl = cap.optString("url").takeIf { it.isNotBlank() } ?: continue
                    val lang = cap.optString("language").takeIf { it.isNotBlank() } ?: cap.optString("label", "Unknown")
                    subtitleCallback(SubtitleFile(lang, subUrl))
                }
            }

            resolved.optJSONObject("sources")?.let { sourcesObj ->
                sourcesObj.keys().asSequence().toList().forEach { key ->
                    val entry = sourcesObj.opt(key)
                    val srcUrl = (entry as? JSONObject)?.optString("url")?.takeIf { it.isNotBlank() }
                        ?: (entry as? String)?.takeIf { it.isNotBlank() }
                    if (srcUrl != null) generateM3u8("CinemaOS-$scraperName-$key", srcUrl, "", headers = headers).forEach(callback)
                }
            }
        }
    }
}
