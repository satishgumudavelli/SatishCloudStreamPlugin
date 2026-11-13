package com.vegaprovider.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink

/**
 * mobilejsr.com/view/<id> link protector (extramovies.sarl), verified live 2026-11-13: the page carries a single
 * "Unlock Links" form (hidden _csrf, no captcha widget); POSTing it with the session cookie returns "Links Unlocked"
 * plus the real hosts. Only hosts already resolvable here are followed (hubcloud /video/ + gofile); filepress.lat
 * hides its API, hubdrive/1fichier/megaup/vikingfile/voe are unverified and skipped.
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
        doc.select("a[href^=http]").map { it.attr("href") }
            .filter { it.contains("hubcloud") || it.contains("gofile.io") }
            .distinct()
            .forEach { MirrorResolver.resolve(it, label, subtitleCallback, callback) }
    }
}
