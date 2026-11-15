package com.vegaprovider.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

/**
 * vidara.to / vidaraa.cc "WATCH" embed (hdhub4u series), verified 2026-11-15: POST /api/stream {filecode, device}
 * returns {streaming_url: HLS master (480/720/1080, plays without referer), subtitles:[{file_path, language}]}.
 * Same page and API on both domains, so the code from the embed url is enough.
 */
object VidaraResolver {
    private const val HOST = "https://vidara.to"

    fun isVidara(url: String) = url.contains("vidara.") || url.contains("vidaraa.")

    suspend fun resolve(embedUrl: String, label: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val code = embedUrl.substringBefore("?").trimEnd('/').substringAfterLast('/')
        if (code.isBlank()) return
        val json = JSONObject(
            app.post("$HOST/api/stream", referer = "$HOST/e/$code", json = mapOf("filecode" to code, "device" to "web")).text
        )
        val m3u8 = json.optString("streaming_url").takeIf { it.startsWith("http") } ?: return
        json.optJSONArray("subtitles")?.let { subs ->
            for (i in 0 until subs.length()) {
                val s = subs.optJSONObject(i) ?: continue
                val u = s.optString("file_path")
                if (u.isNotBlank()) subtitleCallback(SubtitleFile(s.optString("language", "English"), u))
            }
        }
        callback(newExtractorLink("Vidara", "Vidara $label", m3u8, ExtractorLinkType.M3U8) { referer = "$HOST/" })
    }
}
