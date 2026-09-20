package com.onlyflix

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// vidapi.xyz's own aggregator ships Peachify and Videasy among its mirrors (research.md Decision
// 6b, user-supplied Postman capture) - both already have a live-verified protocol elsewhere in
// this repo (VidboxExtractor.kt/FrameMovieExtractor.kt), ported here rather than re-derived
// (Constitution III). The AES-GCM key / XOR-cipher below are properties of none.eat-peach.sbs /
// player.videasy.to themselves, not of onlyflix.to.

private fun hexToBytes(hex: String): ByteArray =
    ByteArray(hex.length / 2) { i -> ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte() }

private fun base64UrlDecode(s: String): ByteArray {
    val fixed = s.replace("-", "+").replace("_", "/")
    val padded = fixed + "=".repeat((4 - fixed.length % 4) % 4)
    return Base64.decode(padded, Base64.DEFAULT)
}

/**
 * The XOR keystream cipher shared by player.videasy.to and api.speedracelight.com. Ported
 * verbatim from VidboxCrypto.kt's `MvmCipher` (already live-verified elsewhere in this repo).
 */
@OptIn(ExperimentalUnsignedTypes::class)
private object MvmCipher {
    private const val MS = 2654435769u
    private val MAGIC = byteArrayOf(109, 118, 109, 49)

    private fun avalanche(eIn: UInt): UInt {
        var e = eIn
        e = e xor (e shr 16)
        e *= 2246822507u
        e = e xor (e shr 13)
        e *= 3266489909u
        e = e xor (e shr 16)
        return e
    }

    private fun rotl(l: UInt, o: Int): UInt {
        val shift = o and 31
        return if (shift == 0) l else (l shl shift) or (l shr (32 - shift))
    }

    private fun fnv1a(s: String): UInt {
        var o = 2166136261u
        for (c in s) o = (o xor c.code.toUInt()) * 16777619u
        return avalanche(o)
    }

    private class State(seed: String, mediaId: Int) {
        val table = UIntArray(61)
        val visited = BooleanArray(61)
        var acc: UInt

        init {
            var i = avalanche(fnv1a(seed) xor avalanche(mediaId.toUInt() xor MS))
            for (r in 0 until 8) {
                val n = (i % 61u).toInt()
                i = rotl(i + MS, 7 + (r and 7))
                table[n] = i xor avalanche(i)
                visited[n] = true
                i = avalanche(i + n.toUInt())
            }
            acc = avalanche(i xor 2779096485u)
        }

        fun nextWord(counter: Int): UInt {
            val r = (acc % 61u).toInt()
            val hasSlot = visited[r]
            val u = if (hasSlot) table[r] else 0u
            val mask = if (hasSlot) 0xFFFFFFFFu else 0u
            val d = MS * (counter + 1).toUInt()
            val xorUD = u xor d
            var g = (acc xor xorUD) or (acc and xorUD and mask)
            g = rotl(g + acc, r and 31) xor rotl(acc, (r * 7) and 31)
            val newAcc = avalanche(g + MS)
            table[r] = newAcc
            visited[r] = true
            acc = newAcc
            return newAcc
        }
    }

    fun decode(base64UrlToken: String, seed: String, mediaId: Int): String {
        val cipherBytes = base64UrlDecode(base64UrlToken)
        val state = State(seed, mediaId)
        val out = ByteArray(cipherBytes.size)
        var idx = 0
        var counter = 0
        while (idx < cipherBytes.size) {
            val word = state.nextWord(counter++)
            out[idx] = (cipherBytes[idx].toInt() xor (word and 0xFFu).toInt()).toByte(); idx++
            if (idx < cipherBytes.size) { out[idx] = (cipherBytes[idx].toInt() xor ((word shr 8) and 0xFFu).toInt()).toByte(); idx++ }
            if (idx < cipherBytes.size) { out[idx] = (cipherBytes[idx].toInt() xor ((word shr 16) and 0xFFu).toInt()).toByte(); idx++ }
            if (idx < cipherBytes.size) { out[idx] = (cipherBytes[idx].toInt() xor ((word shr 24) and 0xFFu).toInt()).toByte(); idx++ }
        }
        for (i in MAGIC.indices) {
            if (out[i] != MAGIC[i]) throw IllegalStateException("mvm1 decrypt failed: bad seed or tampered payload")
        }
        return String(out, MAGIC.size, out.size - MAGIC.size, Charsets.UTF_8)
    }
}

