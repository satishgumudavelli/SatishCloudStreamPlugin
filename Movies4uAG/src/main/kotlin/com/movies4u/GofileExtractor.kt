package com.movies4u

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

// Adapted from the reference implementation (MoviesDrive's Extractors.kt) - a real working
// Gofile account/token API flow (FR-004), replacing this provider's previous delegation to
// cloudstream core's built-in Gofile extractor via loadExtractor, which had no guarantee of
// covering every case this site's Gofile links need.
class GofileExtractor : ExtractorApi() {
    override val name = "Gofile"
    override val mainUrl = "https://gofile.*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val latestMainUrl = getLatestUrl(url, "gofile")
        val latestApiUrl = latestMainUrl.replace("://", "://api.")
        val id = url.substringAfter("d/").substringBefore("/")
        if (id.isBlank()) return

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
            "Origin" to latestMainUrl,
            "Referer" to latestMainUrl,
        )

        val token = runCatching {
            JSONObject(app.post("$latestApiUrl/accounts", headers = headers).text)
                .getJSONObject("data").getString("token")
        }.getOrNull() ?: return

        val websiteToken = runCatching {
            Regex("""appdata\.wt\s*=\s*["']([^"']+)["']""")
                .find(app.get("$latestMainUrl/dist/js/config.js", headers = headers).text)
                ?.groupValues?.get(1)
        }.getOrNull() ?: return

        val contents = runCatching {
            JSONObject(
                app.get(
                    "$latestApiUrl/contents/$id?cache=true&sortField=createTime&sortDirection=1",
                    headers = headers + mapOf("Authorization" to "Bearer $token", "X-Website-Token" to websiteToken)
                ).text
            )
        }.getOrNull() ?: return

        val children = runCatching { contents.getJSONObject("data").getJSONObject("children") }.getOrNull() ?: return
        if (!children.keys().hasNext()) return
        val entry = children.getJSONObject(children.keys().next())

        val link = entry.optString("link").takeIf { it.isNotBlank() } ?: return
        val fileName = entry.optString("name")
        val size = entry.optLong("size")
        val formattedSize = if (size < 1024L * 1024 * 1024) {
            "%.2f MB".format(size.toDouble() / (1024 * 1024))
        } else {
            "%.2f GB".format(size.toDouble() / (1024 * 1024 * 1024))
        }

        callback(
            newExtractorLink(name, "$name $fileName[$formattedSize]", link, ExtractorLinkType.VIDEO) {
                this.quality = getIndexQuality(fileName)
                this.headers = VIDEO_HEADERS + mapOf("Cookie" to "accountToken=$token")
            }
        )
    }
}
