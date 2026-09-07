package com.movies4u

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.FormBody
import org.json.JSONObject
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

// GDFlix domain-variant subclasses, registered for the app-wide extractor registry's benefit
// (per spec Assumptions) - this provider's own dispatch (Movies4uExtractor.resolve()) matches
// the host in the link it already found and calls GDFlixExtractor directly, same as every other
// mirror type here.
class GDLink : GDFlixExtractor() {
    override val mainUrl = "https://gdlink.*"
}
class GDFlixNet : GDFlixExtractor() {
    override val mainUrl = "https://(.*gdflix|gdlink).*"
}

// Adapted from the reference implementation (MoviesDrive's Extractors.kt): its filename/size
// selectors (ul > li.list-group-item) and mirror-button container (div.text-center a) were
// confirmed to match movies4u.ag's real gdflix.dev pages as-is - unlike HubCloud, no scoping fix
// was needed here (research.md, feature 003-movies4u-extractor-upgrade).
open class GDFlixExtractor : ExtractorApi() {
    override val name = "GDFlix"
    override val mainUrl = "https://(.*gdflix|gdlink).*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val latestUrl = getLatestUrl(url, "gdflix")
        val fileUrl = url.replace(getBaseUrl(url), latestUrl)
        val document = runCatching { app.get(fileUrl).document }.getOrNull() ?: return

        val fileName = document.select("ul > li.list-group-item:contains(Name)").text().substringAfter("Name : ")
        val fileSize = document.select("ul > li.list-group-item:contains(Size)").text().substringAfter("Size : ")
        val baseQuality = getIndexQuality(fileName)

        val fastServers = mutableListOf<org.jsoup.nodes.Element>()
        val slowServers = mutableListOf<org.jsoup.nodes.Element>()

        document.select("div.text-center a").forEach { anchor ->
            val text = anchor.text()
            val href = anchor.attr("href")
            if (text.contains("FAST CLOUD", true) || text.contains("ZIPDISK", true) ||
                text.contains("ZIP", true) || href.contains(".zip", true)
            ) {
                return@forEach
            }
            when {
                text.contains("Instant DL", true) || text.contains("Direct DL", true) ||
                    text.contains("Direct Server", true) || text.contains("Cloud Download", true) ||
                    href.contains("pixeldra", true) -> fastServers.add(anchor)
                else -> slowServers.add(anchor)
            }
        }

        fastServers.forEach { anchor ->
            val text = anchor.text()
            val link = anchor.attr("href")
            val quality = getAdjustedQuality(baseQuality, fileSize, text, fileName)
            runCatching {
                when {
                    text.contains("Instant DL", true) -> {
                        val instantLink = app.get(link, allowRedirects = false).headers["location"]
                            ?.substringAfter("url=").orEmpty()
                        if (instantLink.isNotEmpty()) {
                            callback(newExtractorLink("GDFlix [Instant]", "GDFlix [Instant] $fileName[$fileSize]", instantLink, ExtractorLinkType.VIDEO) {
                                this.quality = quality
                                this.headers = VIDEO_HEADERS
                            })
                        }
                    }
                    text.contains("Direct DL", true) || text.contains("Direct Server", true) -> {
                        callback(newExtractorLink("GDFlix [Direct]", "GDFlix [Direct] $fileName[$fileSize]", link, ExtractorLinkType.VIDEO) {
                            this.quality = quality
                            this.headers = VIDEO_HEADERS
                        })
                    }
                    text.contains("Cloud Download", true) -> {
                        val cloudLink = URLDecoder.decode(link.substringAfter("url="), StandardCharsets.UTF_8.toString())
                        callback(newExtractorLink("GDFlix [Cloud]", "GDFlix [Cloud] $fileName[$fileSize]", cloudLink, ExtractorLinkType.VIDEO) {
                            this.quality = quality
                            this.headers = VIDEO_HEADERS
                        })
                    }
                    link.contains("pixeldra", true) -> {
                        val base = getBaseUrl(link)
                        val finalUrl = if (link.contains("download", true)) link else "$base/api/file/${link.substringAfterLast("/")}?download"
                        callback(newExtractorLink("Pixeldrain", "GDFlix [Pixeldrain] $fileName[$fileSize]", finalUrl, ExtractorLinkType.VIDEO) {
                            this.quality = quality
                            this.headers = VIDEO_HEADERS
                        })
                    }
                }
            }
        }

        slowServers.amap { anchor ->
            val text = anchor.text()
            val link = anchor.attr("href")
            val quality = getAdjustedQuality(baseQuality, fileSize, text, fileName)
            runCatching {
                when {
                    text.contains("Index Links", true) -> {
                        val indexDoc = app.get("$latestUrl$link").document
                        val firstServer = indexDoc.selectFirst("a.btn.btn-outline-info")
                        if (firstServer != null) {
                            val serverUrl = latestUrl + firstServer.attr("href")
                            val source = app.get(serverUrl).document.selectFirst("div.mb-4 > a")?.attr("href")
                            if (!source.isNullOrBlank()) {
                                callback(newExtractorLink("GDFlix [Index]", "GDFlix [Index] $fileName[$fileSize]", source, ExtractorLinkType.VIDEO) {
                                    this.quality = quality
                                    this.headers = VIDEO_HEADERS
                                })
                            }
                        }
                    }
                    text.contains("DRIVEBOT", true) -> {
                        val id = link.substringAfter("id=").substringBefore("&")
                        val doId = link.substringAfter("do=").substringBefore("==")
                        val driveBotBaseUrl = "https://drivebot.sbs"
                        val indexbotResponse = app.get("$driveBotBaseUrl/download?id=$id&do=$doId", timeout = 30000L)
                        if (indexbotResponse.isSuccessful) {
                            val sessionId = indexbotResponse.cookies["PHPSESSID"]
                            val indexbotHtml = indexbotResponse.document.toString()
                            val token = Regex("""formData\.append\('token', '([a-f0-9]+)'\)""").find(indexbotHtml)?.groupValues?.get(1).orEmpty()
                            val postId = Regex("""fetch\('/download\?id=([a-zA-Z0-9/+]+)'""").find(indexbotHtml)?.groupValues?.get(1).orEmpty()
                            val downloadLink = app.post(
                                "$driveBotBaseUrl/download?id=$postId",
                                requestBody = FormBody.Builder().add("token", token).build(),
                                headers = mapOf("Referer" to "$driveBotBaseUrl/download?id=$id&do=$doId"),
                                cookies = mapOf("PHPSESSID" to "$sessionId"),
                                timeout = 30000L
                            ).text.let { Regex("url\":\"(.*?)\"").find(it)?.groupValues?.get(1)?.replace("\\", "").orEmpty() }
                            if (downloadLink.isNotEmpty()) {
                                callback(newExtractorLink("GDFlix [DriveBot]", "GDFlix [DriveBot] $fileName[$fileSize]", downloadLink, ExtractorLinkType.VIDEO) {
                                    this.referer = driveBotBaseUrl
                                    this.quality = quality
                                    this.headers = VIDEO_HEADERS
                                })
                            }
                        }
                    }
                    text.contains("GoFile", true) -> {
                        val gofileLink = app.get(link).document.selectFirst(".row .row a")?.attr("href")
                        if (gofileLink?.contains("gofile") == true) {
                            GofileExtractor().getUrl(gofileLink, "", subtitleCallback, callback)
                        }
                    }
                }
            }
        }
    }
}
