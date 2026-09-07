package com.movies4u

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink

// Adapted from the reference implementation (MoviesDrive's Extractors.kt), with two fixes found
// by checking it against a real movies4u.ag capture (research.md Decisions 1-2 of feature
// 003-movies4u-extractor-upgrade):
//  - buttons are matched unscoped (a.btn across the whole page), NOT scoped to a div.card-body -
//    that div only wraps the file-info block (File Size/Type/Share Date) on this site's actual
//    template, not the mirror buttons; scoping to it would find zero buttons.
//  - "Buzz Server" is matched with its real, space-containing button text ("Buzz Server", not
//    "BuzzServer"), and its href is used directly - movies4u.ag's Buzz Server button already IS
//    the final playable link, unlike the reference's redirect-follow handling for a different
//    HubCloud deployment variant.
class HubCloudExtractor : ExtractorApi() {
    override val name = "Hub-Cloud"
    override val mainUrl = "https://hubcloud.*"
    override val requiresReferer = false

    private val skipLabels = listOf("telegram", "watch online", "with android app")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val latestUrl = getLatestUrl(url, "hubcloud")
        val driveUrl = url.replace(getBaseUrl(url), latestUrl)
        val driveDoc = runCatching { app.get(driveUrl).document }.getOrNull() ?: return

        // The drive page reveals the "generate" page either through a static #download href
        // (hubcloud.cx) or - on the VegaCloud-branded clone (vcloud.fit) - a
        // `var url = atob(atob('...'))` script next to a href-less #download button.
        val generateHref = driveDoc.selectFirst("#download")?.attr("href")?.takeIf { it.isNotBlank() && it != "#" }
            ?: driveDoc.select("script").asSequence()
                .mapNotNull { Regex("""atob\(atob\(['"]([^'"]+)['"]\)\)""").find(it.data())?.groupValues?.get(1) }
                .firstOrNull()
                ?.let { runCatching { decodeDoubleBase64(it) }.getOrNull() }
            ?: return

        val finalDoc = runCatching { app.get(generateHref).document }.getOrNull() ?: return
        val finalHtml = finalDoc.html()
        val header = finalDoc.selectFirst("div.card-header")?.text()?.trim() ?: ""
        val size = finalDoc.selectFirst("i#size")?.text()?.takeIf { it.isNotBlank() } ?: ""
        val baseQuality = getIndexQuality(header)

        finalDoc.select("a.btn").forEach { el ->
            val label = el.text().trim()
            if (label.isBlank() || skipLabels.any { label.contains(it, ignoreCase = true) }) return@forEach

            var href = el.attr("href")
            if (!href.startsWith("http")) return@forEach

            // PixelServer ships a stale href in the raw HTML that an inline script corrects
            // right after the anchor - read that from the page source instead of the tag itself.
            val id = el.attr("id").takeIf { it.isNotBlank() }
            if (id != null) {
                Regex("""getElementById\("$id"\)\.href\s*=\s*["']([^"']+)["']""")
                    .find(finalHtml)?.groupValues?.get(1)?.let { href = it }
            }

            if (isZipUrl(href)) {
                invokeZipEpisodes(href, header.ifBlank { label }, callback)
                return@forEach
            }

            if (label.contains("Server : 10Gbps", ignoreCase = true) || label.contains("10Gbps", ignoreCase = true)) {
                val dlink = runCatching { app.get(href, allowRedirects = false).headers["location"] }.getOrNull().orEmpty()
                val finalUrl = if (dlink.contains("link=")) dlink.substringAfter("link=") else dlink
                if (finalUrl.isNotEmpty() && !isZipUrl(finalUrl)) {
                    emit(callback, name, label, header, size, finalUrl, baseQuality)
                }
                return@forEach
            }

            // Buzz Server, FSL/FSLv2/Mega Server, generic "Download File", and any other button
            // whose href already points at the final file (direct .mkv/.mp4, or unrecognized but
            // still a plausible direct link) all resolve the same way here: pass the href
            // straight through.
            emit(callback, name, label, header, size, href, baseQuality)
        }
    }

    private suspend fun emit(
        callback: (ExtractorLink) -> Unit,
        source: String,
        label: String,
        header: String,
        size: String,
        link: String,
        baseQuality: Int
    ) {
        val quality = getAdjustedQuality(baseQuality, size, label, header)
        callback(
            newExtractorLink("$source [$label]", "$source [$label] $header${if (size.isNotBlank()) " [$size]" else ""}", link, ExtractorLinkType.VIDEO) {
                this.quality = quality
                this.headers = VIDEO_HEADERS
            }
        )
    }

    private fun decodeDoubleBase64(value: String): String {
        val once = String(Base64.decode(value, Base64.DEFAULT))
        return String(Base64.decode(once, Base64.DEFAULT))
    }
}
