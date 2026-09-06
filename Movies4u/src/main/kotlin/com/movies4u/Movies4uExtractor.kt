package com.movies4u

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLDecoder

object Movies4uExtractor {

    private val skipLabels = listOf("telegram", "watch online", "with android app")
    private val directExtensions = listOf(".mkv", ".mp4", ".avi", ".mov")

    // Dispatches every mirror link found on an mdrive.cloud page to the right resolver.
    // Verified against real captures: HubCloud (hubcloud.cx) and its VegaCloud-branded clone
    // (vcloud.fit) share the same "generate link" -> mirror-buttons template, HubCDN just wraps
    // an already-final CDN url in a query param, and Gofile/PixelDrain are handled by cloudstream
    // core's own built-in extractors. Filepress/vegadrive/1fichier/vikingfile/fastdl mirrors are
    // skipped: their pages are JS-driven (React SPA) or were unreachable/expired when checked, so
    // there's nothing verified to resolve them against.
    suspend fun resolve(
        href: String,
        quality: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val lower = href.lowercase()
        when {
            lower.contains("hubcloud") || lower.contains("vcloud") -> invokeHubCloud(href, quality, callback)
            lower.contains("hubcdn.") -> invokeHubCdn(href, quality, callback)
            lower.contains("gofile.io") || lower.contains("pixeldrain") ->
                runCatching { loadExtractor(href, href, subtitleCallback, callback) }
            directExtensions.any { lower.substringBefore("?").endsWith(it) } ->
                callback(
                    newExtractorLink("Movies4u", "Movies4u $quality", href, ExtractorLinkType.VIDEO) {
                        this.quality = getQualityFromName(quality)
                    }
                )
        }
    }

    // HubCloud-family flow: the drive page reveals the "generate" page either through a static
    // #download href (hubcloud.cx) or - on the VegaCloud clone - a `var url = atob(atob('...'))`
    // script next to a href-less #download button (vcloud.fit). The generate page then lists
    // several mirror buttons (FSL/FSLv2/10Gbps/PixelServer/Buzz...), each a direct file link; one
    // of them (PixelServer) ships a stale href in the raw HTML that an inline script corrects
    // right after the anchor - read that from the page source instead of the tag itself.
    private suspend fun invokeHubCloud(url: String, quality: String, callback: (ExtractorLink) -> Unit) {
        val driveDoc = runCatching { app.get(url).document }.getOrNull() ?: return

        val generateHref = driveDoc.selectFirst("#download")?.attr("href")?.takeIf { it.isNotBlank() && it != "#" }
            ?: driveDoc.select("script").asSequence()
                .mapNotNull { Regex("""atob\(atob\(['"]([^'"]+)['"]\)\)""").find(it.data())?.groupValues?.get(1) }
                .firstOrNull()
                ?.let { runCatching { decodeDoubleBase64(it) }.getOrNull() }
            ?: return

        val finalDoc = runCatching { app.get(generateHref).document }.getOrNull() ?: return
        val finalHtml = finalDoc.html()
        val size = finalDoc.selectFirst("i#size")?.text()?.takeIf { it.isNotBlank() }

        finalDoc.select("a.btn").forEach { el ->
            val label = el.text().trim()
            if (label.isBlank() || skipLabels.any { label.contains(it, ignoreCase = true) }) return@forEach

            var href = el.attr("href")
            if (!href.startsWith("http")) return@forEach

            val id = el.attr("id").takeIf { it.isNotBlank() }
            if (id != null) {
                Regex("""getElementById\("$id"\)\.href\s*=\s*["']([^"']+)["']""")
                    .find(finalHtml)?.groupValues?.get(1)?.let { href = it }
            }

            val name = "HubCloud [$label] $quality" + (size?.let { " ($it)" } ?: "")
            callback(
                newExtractorLink("HubCloud", name, href, ExtractorLinkType.VIDEO) {
                    this.quality = getQualityFromName(quality)
                }
            )
        }
    }

    private fun decodeDoubleBase64(value: String): String {
        val once = String(Base64.decode(value, Base64.DEFAULT))
        return String(Base64.decode(once, Base64.DEFAULT))
    }

    // Hub CDN just wraps an already-final CDN url as a query param and redirects to it after an
    // ad gate - the param itself is the workable link, no request needed to resolve it.
    private suspend fun invokeHubCdn(href: String, quality: String, callback: (ExtractorLink) -> Unit) {
        val raw = Regex("""[?&]link=(.+)$""").find(href)?.groupValues?.get(1) ?: return
        val finalUrl = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        callback(
            newExtractorLink("HubCDN", "HubCDN $quality", finalUrl, ExtractorLinkType.VIDEO) {
                this.quality = getQualityFromName(quality)
            }
        )
    }
}
