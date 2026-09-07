package com.movies4u

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

object Movies4uExtractor {

    private val directExtensions = listOf(".mkv", ".mp4", ".avi", ".mov")

    // A movie's mdrive.cloud page comes in two shapes: a single-quality page whose mirror
    // buttons sit directly under #container-content-single, or a multi-quality page (several of
    // the post's own quality buttons happen to share one mdrive link) where each quality gets its
    // own <h4> label followed by its mirror buttons. Either way the buttons can be wrapped in a
    // <p> or a <div class="downloads-btns-div"> - the site isn't consistent about it - so select
    // anchors directly instead of assuming one wrapper.
    suspend fun resolveMdrivePage(
        mdriveUrl: String,
        fallbackQuality: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = runCatching { app.get(mdriveUrl).document }.getOrNull() ?: return
        val container = doc.selectFirst("#container-content-single") ?: return
        val qualityHeaders = container.select("h4")

        if (qualityHeaders.isEmpty()) {
            container.select("a[href^=http]").distinctBy { it.attr("href") }.forEach { a ->
                resolve(a.attr("href"), fallbackQuality, subtitleCallback, callback)
            }
        } else {
            qualityHeaders.forEach { h4 ->
                val quality = h4.text().trim().ifBlank { fallbackQuality }
                h4.nextElementSibling()?.select("a[href^=http]")?.forEach { a ->
                    resolve(a.attr("href"), quality, subtitleCallback, callback)
                }
            }
        }
    }

    // Dispatches a single mirror link (from a mdrive.cloud page or a series episode) to the
    // right resolver. HubCloud (hubcloud.cx/vcloud.fit), GDFlix (+ GDLink/GDFlixNet domain
    // variants), and Gofile are adapted from a production-tested reference implementation
    // (MoviesDrive's Extractors.kt) rather than hand-rolled here - see HubCloudExtractor.kt/
    // GDFlixExtractor.kt/GofileExtractor.kt. HubCDN and fastdl.zip both just wrap an
    // already-final CDN url in a query/JS param, Filepress/filebee's frontend is a JS-rendered
    // SPA but its underlying /api/file/downlaod(2)/ REST endpoint works headlessly, and
    // PixelDrain is handled by cloudstream core's own built-in extractor. Any of these that
    // turns out to be a "Complete Season" .zip gets expanded into its individual episodes via
    // invokeZipEpisodes instead of being emitted as-is. vegadrive/1fichier/vikingfile mirrors
    // are skipped: unreachable/expired when checked, so there's nothing verified to resolve
    // them against.
    suspend fun resolve(
        href: String,
        quality: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val lower = href.lowercase()
        when {
            lower.contains("hubcloud") || lower.contains("vcloud") ->
                HubCloudExtractor().getUrl(href, href, subtitleCallback, callback)
            lower.contains("hubcdn.") -> invokeHubCdn(href, quality, callback)
            lower.contains("gdflix") || lower.contains("gdlink") ->
                GDFlixExtractor().getUrl(href, href, subtitleCallback, callback)
            lower.contains("fastdl.zip") -> invokeFastdl(href, quality, callback)
            lower.contains("filepress") || lower.contains("filebee") -> invokeFilepress(href, quality, callback)
            lower.contains("gofile.io") -> GofileExtractor().getUrl(href, href, subtitleCallback, callback)
            lower.contains("pixeldrain") -> runCatching { loadExtractor(href, href, subtitleCallback, callback) }
            directExtensions.any { lower.substringBefore("?").endsWith(it) } ->
                callback(
                    newExtractorLink("Movies4u", "Movies4u $quality", href, ExtractorLinkType.VIDEO) {
                        this.quality = getQualityFromName(quality)
                    }
                )
        }
    }

    // Hub CDN just wraps an already-final CDN url as a query param and redirects to it after an
    // ad gate - the param itself is the workable link, no request needed to resolve it.
    private suspend fun invokeHubCdn(href: String, quality: String, callback: (ExtractorLink) -> Unit) {
        val raw = Regex("""[?&]link=(.+)$""").find(href)?.groupValues?.get(1) ?: return
        val finalUrl = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        if (isZipUrl(finalUrl)) {
            invokeZipEpisodes(finalUrl, quality, callback)
            return
        }
        callback(
            newExtractorLink("HubCDN", "HubCDN $quality", finalUrl, ExtractorLinkType.VIDEO) {
                this.quality = getQualityFromName(quality)
            }
        )
    }

    // fastdl.zip's embed page is a plain redirect stub: a `var reurl = "https://fastdl.zip/dl.php
    // ?link=<real file url>"` sits right in its inline JS - same "link= param is the final url"
    // shape as HubCDN, so pull it straight out instead of following the redirect page itself.
    private suspend fun invokeFastdl(url: String, quality: String, callback: (ExtractorLink) -> Unit) {
        val body = runCatching { app.get(url).text }.getOrNull() ?: return
        val reurl = Regex("""var reurl\s*=\s*"([^"]+)"""").find(body)?.groupValues?.get(1) ?: return
        val raw = Regex("""[?&]link=(.+)$""").find(reurl)?.groupValues?.get(1)
        val finalUrl = raw?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) } ?: reurl
        if (isZipUrl(finalUrl)) {
            invokeZipEpisodes(finalUrl, quality, callback)
            return
        }
        callback(
            newExtractorLink("FastDL", "FastDL $quality", finalUrl, ExtractorLinkType.VIDEO) {
                this.quality = getQualityFromName(quality)
            }
        )
    }

    // Filepress/filebee's own page (`/file/<id>`) is a React SPA - nothing to scrape from its
    // static HTML - but the site's REST API behind it doesn't care that the request isn't coming
    // from that app: POST the id to /api/file/downlaod/ (their typo, not mine) for a one-time
    // token, then POST that token to /api/file/downlaod2/ for the real stream url.
    private fun postJson(url: String, body: JSONObject, referer: String): String? = runCatching {
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Referer", referer)
            connectTimeout = 15000
            readTimeout = 20000
            outputStream.use { it.write(body.toString().toByteArray()) }
        }.inputStream.bufferedReader().use { it.readText() }
    }.getOrNull()

    private suspend fun invokeFilepress(url: String, quality: String, callback: (ExtractorLink) -> Unit) {
        val parsed = runCatching { URL(url) }.getOrNull() ?: return
        val origin = "${parsed.protocol}://${parsed.host}"
        val id = parsed.path.trimEnd('/').substringAfterLast('/').takeIf { it.isNotBlank() } ?: return

        fun request(id: String) = JSONObject().put("id", id).put("method", "indexDownlaod").put("captchaValue", JSONObject.NULL)

        val token = postJson("$origin/api/file/downlaod/", request(id), origin)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.takeIf { it.optBoolean("status") }
            ?.optString("data")?.takeIf { it.isNotBlank() } ?: return

        val finalUrl = postJson("$origin/api/file/downlaod2/", request(token), origin)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.optJSONObject("data")?.optJSONArray("data")?.optString(0)?.takeIf { it.isNotBlank() } ?: return

        if (isZipUrl(finalUrl)) return
        callback(
            newExtractorLink("Filepress", "Filepress $quality", finalUrl, ExtractorLinkType.VIDEO) {
                this.quality = getQualityFromName(quality)
            }
        )
    }
}
