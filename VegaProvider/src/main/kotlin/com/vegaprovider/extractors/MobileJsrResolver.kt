package com.vegaprovider.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.Gofile
import com.lagradost.cloudstream3.extractors.LuluStream
import com.lagradost.cloudstream3.extractors.StreamTape
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

/**
 * mobilejsr.com/view/<id> link protector (extramovies.sarl), verified live 2026-11-13: the page has one "Unlock Links"
 * form (hidden _csrf, no captcha widget); POSTing it with the session cookie returns the real hosts. Hosts are handed to
 * CloudStream's built-in extractors where they exist (Voe, Gofile, StreamTape, LuluStream) and to HubCloud otherwise.
 * Skipped: hubdrive/filepress (unverified/API hidden) and 1fichier, megaup, multiup, vikingfile, frdl, uploadflix,
 * uploadhub (captcha/countdown download pages, no embed).
 */
object MobileJsrResolver {
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private val h = mapOf("User-Agent" to UA)

    suspend fun resolve(
        viewUrl: String, label: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ) {
        val r1 = app.get(viewUrl, headers = h)
        val csrf = r1.document.selectFirst("input[name=_csrf]")?.attr("value")
        val doc = if (csrf == null) r1.document
        else app.post(viewUrl, headers = h, referer = viewUrl, cookies = r1.cookies, data = mapOf("_csrf" to csrf)).document
        doc.select("a[href^=http]").map { it.attr("href") }.distinct().forEach { href ->
            runCatching {
                val host = href.substringAfter("://").substringBefore("/").removePrefix("www.")
                when {
                    host.startsWith("hubcloud") -> MirrorResolver.resolve(href, label, subtitleCallback, callback)
                    host == "voe.sx" -> loadExtractor(href, viewUrl, subtitleCallback, callback)
                    host == "gofile.io" -> Gofile().getUrl(href, viewUrl, subtitleCallback, callback)
                    // built-in StreamTape is registered for .com/.net/.xyz; this site links .to, same page layout
                    host.startsWith("streamtape") -> StreamTape().getUrl(href, viewUrl, subtitleCallback, callback)
                    // luluvid.com is the same service as the built-in's luluvdo.com (identical embed page)
                    host.startsWith("luluvid") ->
                        LuluStream().getUrl("https://luluvdo.com/e/" + href.trimEnd('/').substringAfterLast('/'), viewUrl, subtitleCallback, callback)
                }
            }
        }
    }
}
