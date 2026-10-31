package com.almostott

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.net.URI

// The site's own player page just iframes third-party SPAs (vidfast/vidlink/vidstuck/... - their
// streams only resolve inside a browser), but its Download panel calls a plain JSON API
// (tgdl.lovable.app) keyed by the same TMDB id that returns file lists with quality/size/audio.
// Each download_url is `https://tgdl.lovable.app/dl?u=<base64 of the real file URL>`; decoding it
// yields the real host, so direct-playable hosts are handed to the player with no browser step.
object AlmostOttExtractor {
    private const val TAG = "AlmostOTT"
    private const val api = "https://tgdl.lovable.app"
    private const val siteOrigin = "https://almostott.aethoflix.world"
    private val apiHeaders = mapOf("Origin" to siteOrigin, "Referer" to "$siteOrigin/")

    // Hindi-first like the site's own audio parser; keyed on the "[...]" block, else tokens.
    private val languages = listOf(
        "Hindi", "English", "Tamil", "Telugu", "Malayalam", "Kannada", "Bengali", "Punjabi",
        "Marathi", "Japanese", "Korean", "Spanish",
    )

    private fun audioOf(name: String): String {
        val found = languages.filter { Regex("\\b${it.take(3)}[a-z]*\\b", RegexOption.IGNORE_CASE).containsMatchIn(name) }
        return when {
            found.isNotEmpty() -> found.joinToString("+")
            Regex("\\bdual\\b", RegexOption.IGNORE_CASE).containsMatchIn(name) -> "Dual"
            Regex("\\bmulti\\b", RegexOption.IGNORE_CASE).containsMatchIn(name) -> "Multi"
            else -> ""
        }
    }

    // Presigned R2 URLs carry X-Amz-Date + X-Amz-Expires; the API serves stale cached ones as
    // well (live: FSLv2 -> 403 ExpiredRequest while FSL from the same response -> 206).
    private fun isExpiredPresigned(url: String): Boolean {
        val date = Regex("""[?&]X-Amz-Date=(\d{8}T\d{6})Z""").find(url)?.groupValues?.get(1) ?: return false
        val ttl = Regex("""[?&]X-Amz-Expires=(\d+)""").find(url)?.groupValues?.get(1)?.toLongOrNull() ?: return false
        val signedAt = runCatching {
            java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss", java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(date)?.time
        }.getOrNull() ?: return false
        return System.currentTimeMillis() > signedAt + ttl * 1000
    }

    private fun decode(downloadUrl: String): String? {
        val u = Regex("""[?&]u=([^&]+)""").find(downloadUrl)?.groupValues?.get(1) ?: return downloadUrl.takeIf { it.startsWith("https://") }
        return runCatching { String(Base64.decode(u, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)) }.getOrNull()
    }

