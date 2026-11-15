package com.vegaprovider.extractors

import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * playdrive.vip/e/<id>/s-NN/ep-NN WATCH embed. The page unseals its own stream in the browser and then fetches an HLS master from
 * https://<cdn>/range/<token>.html (a real #EXTM3U playlist behind a disguised extension/content-type; variants .html, segments
 * .css/.js, all real HLS/MPEG-TS). So no key is decoded here: the page runs in a WebView, its play button (#btnPlay) is clicked,
 * and the first /range/ request is the master playlist. Verified in headless Chrome 2026-11-15; the CDN needs Referer https://playdrive.vip/
 * (302 without it). The master carries Hindi 5.1 / Hindi stereo audio, English subtitles, 1080p/720p variants.
 * ponytail: untested in Android WebView (autoplay policy); the click script retries for ~12s and also calls video.play().
 */
object PlaydriveResolver {
    private const val SITE = "https://playdrive.vip/"
    private val range = Regex("""https?://[^/\s]+/range/[^\s"']+""")
    private const val CLICK = """(function(){var n=0;var t=setInterval(function(){var b=document.getElementById('btnPlay');if(b){b.click();var v=document.querySelector('video');if(v){v.muted=true;try{v.play()}catch(e){}}clearInterval(t)}if(++n>40)clearInterval(t)},300)})();"""

    fun isPlaydrive(url: String) = url.contains("playdrive.")

    suspend fun resolve(embedUrl: String, label: String, referer: String, callback: (ExtractorLink) -> Unit) {
        val resolver = WebViewResolver(interceptUrl = range, useOkhttp = false, script = CLICK, timeout = 15_000L)
        val master = resolver.resolveUsingWebView(embedUrl, referer).first?.url?.toString() ?: return
        callback(newExtractorLink("Playdrive", "Playdrive $label", master, ExtractorLinkType.M3U8) { this.referer = SITE })
    }
}