object OnlyflixExtractor {

    // onlyflix.to's mcp_get_available_players only hands back an IMDb id, but Peachify/Videasy
    // (reached elsewhere in this repo via vidapi.xyz's own aggregator - research.md Decision 6b)
    // key off a TMDB id - one extra `find` lookup bridges the two. Reuses the same public TMDB API
    // key VidboxScraper.kt already ships in this repo (site key, not a secret of ours).
    private const val tmdbKey = "ef311eb0b9b07b9c73e9fb0a732cc150"

    suspend fun imdbToTmdbId(imdbId: String, isTv: Boolean): Int? {
        val json = runCatching {
            JSONObject(app.get("https://api.themoviedb.org/3/find/$imdbId?api_key=$tmdbKey&external_source=imdb_id").text)
        }.getOrNull() ?: return null
        val arr = json.optJSONArray(if (isTv) "tv_results" else "movie_results") ?: return null
        return arr.optJSONObject(0)?.optInt("id")?.takeIf { it > 0 }
    }

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
    // A subtitle-file URL's own filename sometimes names the language (Vidmoly-style
    // `..._English.vtt`); CDNM's own `cdn.iamcdn.net/subtitle/{id}/{randomToken}.srt` never does -
    // fall back to a generic indexed label rather than guessing (spec.md edge case).
    private fun subtitleLabel(url: String, index: Int): String =
        Regex("""_([A-Za-z]+)\.(?:vtt|srt)(?:\?|$)""").find(url)?.groupValues?.get(1) ?: "Subtitle ${index + 1}"