    private suspend fun fetch(version: String, isMovie: Boolean, id: Int, season: Int?, episode: Int?): List<JSONObject> {
        val prefix = if (version == "v2") "/api/v2" else "/api"
        val path = if (isMovie) "$prefix/movie/$id" else "$prefix/tv/$id/$season/$episode"
        val res = runCatching { app.get("$api$path", headers = apiHeaders, timeout = 30) }.getOrElse {
            Log.w(TAG, "$version $path failed: ${it.message}"); return emptyList()
        }
        if (res.code != 200) { Log.d(TAG, "$version $path -> HTTP ${res.code}"); return emptyList() }
        val json = runCatching { JSONObject(res.text) }.getOrNull() ?: return emptyList()
        if (!json.optBoolean("success", true)) return emptyList()
        val arr = json.optJSONArray("downloads")
        return if (arr != null && arr.length() > 0) (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        else if (json.has("download_url")) listOf(json) else emptyList()
    }

    // The player (tgvid.lovable.app) streams through vidsync.pro: a plain JSON API keyed by TMDB id.
    // Each source's `url` is vidsync's own HLS proxy (works with no headers, live-checked for
    // movies and TV); its `data` query param is JSON carrying audio language + quality.
    private const val streamApi = "https://vidsync.pro"
    private val streamHeaders = mapOf("Origin" to "https://tgvid.lovable.app", "Referer" to "https://tgvid.lovable.app/")

    private suspend fun invokeStream(isMovie: Boolean, id: Int, season: Int?, episode: Int?, callback: (ExtractorLink) -> Unit): Boolean {
        val type = if (isMovie) "movie" else "tv"
        val res = runCatching {
            app.get("$streamApi/api/stream?type=$type&id=$id&season=${season ?: 1}&episode=${episode ?: 1}", headers = streamHeaders, timeout = 30)
        }.getOrElse { Log.w(TAG, "vidsync failed: ${it.message}"); return false }
        if (res.code != 200) { Log.d(TAG, "vidsync -> HTTP ${res.code}"); return false }
        val sources = runCatching { JSONObject(res.text).optJSONArray("sources") }.getOrNull() ?: return false
        val seen = HashSet<String>()
        var emitted = false
        for (i in 0 until sources.length()) {
            val s = sources.optJSONObject(i) ?: continue
            val path = s.optString("url").takeIf { it.startsWith("/api/") } ?: continue
            if (!seen.add(path)) continue
            val data = runCatching {
                JSONObject(java.net.URLDecoder.decode(Regex("""[?&]data=([^&]+)""").find(path)?.groupValues?.get(1).orEmpty(), "UTF-8"))
            }.getOrNull()
            if (data?.optString("streamType") != "hls") continue
            val q = data.optString("quality")
            val quality = getQualityFromName(q).takeIf { it != Qualities.Unknown.value } ?: Qualities.Unknown.value
            val server = s.optString("server").ifBlank { "vidsync" }
            val provider = s.optJSONObject("provider")?.optString("name").orEmpty()
            val audio = data.optString("audio").uppercase().takeIf { it.isNotBlank() }
            val label = listOfNotNull("AlmostOTT", server, provider.takeIf { it.isNotBlank() && it != server }, q.takeIf { it.isNotBlank() }, audio).joinToString(" · ")
            callback(newExtractorLink("AlmostOTT", label, "$streamApi$path", ExtractorLinkType.M3U8) {
                this.quality = quality
                this.referer = ""
            })
            emitted = true
        }
        return emitted
    }

    suspend fun invoke(isMovie: Boolean, id: Int, season: Int?, episode: Int?, callback: (ExtractorLink) -> Unit): Boolean {
        val streams = invokeStream(isMovie, id, season, episode, callback)
        return invokeDownloads(isMovie, id, season, episode, callback) || streams
    }

    private suspend fun invokeDownloads(isMovie: Boolean, id: Int, season: Int?, episode: Int?, callback: (ExtractorLink) -> Unit): Boolean {
        // v2 first like the site; v1 only when v2 has nothing (site does the same).
        val files = fetch("v2", isMovie, id, season, episode).ifEmpty { fetch("v1", isMovie, id, season, episode) }
        if (files.isEmpty()) return false

        val seen = HashSet<String>()
        var emitted = false
        for (f in files) {
            val real = decode(f.optString("download_url")) ?: continue
            if (!seen.add(real)) continue
            val host = runCatching { URI(real).host.orEmpty() }.getOrDefault("")
            val path = runCatching { URI(real).path.orEmpty() }.getOrDefault("")
            // PixelDrain stays a direct link rather than going through core's PixelDrain extractor
            // (host pixeldrain.com; same ids resolve there): that extractor emits a bare
            // "PixelDrain" link with unknown quality, which would drop the quality/size/audio
            // label FR-007 needs. Verified by inspecting the core class + a ranged HEAD, not on-device.
            val type = when {
                path.endsWith(".m3u8", true) -> ExtractorLinkType.M3U8
                host.endsWith("pixeldrain.dev") || host.endsWith("pixeldrain.com") ||
                    host.endsWith(".r2.dev") -> ExtractorLinkType.VIDEO
                host.endsWith(".r2.cloudflarestorage.com") -> {
                    if (isExpiredPresigned(real)) { Log.d(TAG, "skip expired presigned $host"); continue }
                    ExtractorLinkType.VIDEO
                }
                else -> {
                    // hubcloud/hubdrive are interstitial pages and googleusercontent links were never
                    // verified playable - not directly playable / not guessed at.
                    Log.d(TAG, "skip unplayable host $host")
                    continue
                }
            }
            val name = f.optString("name")
            val qualityText = f.optString("quality").takeIf { it.isNotBlank() && it != "null" } ?: name
            val quality = getQualityFromName(qualityText).takeIf { it != Qualities.Unknown.value }
                ?: Regex("""(\d{3,4})p""").find(name)?.groupValues?.get(1)?.toIntOrNull()
                ?: Qualities.Unknown.value
            val server = (f.optString("server").takeIf { it.isNotBlank() && it != "null" } ?: host).trim()
            val size = f.optString("size").takeIf { it.isNotBlank() && it != "null" }
            val audio = audioOf(name)
            val label = listOfNotNull("AlmostOTT", server, size, audio.takeIf { it.isNotEmpty() }).joinToString(" · ")
            callback(newExtractorLink("AlmostOTT", label, real, type) {
                this.quality = quality
                this.referer = ""
            })
            emitted = true
        }
        return emitted
    }
}
