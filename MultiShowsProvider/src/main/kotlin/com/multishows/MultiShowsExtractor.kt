package com.multishows

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

/**
 * Resolves a MultiShows movie/episode detail page down to playable mirror links.
 *
 * Verified live chain (specs/002-multishows-provider/research.md):
 * detail page -> `<iframe id="video-iframe" src="multishows.top/embed/{id}">` ->
 * that page's own nested `<iframe src="https://filesforever.link/embed/{code}">` ->
 * `filesforever.link` is a domain-fronting alias that 302-redirects every path 1:1 to the real
 * backend (`pro.iqsmartgames.com` at verification time) -> `POST {backend}/embedhelper2.php`
 * (`sid`/`UserFavSite`/`currentDomain` form fields) returns a `sources` map (per-mirror `siteUrl`
 * + `friendlyName`) and a base64 `mresult` map (per-mirror video id); a final mirror url is
 * `sources[code].siteUrl + mresult[code]`.
 */
object MultiShowsExtractor {

    suspend fun invoke(
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val pageDoc = runCatching { app.get(pageUrl).document }.getOrNull() ?: return
        val multishowsEmbedUrl = pageDoc.selectFirst("iframe#video-iframe")?.attr("src")?.takeIf { it.isNotBlank() } ?: return

        val embedDoc = runCatching { app.get(multishowsEmbedUrl, referer = pageUrl).document }.getOrNull() ?: return
        val mirrorEmbedUrl = embedDoc.selectFirst("body iframe")?.attr("src")?.takeIf { it.isNotBlank() } ?: return

        // Follow the fronting alias live rather than hardcoding its backend host, so a future
        // rotation of that alias needs no code change here.
        val mirrorPageResponse = runCatching { app.get(mirrorEmbedUrl, referer = multishowsEmbedUrl) }.getOrNull() ?: return
        val resolvedHost = runCatching { URL(mirrorPageResponse.url).let { "${it.protocol}://${it.host}" } }.getOrNull() ?: return
        val code = mirrorEmbedUrl.trimEnd('/').substringAfterLast('/')
        val currentDomain = runCatching { URL(mirrorEmbedUrl).host }.getOrNull() ?: return

        val helperJson = runCatching {
            JSONObject(
                app.post(
                    "$resolvedHost/embedhelper2.php",
                    referer = mirrorEmbedUrl,
                    data = mapOf(
                        "sid" to code,
                        "UserFavSite" to "",
                        "currentDomain" to JSONArray().put(currentDomain).toString(),
                    )
                ).text
            )
        }.getOrNull() ?: return

        val sources = helperJson.optJSONObject("sources") ?: return
        val mresult = runCatching {
            JSONObject(String(Base64.decode(helperJson.optString("mresult"), Base64.DEFAULT)))
        }.getOrNull() ?: return

        sources.keys().asSequence().forEach { sourceKey ->
            val source = sources.optJSONObject(sourceKey) ?: return@forEach
            val siteUrl = source.optString("siteUrl").takeIf { it.isNotBlank() } ?: return@forEach
            val videoId = mresult.optString(sourceKey).takeIf { it.isNotBlank() } ?: return@forEach
            val finalUrl = siteUrl + videoId

            // Confirmed bundled in CloudStream: Smoothpre (earnvids). Try loadExtractor first for
            // every brand regardless (Constitution Principle III - reuse before reinventing); a
            // mirror this app doesn't already know how to resolve simply contributes nothing
            // (Principle II - an honest skip, never a guessed/broken link).
            runCatching { loadExtractor(finalUrl, mirrorEmbedUrl, subtitleCallback, callback) }
        }
    }
}
