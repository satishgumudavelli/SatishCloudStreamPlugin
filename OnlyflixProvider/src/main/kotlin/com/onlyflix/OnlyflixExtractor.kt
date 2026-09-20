package com.onlyflix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document

object OnlyflixExtractor {

    // onlyflix.to's own AJAX endpoint (contracts/movie-tv-embed-servers.md) - `document` is the
    // already-fetched movie/episode detail page; its player-frame div carries the nonce/post-id/
    // content-type this call needs, scraped fresh each time. The nonce is session-scoped, not
    // cacheable across titles/requests (research.md Decision 4).
    suspend fun getPlayers(mainUrl: String, document: Document): List<JSONObject> {
        val frame = document.selectFirst(".player-frame[data-player-post-id]") ?: return emptyList()
        val ajaxUrl = frame.attr("data-player-ajax-url").takeIf { it.isNotBlank() } ?: "$mainUrl/wp-admin/admin-ajax.php"
        val nonce = frame.attr("data-player-nonce").takeIf { it.isNotBlank() } ?: return emptyList()
        val postId = frame.attr("data-player-post-id").takeIf { it.isNotBlank() } ?: return emptyList()
        val contentType = frame.attr("data-player-content-type").takeIf { it.isNotBlank() } ?: "movie"

        val body = mapOf(
            "action" to "mcp_get_available_players",
            "nonce" to nonce,
            "post_id" to postId,
            "type" to contentType,
        )
        val response = runCatching { app.post(ajaxUrl, data = body, referer = mainUrl).text }.getOrNull() ?: return emptyList()
        val players = runCatching { JSONObject(response).optJSONObject("data")?.optJSONArray("players") }.getOrNull() ?: return emptyList()
        return (0 until players.length()).mapNotNull { players.optJSONObject(it) }
    }

    // Server 2 (Nontongo, sv2.nontongo.stream) - a two-hop ArtPlayer backend, NOT the protocol
    // VidboxExtractor.invokeTongo already implements for nontongo.win (research.md Decision 5:
    // same brand, different backend - ported nothing, this is independently verified). Both hops
    // return plain (non-packed) JS array literals, no decrypt/token step needed.
    suspend fun invokeNontongo(
        embedUrl: String,
        siteQuality: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val soapHtml = runCatching { app.get(embedUrl).text }.getOrNull() ?: return
        val multiSourceUrl = Regex("""var embedLink\s*=\s*"([^"]+)"""").find(soapHtml)?.groupValues?.get(1) ?: return

        val playerHtml = runCatching { app.get(multiSourceUrl, referer = embedUrl).text }.getOrNull() ?: return
        val quality = getQualityFromName(siteQuality ?: "")

        Regex("""const sources\s*=\s*(\[.*?]);""").find(playerHtml)?.groupValues?.get(1)
            ?.let { runCatching { JSONArray(it) }.getOrNull() }
            ?.let { sources ->
                for (i in 0 until sources.length()) {
                    val src = sources.optJSONObject(i) ?: continue
                    val fileUrl = src.optString("file").takeIf { it.isNotBlank() } ?: continue
                    val label = src.optString("html").takeIf { it.isNotBlank() } ?: "Nontongo"
                    callback(
                        newExtractorLink("Nontongo", "Nontongo $label", fileUrl, ExtractorLinkType.M3U8) {
                            this.referer = multiSourceUrl
                            this.quality = quality
                        }
                    )
                }
            }

        Regex("""const tracks\s*=\s*(\[.*?]);""").find(playerHtml)?.groupValues?.get(1)
            ?.let { runCatching { JSONArray(it) }.getOrNull() }
            ?.let { tracks ->
                for (i in 0 until tracks.length()) {
                    val track = tracks.optJSONObject(i) ?: continue
                    val fileUrl = track.optString("file").takeIf { it.isNotBlank() } ?: continue
                    val label = track.optString("label").takeIf { it.isNotBlank() } ?: "Unknown"
                    subtitleCallback(SubtitleFile(label, fileUrl))
                }
            }
    }
}
