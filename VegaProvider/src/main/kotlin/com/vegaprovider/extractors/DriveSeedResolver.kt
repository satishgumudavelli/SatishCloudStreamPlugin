package com.vegaprovider.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * moviesmod / uhdmovies link chain, verified live 2026-11-13:
 * en.thenaukriadda.in/?sid=.. (3 form POSTs + lp_go) -> driveseed.org/r?.. -> /file/<id> -> "Cloud Download" (r2.dev .mkv).
 * ponytail: /zfile (Resume Cloud) skipped - it serves .zip; Instant Download (video-gen.xyz) skipped - unverified.
 */
object DriveSeedResolver {
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private val h = mapOf("User-Agent" to UA)
    private val jsRedirect = Regex("""location\.replace\(["']([^"']+)["']\)""")
    private val sc = Regex("""sc\(["']([^"']+)["'],\s*["']([^"']+)["']""")

    /** Follow the shortlink to its destination url (null if the chain breaks). */
    suspend fun bypass(u: String): String? {
        val r1 = app.get(u, headers = h)
        val lp = r1.document.selectFirst("input[name=_lp_http]")?.attr("value")
            ?: return jsRedirect.find(r1.text)?.groupValues?.get(1)?.replace("\\", "") ?: u
        val a1 = r1.document.selectFirst("form#lp-land")?.attr("action")?.ifBlank { null } ?: u
        var ck = r1.cookies
        val r2 = app.post(a1, headers = h, referer = u, cookies = ck, data = mapOf("_lp_http" to lp))
        ck = ck + r2.cookies
        val f = r2.document.selectFirst("form#lp-s1-form") ?: return null
        val a2 = f.attr("action").ifBlank { a1 }
        val r3 = app.post(a2, headers = h, referer = a1, cookies = ck,
            data = f.select("input[name]").associate { it.attr("name") to it.attr("value") })
        ck = ck + r3.cookies
        val m = sc.find(r3.text) ?: return null
        ck = ck + (m.groupValues[1] to m.groupValues[2])
        val origin = a2.substringBefore("://") + "://" + a2.substringAfter("://").substringBefore("/")
        val r4 = app.get("$origin/?lp_go=${m.groupValues[1]}", headers = h, referer = a2, cookies = ck)
        return jsRedirect.find(r4.text)?.groupValues?.get(1)?.replace("\\", "")
    }

    suspend fun resolve(
        shortUrl: String, label: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ) {
        val target = bypass(shortUrl) ?: return
        if (!target.contains("driveseed") && !target.contains("driveleech")) {
            MirrorResolver.resolve(target, label, subtitleCallback, callback); return
        }
        var file = target
        if (target.contains("/r?")) {
            val p = jsRedirect.find(app.get(target, headers = h, referer = "https://en.thenaukriadda.in/").text)
                ?.groupValues?.get(1) ?: return
            file = if (p.startsWith("http")) p else target.substringBefore("://") + "://" + target.substringAfter("://").substringBefore("/") + p
        }
        app.get(file, headers = h, referer = target).document.select("a.btn").map { it.attr("href") }
            .filter { href -> href.startsWith("http") && listOf(".mkv", ".mp4").any { href.substringBefore("?").endsWith(it) } }
            .distinct().forEach { href ->
                callback(newExtractorLink("DriveSeed", "DriveSeed $label", href, ExtractorLinkType.VIDEO) {
                    quality = getQualityFromName(label)
                })
            }
    }
}
