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

    // "Complete Season" batch releases serve a .zip (the whole season packed together) on every
    // mirror, not a playable video - the real filename usually only shows up decoded (an R2
    // presigned url carries it inside a response-content-disposition query param, not the path),
    // so decode before checking. Emitting it as a VIDEO link just gets ExoPlayer a container it
    // can't sniff (UnrecognizedInputFormatException) - better to emit nothing for it than a
    // source that's guaranteed to fail.
    private fun isZipUrl(url: String): Boolean =
        runCatching { URLDecoder.decode(url, "UTF-8") }.getOrDefault(url).contains(".zip", ignoreCase = true)

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
    // right resolver. Verified against real captures: HubCloud (hubcloud.cx) and its
    // VegaCloud-branded clone (vcloud.fit) share the same "generate link" -> mirror-buttons
    // template, HubCDN and fastdl.zip both just wrap an already-final CDN url in a query/JS
    // param, GDFlix exposes a couple of already-resolved CDN mirrors as plain links (no captcha
    // needed for those), and Gofile/PixelDrain are handled by cloudstream core's own built-in
    // extractors. Filepress/vegadrive/1fichier/vikingfile mirrors are skipped: their pages are
    // JS-driven (React SPA) or were unreachable/expired when checked, so there's nothing
    // verified to resolve them against.
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
            lower.contains("gdflix") -> invokeGdflix(href, quality, callback)
            lower.contains("fastdl.zip") -> invokeFastdl(href, quality, callback)
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
            if (isZipUrl(href)) return@forEach

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
        if (isZipUrl(finalUrl)) return
        callback(
            newExtractorLink("HubCDN", "HubCDN $quality", finalUrl, ExtractorLinkType.VIDEO) {
                this.quality = getQualityFromName(quality)
            }
        )
    }

    // GDFlix's file page normally gates its fast mirrors behind a Cloudflare Turnstile captcha
    // (an "original"/"direct" POST using a token from solving it), but for a plain (non-premium)
    // file those captcha-only buttons just aren't rendered at all - what's left in the file-info
    // card body are a handful of already-resolved absolute mirrors (Instant DL, a direct R2.dev
    // CDN link) alongside a login-gated 10Gbps option, a Telegram bot deep link, a Multiup/Gofile
    // aggregator (rotates domains - blacklisting them one at a time is a losing game) and an
    // unverified "DIRECT SERVER" index redirect. The card body also has a plain, class-less
    // "Shared By" attribution link (e.g. movies4u.ws) that isn't a mirror at all but does start
    // with "http" - scoping to real button anchors (a.btn) excludes it. Whitelist only the two
    // mirrors confirmed to be already-resolved direct CDN files instead of trying to blacklist
    // every non-mirror/aggregator link that shows up.
    private suspend fun invokeGdflix(url: String, quality: String, callback: (ExtractorLink) -> Unit) {
        val doc = runCatching { app.get(url).document }.getOrNull() ?: return
        doc.select(".card-body a.btn[href^=http]").forEach { a ->
            val href = a.attr("href")
            val label = a.text().trim()
            if (label.isBlank()) return@forEach
            val isVerifiedMirror = label.contains("instant", ignoreCase = true) ||
                label.contains("r2", ignoreCase = true) ||
                href.contains("r2.dev", ignoreCase = true)
            if (!isVerifiedMirror || isZipUrl(href)) return@forEach

            callback(
                newExtractorLink("GDFlix", "GDFlix [$label] $quality", href, ExtractorLinkType.VIDEO) {
                    this.quality = getQualityFromName(quality)
                }
            )
        }
    }

    // fastdl.zip's embed page is a plain redirect stub: a `var reurl = "https://fastdl.zip/dl.php
    // ?link=<real file url>"` sits right in its inline JS - same "link= param is the final url"
    // shape as HubCDN, so pull it straight out instead of following the redirect page itself.
    private suspend fun invokeFastdl(url: String, quality: String, callback: (ExtractorLink) -> Unit) {
        val body = runCatching { app.get(url).text }.getOrNull() ?: return
        val reurl = Regex("""var reurl\s*=\s*"([^"]+)"""").find(body)?.groupValues?.get(1) ?: return
        val raw = Regex("""[?&]link=(.+)$""").find(reurl)?.groupValues?.get(1)
        val finalUrl = raw?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) } ?: reurl
        if (isZipUrl(finalUrl)) return
        callback(
            newExtractorLink("FastDL", "FastDL $quality", finalUrl, ExtractorLinkType.VIDEO) {
                this.quality = getQualityFromName(quality)
            }
        )
    }
}
