package com.tmdb

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import org.json.JSONObject
import java.net.URLEncoder

/**
 * CinemaOS's providerv5/scrape API - ported from CinemaOsProvider's CinemaOsExtractor (which was
 * itself ported from VidboxProvider's invokeCinemaos), using the same shared domains.json
 * DomainResolver targets. See CinemaOsExtractor.kt for full detail on this API and its currently
 * unresolved empty-sources gap.
 */
private const val cinemaosPrimaryKey = "a7f3b9c2e8d4f1a6b5c9e2d7f4a8b3c6e1d9f7a4b2c8e5d3f9a6b4c1e7d2f8a5"
private const val cinemaosSecondaryKey = "d3f8a5b2c9e6d1f7a4b8c5e2d9f3a6b1c7e4d8f2a9b5c3e7d4f1a8b6c2e9d5f3"
private const val cinemaosEncKeyHex = "a1b2c3d4e4f6477658455678901477567890abcdef1234567890abcdef123456"
private const val cinemaosGt = "6775dc8e702c08643385273df088c14952c590ddda02d14f"

private const val cinemaosFallbackDomain = "cinemaos.tech"

// Shared across providers in this repo, keyed by "name" - see VidboxProvider's DomainResolver.kt.
private const val domainsJsonUrl =
    "https://raw.githubusercontent.com/satishgumudavelli/SatishCloudStreamPlugin/master/domains.json"

private val cinemaosScrapers = listOf("vf", "rive", "v2")

object TmdbExtractor {

    private val domainResolver = DomainResolver(
        domainsJsonUrl = domainsJsonUrl,
        targetName = "cinemaos",
        fallbackDomain = cinemaosFallbackDomain,
    )

    private fun cinemaosSecret(tmdbId: Int, season: Int?, episode: Int?): String {
        val parts = mutableListOf("tmdbId:$tmdbId")
        if (season != null) parts.add("seasonId:$season")
        if (episode != null) parts.add("episodeId:$episode")
        val content = parts.joinToString("|")
        return hmacSha256Hex(cinemaosSecondaryKey, hmacSha256Hex(cinemaosPrimaryKey, content))
    }

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
        val base = domainResolver.resolveMainUrl()
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
