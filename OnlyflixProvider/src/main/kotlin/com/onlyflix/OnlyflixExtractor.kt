package com.onlyflix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
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

        Regex("""const sources\s*=\s*(\[.*?]);""", RegexOption.DOT_MATCHES_ALL).find(playerHtml)?.groupValues?.get(1)
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

        Regex("""const tracks\s*=\s*(\[.*?]);""", RegexOption.DOT_MATCHES_ALL).find(playerHtml)?.groupValues?.get(1)
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

    // Server "CDNM" (share.cdnm.ink) - live-verified request chain (user-supplied Postman capture,
    // cross-checked live): share.cdnm.ink/embed/imdb/{imdbId} server-renders an
    // <iframe id="player" data-src="{randomSubdomain}.cdnmovies-stream.online/imdb/{imdbId}/iframe?...">.
    // That iframe page loads a per-session-hashed `player-*.js` which decrypts an obfuscated inline
    // `file:` string (NOT plain base64 - confirmed by attempting to decode it: it fails as a single
    // block and as `//`-delimited blocks, so this is a custom/versioned scheme, not something to
    // reverse-engineer blind per Constitution I) and requests the real playlist from
    // `s1.cdnmvs.online/{token}:{expiry}/.../index-v1-a1.m3u8` - confirmed live by watching that
    // exact request actually fire with a 200. Real m3u8, no CAPTCHA gate (unlike vidapi.xyz) - so a
    // WebViewResolver (letting a real WebView execute the site's own unmodified JS and intercepting
    // the resulting request) reaches it without reimplementing the obfuscation, the same pattern
    // CinemaOsExtractor.invokeCinemaosWebview already uses in this repo for a different site's
    // WebView fallback.
    suspend fun invokeCdnm(
        embedUrl: String,
        siteQuality: String?,
        callback: (ExtractorLink) -> Unit,
    ) {
        val embedHtml = runCatching { app.get(embedUrl).text }.getOrNull() ?: return
        val iframeUrl = Regex("""id="player"[^>]*\bdata-src="([^"]+)"""").find(embedHtml)?.groupValues?.get(1)
            ?.replace("&amp;", "&")?.takeIf { it.isNotBlank() } ?: return
        val quality = getQualityFromName(siteQuality ?: "")

        val mediaRes = runCatching {
            app.get(
                iframeUrl,
                referer = embedUrl,
                interceptor = WebViewResolver(
                    Regex("""https?://[^"'\s]+?\.m3u8(?:\?[^"'\s]*)?"""),
                    useOkhttp = false,
                    timeout = 25_000L,
                )
            )
        }.getOrNull() ?: return

        val mediaUrl = mediaRes.url
        if (!mediaUrl.contains(".m3u8", ignoreCase = true)) return

        callback(
            newExtractorLink("CDNM", "CDNM", mediaUrl, ExtractorLinkType.M3U8) {
                this.referer = iframeUrl
                this.quality = quality
            }
        )
    }
}