    suspend fun invokeCdnm(
        embedUrl: String,
        siteQuality: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        // Live-tested (2026-09-21): share.cdnm.ink now 403s a plain GET, even with a Referer
        // header, with "This player is only available when embedded on a website" - it checks
        // Sec-Fetch-Dest (only a real browser loading it inside an <iframe> sets this, curl/OkHttp
        // don't by default). Confirmed live that adding these three headers alone (no other change)
        // turns the 403 into a 200 - this wasn't required when CDNM was first implemented.
        val embedHeaders = mapOf(
            "Referer" to "https://onlyflix.to/",
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "cross-site",
        )
        val embedHtml = runCatching { app.get(embedUrl, headers = embedHeaders).text }.getOrNull() ?: return
        val iframeUrl = Regex("""id="player"[^>]*\bdata-src="([^"]+)"""").find(embedHtml)?.groupValues?.get(1)
            ?.replace("&amp;", "&")?.takeIf { it.isNotBlank() } ?: return
        val quality = getQualityFromName(siteQuality ?: "")

        // additionalUrls also captures cdn.iamcdn.net/subtitle/{id}/{token}.srt in the same
        // WebView pass - resolveUsingWebView (not the plain interceptor style) is what makes that
        // second list available, per WebViewResolver's own doc comment (see CineapseExtractor).
        val resolver = WebViewResolver(
            Regex("""https?://[^"'\s]+?\.m3u8(?:\?[^"'\s]*)?"""),
            additionalUrls = listOf(Regex("""https?://[^"'\s]+?\.srt(?:\?[^"'\s]*)?""")),
            useOkhttp = false,
            timeout = 25_000L,
        )
        val result = runCatching { resolver.resolveUsingWebView(iframeUrl, referer = embedUrl) }.getOrNull() ?: return
        val mediaUrl = result.first?.url?.toString() ?: return
        if (!mediaUrl.contains(".m3u8", ignoreCase = true)) return

        result.second.mapNotNull { it.url.toString().takeIf { u -> u.contains(".srt", ignoreCase = true) } }
            .forEachIndexed { i, subUrl -> subtitleCallback(SubtitleFile(subtitleLabel(subUrl, i), subUrl)) }

        callback(
            newExtractorLink("CDNM", "CDNM", mediaUrl, ExtractorLinkType.M3U8) {
                this.referer = iframeUrl
                this.quality = quality
            }
        )
    }

    private const val peachifyApi = "https://none.eat-peach.sbs"
    private const val peachifyGcmKeyHex = "a8f2a1b5e9c470814f6b2c3a5d8e7f9c1a2b3c4d5e3f7a8b8cad1e2d0a4d5c5d"

    private fun decryptPeachifyUrl(payload: String): String {
        val parts = payload.split(".")
        require(parts.size == 3) { "unexpected peachify payload shape" }
        val iv = base64UrlDecode(parts[0])
        val ct = base64UrlDecode(parts[1])
        val tag = base64UrlDecode(parts[2])
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(hexToBytes(peachifyGcmKeyHex), "AES"), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ct + tag), Charsets.UTF_8)
    }

    // Peachify, reached from vidapi.xyz's aggregator as its "vpls" mirror (research.md Decision
    // 6b) - ported verbatim from VidboxExtractor.invokePeachify/FrameMovieExtractor's own copy of
    // the same, both already live-verified in this repo. Needs a TMDB id (see imdbToTmdbId).
    suspend fun invokePeachify(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        if (tmdbId == null) return
        val headers = mapOf("Referer" to "https://peachify.top/", "Origin" to "https://peachify.top")

        // /subs/ is a separate OpenSubtitles-backed endpoint, not tied to any one air/holly/multi
        // provider above - live-confirmed to return a plain JSON array (not the per-provider
        // {"sources":[...],"subtitles":[...]} shape).
        val subsUrl = if (season == null) "$peachifyApi/subs/movie/$tmdbId" else "$peachifyApi/subs/tv/$tmdbId/$season/$episode"
        runCatching { JSONArray(app.get(subsUrl, headers = headers).text) }.getOrNull()?.let { subs ->
            for (i in 0 until subs.length()) {
                val sub = subs.optJSONObject(i) ?: continue
                val subUrl = sub.optString("url").takeIf { it.isNotBlank() } ?: continue
                val lang = sub.optString("display").takeIf { it.isNotBlank() } ?: sub.optString("language", "Unknown")
                subtitleCallback(SubtitleFile(lang, subUrl))
            }
        }

        // Live-tested against none.eat-peach.sbs directly (2026-09-21): "moviebox" now 404s
        // ("Provider not Found") - the server list ported from VidboxExtractor/FrameMovieExtractor
        // was stale. "multi" is confirmed live to return real sources; kept "air"/"holly" since
        // they respond 200 (just empty for the titles tested, not dead like moviebox).
        listOf("air", "holly", "multi").amap { server ->
            val url = if (season == null) "$peachifyApi/$server/movie/$tmdbId"
            else "$peachifyApi/$server/tv/$tmdbId/$season/$episode"
            val json = runCatching { JSONObject(app.get(url, headers = headers).text) }.getOrNull() ?: return@amap
            val sourcesArr = json.optJSONArray("sources") ?: return@amap
            val encrypted = json.optBoolean("isEncrypted", false)

            json.optJSONArray("subtitles")?.let { subs ->
                for (i in 0 until subs.length()) {
                    val sub = subs.optJSONObject(i) ?: continue
                    val subUrl = sub.optString("url").takeIf { it.isNotBlank() }
                        ?: sub.optString("file").takeIf { it.isNotBlank() } ?: continue
                    val lang = sub.optString("lang").takeIf { it.isNotBlank() }
                        ?: sub.optString("label", "Unknown")
                    subtitleCallback(SubtitleFile(lang, subUrl))
                }
            }

            for (i in 0 until sourcesArr.length()) {
                val src = sourcesArr.optJSONObject(i) ?: continue
                val rawUrl = src.optString("url").takeIf { it.isNotBlank() } ?: continue
                val finalUrl = if (encrypted) {
                    runCatching { decryptPeachifyUrl(rawUrl) }.getOrNull() ?: continue
                } else rawUrl
                val dub = src.optString("dub", "")
                val quality = src.optInt("quality", 0)
                val srcHeaders = src.optJSONObject("headers")?.let { h ->
                    h.keys().asSequence().associateWith { k -> h.optString(k) }
                } ?: headers

                if (src.optString("type") == "mp4") {
                    callback(
                        newExtractorLink("Peachify-$server", "Peachify [$server] $dub", finalUrl, ExtractorLinkType.VIDEO) {
                            this.headers = srcHeaders
                            if (quality > 0) this.quality = quality
                        }
                    )
                } else {
                    callback(
                        newExtractorLink("Peachify-$server", "Peachify [$server] $dub", finalUrl, ExtractorLinkType.M3U8) {
                            this.headers = srcHeaders
                            if (quality > 0) this.quality = quality
                        }
                    )
                }
            }
        }
    }

    private const val speedraceApi = "https://api.speedracelight.com"
    private val speedraceProviders = listOf("cdn", "lamovie", "downloader2", "hdmovie", "m4uhd", "superflix")

    // Videasy, reached from vidapi.xyz's aggregator as its "vesy" mirror (research.md Decision
    // 6b) - ported verbatim from FrameMovieExtractor.invokeVideasy, already live-verified in this
    // repo. Needs a TMDB id (see imdbToTmdbId).
    suspend fun invokeVideasy(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        title: String?,
        year: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        if (tmdbId == null || title.isNullOrBlank()) return
        val mediaType = if (season == null) "movie" else "tv"

        val seedResp = runCatching { app.get("$speedraceApi/seed?mediaId=$tmdbId").text }.getOrNull() ?: return
        val seed = runCatching { JSONObject(seedResp).optString("seed") }.getOrNull()?.takeIf { it.isNotBlank() } ?: return

        speedraceProviders.amap { provider ->
            val params = mutableListOf(
                "title" to title, "mediaType" to mediaType, "tmdbId" to "$tmdbId", "imdbId" to "",
                "year" to (year?.toString() ?: ""), "enc" to "2", "seed" to seed
            )
            if (season != null) params.add("seasonId" to "$season")
            if (episode != null) params.add("episodeId" to "$episode")
            val qs = params.joinToString("&") { (k, v) -> "$k=${java.net.URLEncoder.encode(v, "UTF-8")}" }

            val body = runCatching { app.get("$speedraceApi/$provider/sources-with-title?$qs").text }.getOrNull() ?: return@amap
            val decoded = runCatching { MvmCipher.decode(body, seed, tmdbId) }.getOrNull() ?: return@amap
            val json = runCatching { JSONObject(decoded) }.getOrNull() ?: return@amap

            json.optJSONArray("subtitles")?.let { subs ->
                for (i in 0 until subs.length()) {
                    val sub = subs.optJSONObject(i) ?: continue
                    val subUrl = sub.optString("url").takeIf { it.isNotBlank() } ?: continue
                    val lang = sub.optString("lang").takeIf { it.isNotBlank() } ?: sub.optString("language", "Unknown")
                    subtitleCallback(SubtitleFile(lang, subUrl))
                }
            }

            val sources = json.optJSONArray("sources") ?: return@amap
            for (i in 0 until sources.length()) {
                val src = sources.optJSONObject(i) ?: continue
                val srcUrl = src.optString("url").takeIf { it.isNotBlank() } ?: continue
                val rawLabel = src.optString("quality")
                val quality = getQualityFromName(rawLabel)
                callback(
                    newExtractorLink("Videasy-$provider", "Videasy [$provider] $rawLabel", srcUrl, ExtractorLinkType.M3U8) {
                        this.quality = quality
                    }
                )
            }
        }
    }

    // vidapi.xyz and vidfast.vc are themselves meta-aggregators: each auto-cycles through ~10
    // named mirrors client-side (research.md Decision 6b - videm.xyz, cineby.hair, vidcore.io/net,
    // embedmaster.link -> a crypto-obfuscated "EM" player exposing VOE/MixDrop/Vidmoly/Dsvplay/
    // Playmogo/AbyssPlayer, etc.), several of which are individually CAPTCHA- or crypto-gated the
    // same way CDNM's own player is. Rather than hand-writing/maintaining a bespoke extractor per
    // mirror, point a broad WebViewResolver at the top-level embed itself and let the site's own
    // unmodified JS pick and load whichever mirror actually works, the same fallback pattern
    // CinemaOsExtractor/CineapseExtractor already use in this repo. Whichever named CloudStream-
    // core extractor (Voe/MixDrop/Vidmoly/Dsvplay/Playmogo) the resolved URL belongs to is left to
    // `loadExtractor` to match - this function only needs to hand off the final resolved media URL.
    private suspend fun invokeAggregatorWebView(
        source: String,
        embedUrl: String,
        siteQuality: String?,
        timeoutMs: Long,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val quality = getQualityFromName(siteQuality ?: "")
        // additionalUrls also captures whichever mirror's own subtitle files fire in the same
        // WebView pass (Vidmoly-style `srt.vidmoly.me/.../{lang}.vtt`, or a plain `.srt`) - see
        // invokeCdnm's comment on why resolveUsingWebView (not the plain interceptor style) is
        // what makes that second list available at all.
        val resolver = WebViewResolver(
            Regex("""https?://[^"'\s]+?\.(?:m3u8|mp4)(?:\?[^"'\s]*)?"""),
            additionalUrls = listOf(Regex("""https?://[^"'\s]+?\.(?:vtt|srt)(?:\?[^"'\s]*)?""")),
            useOkhttp = false,
            timeout = timeoutMs,
        )
        val result = runCatching { resolver.resolveUsingWebView(embedUrl) }.getOrNull() ?: return
        val mediaUrl = result.first?.url?.toString() ?: return
        val type = when {
            mediaUrl.contains(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
            mediaUrl.contains(".mp4", ignoreCase = true) -> ExtractorLinkType.VIDEO
            else -> return
        }

        result.second.mapNotNull { it.url.toString().takeIf { u -> u.contains(".vtt", true) || u.contains(".srt", true) } }
            .forEachIndexed { i, subUrl -> subtitleCallback(SubtitleFile(subtitleLabel(subUrl, i), subUrl)) }

        callback(
            newExtractorLink(source, "$source [WebView]", mediaUrl, type) {
                this.referer = embedUrl
                this.quality = quality
            }
        )
    }

    // vidapi.xyz frame-busts: live-confirmed via Playwright that navigating a browser straight to
    // `vidapi.xyz/embed/movie/{imdbId}` (as invokeAggregatorWebView did) bounces the tab back out
    // immediately - a plain `curl` GET returns the real page fine, so this is a client-side JS
    // check (window.top !== window.self), not a server-side Referer/header gate (unlike CDNM's).
    // Loading onlyflix's own detail page instead and clicking its "Server N" tab keeps vidapi.xyz
    // properly iframed, the same way a real visitor reaches it, avoiding the bust entirely.
    //
    // Known remaining gap: vidapi.xyz's own UI (confirmed live) then shows a "Play video" overlay,
    // and - per a user-supplied screenshot - a further dropdown of ~8 named sub-servers ("Server
    // A/X/N/V/Y/P/B/S...") to pick from before any real stream request fires. Both live inside a
    // cross-origin nested iframe this script cannot reach via document.querySelector, so this is
    // not (yet) automated - unverified whether the site auto-selects a default without those
    // clicks. Left as the honest limit of what's confirmed working, not guessed further.
    suspend fun invokeVidapi(
        pageUrl: String,
        serverNumber: Int,
        siteQuality: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val quality = getQualityFromName(siteQuality ?: "")
        // Idempotent guard (WebViewResolver re-evaluates `script` on every intercepted request,
        // per CineapseExtractor's own comment on the same behavior) so the tab isn't re-clicked
        // repeatedly for the whole timeout window.
        val script = """
            (function(){
              function clickServerTab(){
                var btns = document.querySelectorAll('[role="tablist"] button, .nav-tabs button');
                for (var i=0;i<btns.length;i++){
                  if (btns[i].textContent.trim() === 'Server $serverNumber') { btns[i].click(); return true; }
                }
                return false;
              }
              if (!window.__ofClickedTab) { window.__ofClickedTab = clickServerTab(); }
            })();
        """.trimIndent()

        val resolver = WebViewResolver(
            Regex("""https?://[^"'\s]+?\.(?:m3u8|mp4)(?:\?[^"'\s]*)?"""),
            additionalUrls = listOf(Regex("""https?://[^"'\s]+?\.(?:vtt|srt)(?:\?[^"'\s]*)?""")),
            script = script,
            useOkhttp = false,
            timeout = 40_000L,
        )
        val result = runCatching { resolver.resolveUsingWebView(pageUrl) }.getOrNull() ?: return
        val mediaUrl = result.first?.url?.toString() ?: return
        val type = when {
            mediaUrl.contains(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
            mediaUrl.contains(".mp4", ignoreCase = true) -> ExtractorLinkType.VIDEO
            else -> return
        }

        result.second.mapNotNull { it.url.toString().takeIf { u -> u.contains(".vtt", true) || u.contains(".srt", true) } }
            .forEachIndexed { i, subUrl -> subtitleCallback(SubtitleFile(subtitleLabel(subUrl, i), subUrl)) }

        callback(
            newExtractorLink("vidapi.xyz", "vidapi.xyz [WebView]", mediaUrl, type) {
                this.referer = pageUrl
                this.quality = quality
            }
        )
    }

    suspend fun invokeVidfast(
        embedUrl: String,
        siteQuality: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) =
        invokeAggregatorWebView("vidfast.vc", embedUrl, siteQuality, timeoutMs = 35_000L, subtitleCallback = subtitleCallback, callback = callback)
}
