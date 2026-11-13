package com.vegaprovider.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder

// Adapted from the reference implementation (MoviesDrive's Extractors.kt) - shared by
// HubCloudExtractor/GDFlixExtractor/GofileExtractor. This scoring scheme and domain-rotation
// source are self-contained (no site-specific selectors), so kept as-is per research.md
// Decision 5; domain rotation is Decision 4 (a separate mechanism from this provider's own
// DomainResolver/domains.json, which only ever covered movies4u.ag's own domain).

val VIDEO_HEADERS = mapOf(
    "User-Agent" to "VLC/3.6.0 LibVLC/3.0.18 (Android)",
    "Accept" to "*/*",
    "Accept-Encoding" to "identity",
    "Connection" to "keep-alive",
    "Range" to "bytes=0-",
    "Icy-MetaData" to "1"
)

fun getIndexQuality(str: String?): Int =
    Regex("""(\d{3,4})[pP]""").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0

fun getBaseUrl(url: String): String = URI(url).let { "${it.scheme}://${it.host}" }

fun parseSizeToMB(sizeStr: String): Double {
    val cleanSize = sizeStr.replace("[", "").replace("]", "").trim()
    val match = Regex("""([\d.]+)\s*(GB|MB|gb|mb)""", RegexOption.IGNORE_CASE).find(cleanSize)
        ?: return Double.MAX_VALUE
    val value = match.groupValues[1].toDoubleOrNull() ?: return Double.MAX_VALUE
    return when (match.groupValues[2].uppercase()) {
        "GB" -> value * 1024
        else -> value
    }
}

fun getServerPriority(serverName: String): Int = when {
    serverName.contains("Instant", true) -> 100
    serverName.contains("Direct", true) -> 90
    serverName.contains("10Gbps", true) -> 85
    serverName.contains("FSL", true) -> 80
    serverName.contains("Download File", true) -> 70
    serverName.contains("Pixeldrain", true) -> 60
    else -> 50
}

// Priority order: codec+quality tier first (X264 1080p > X264 720p > HEVC 1080p > ...), then
// smaller file size within that tier, then faster server within that tier+size.
fun getAdjustedQuality(quality: Int, sizeStr: String, serverName: String = "", fileName: String = ""): Int {
    val text = (fileName + sizeStr + serverName).lowercase()
    val isHEVC = text.contains("hevc") || text.contains("x265") || text.contains("h265") || text.contains("h.265")
    val isX264 = text.contains("x264") || text.contains("h264") || text.contains("h.264")

    val codecQualityScore = when {
        isX264 && quality >= 1080 -> 30000
        isX264 && quality >= 720 -> 20000
        isHEVC && quality >= 1080 -> 10000
        isHEVC && quality >= 720 -> 9000
        quality >= 1080 -> 8000
        quality >= 720 -> 7000
        quality >= 480 -> 6000
        else -> 5000
    }

    val sizeMB = parseSizeToMB(sizeStr)
    val sizeScore = when {
        sizeMB <= 300 -> 260
        sizeMB <= 400 -> 250
        sizeMB <= 500 -> 240
        sizeMB <= 600 -> 230
        sizeMB <= 700 -> 220
        sizeMB <= 800 -> 210
        sizeMB <= 900 -> 200
        sizeMB <= 1000 -> 190
        sizeMB <= 1200 -> 170
        sizeMB <= 1500 -> 140
        sizeMB <= 2000 -> 100
        sizeMB <= 2500 -> 60
        sizeMB <= 3000 -> 20
        else -> 0
    }

    return codecQualityScore + sizeScore + getServerPriority(serverName)
}

// Session-level cache (fetch once, reuse for the process lifetime) of the reference's
// live domain-rotation JSON, keyed by mirror-service name (e.g. "hubcloud", "gdflix", "gofile").
private var cachedUrlsJson: JSONObject? = null

suspend fun getLatestUrl(url: String, source: String): String {
    if (cachedUrlsJson == null) {
        cachedUrlsJson = runCatching {
            JSONObject(app.get("https://raw.githubusercontent.com/codeiva4u/Utils-repo/refs/heads/main/urls.json").text)
        }.getOrNull()
    }
    val link = cachedUrlsJson?.optString(source)
    return link?.takeIf { it.isNotEmpty() } ?: getBaseUrl(url)
}

// "Complete Season" batch releases serve a .zip (the whole season packed together) on every
// mirror, not a playable video - the real filename usually only shows up decoded (an R2
// presigned url carries it inside a response-content-disposition query param, not the path),
// so decode before checking. Shared by every mirror resolver (HubCloud/GDFlix/HubCDN/fastdl/
// Filepress), not just the ExtractorApi classes in this file.
fun isZipUrl(url: String): Boolean =
    runCatching { URLDecoder.decode(url, "UTF-8") }.getOrDefault(url).contains(".zip", ignoreCase = true)

// A presigned R2/S3 url's signature is method-specific - it authorizes GET, not HEAD, which

// ponytail: season .zip batches are skipped (no per-episode zip proxy in this plugin); movies4u has one if needed.
@Suppress("UNUSED_PARAMETER")
suspend fun invokeZipEpisodes(zipUrl: String, quality: String, callback: (ExtractorLink) -> Unit) = Unit
