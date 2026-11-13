package com.vegaprovider.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * zee-dl.lol "G-Direct" button (zeefliz/nexdrive). embed.php?download=<id> is a JS redirect page whose
 * `var reurl = ".../dl.php?link=<googleusercontent mkv url>"` holds the final direct link (verified 200 video/mkv 2026-11-13).
 */
object ZeeDlResolver {
    private val reurl = Regex("""var reurl\s*=\s*"[^"]*?link=([^"]+)"""")

    suspend fun resolve(href: String, label: String, callback: (ExtractorLink) -> Unit) {
        val url = reurl.find(app.get(href).text)?.groupValues?.get(1) ?: return
        val q = Regex("(\\d{3,4})p").find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        callback(newExtractorLink("ZeeDl", "G-Direct $label".trim(), url, ExtractorLinkType.VIDEO) { quality = q })
    }
}
