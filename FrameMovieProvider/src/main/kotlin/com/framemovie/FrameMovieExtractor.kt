package com.framemovie

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonObject as GsonObject
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.net.URLDecoder
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// VidRock and Peachify are third-party embed backends observed live on framemovie.online's own
// watch page (specs/006-framemovie-provider/research.md Decision 3, contracts/movie-tv-embed-
// servers.md) - both already have a live-verified protocol elsewhere in this repo
// (VidboxProvider's VidboxExtractor.kt/VidboxCrypto.kt), ported here rather than re-derived
// (Constitution III). The AES-GCM keys/request shapes below are properties of vidrock.net /
// none.eat-peach.sbs themselves, not of framemovie.online.

private fun hexToBytes(hex: String): ByteArray =
    ByteArray(hex.length / 2) { i -> ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte() }

private fun base64UrlDecode(s: String): ByteArray {
    val fixed = s.replace("-", "+").replace("_", "/")
    val padded = fixed + "=".repeat((4 - fixed.length % 4) % 4)
    return Base64.decode(padded, Base64.DEFAULT)
}

/** AES-256-GCM where [token] is base64url(nonce[12] + ciphertext + tag[16]), as used by vidrock.net. */
private fun aesGcmDecryptToken(keyHex: String, token: String): String {
    val raw = base64UrlDecode(token)
    val nonce = raw.copyOfRange(0, 12)
    val ctAndTag = raw.copyOfRange(12, raw.size)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(hexToBytes(keyHex), "AES"), GCMParameterSpec(128, nonce))
    return String(cipher.doFinal(ctAndTag), Charsets.UTF_8)
}

/**
 * The XOR keystream cipher shared by player.videasy.to ("S1" on framemovie.online) and the
 * broader api.speedracelight.com backend it fronts. Ported verbatim from VidboxCrypto.kt's
 * `MvmCipher` (already live-verified elsewhere in this repo) - transcribed from the site's own
 * webpack chunks: a seeded PRNG (fnv1a + murmur3-style avalanche, golden-ratio mixing) generating
 * a keystream XORed against the base64url ciphertext body, validated against a 4-byte "mvm1"
 * magic header.
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

object FrameMovieExtractor {

    private suspend fun directM3u8Link(
        source: String,
        streamUrl: String,
        headers: Map<String, String> = emptyMap(),
        quality: Int? = null,
        name: String = source,
    ): ExtractorLink = newExtractorLink(source, name, streamUrl, ExtractorLinkType.M3U8) {
        this.headers = headers
        this.quality = quality ?: Qualities.Unknown.value
    }

    // -------------------------------------------------------------------------------------------
    // VidRock (vidrock.net) - id goes in the URL as plain text; each returned stream URL is its
    // own AES-256-GCM token. Ported from VidboxExtractor.invokevidrock. VidRock also runs its own
    // subtitle API on a separate host (sub.vdrk.site) - not present in VidboxExtractor's version,
    // live-verified here (2026-09-14, curl'd directly, no referer needed) for both
    // /v2/movie/{tmdbId} and /v2/tv/{tmdbId}/{season}/{episode}, each returning
    // [{"label","file"}, ...].
    // -------------------------------------------------------------------------------------------
    private const val vidrock = "https://vidrock.net"
    private const val vidrockSubApi = "https://sub.vdrk.site"
    private const val vidrockGcmKeyHex = "7f3e9c2a8b5d1f4e6a9c3b7d2e5f8a1c4b6d9e2f5a8c1b4d7e9f2a5c8b1d4e7f"

    private suspend fun invokeVidRockSubtitles(tmdbId: Int, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit) {
        val url = if (season == null) "$vidrockSubApi/v2/movie/$tmdbId" else "$vidrockSubApi/v2/tv/$tmdbId/$season/$episode"
        val subsJson = runCatching { org.json.JSONArray(app.get(url).text) }.getOrNull() ?: return
        for (i in 0 until subsJson.length()) {
            val sub = subsJson.optJSONObject(i) ?: continue
            val label = sub.optString("label").takeIf { it.isNotBlank() } ?: continue
            val file = sub.optString("file").takeIf { it.isNotBlank() } ?: continue
            subtitleCallback(SubtitleFile(label, file))
        }
    }

    suspend fun invokeVidRock(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (tmdbId == null) return
        val url = if (season == null) "$vidrock/api/movie/$tmdbId" else "$vidrock/api/tv/$tmdbId/$season/$episode"
        val response = runCatching { app.get(url).text }.getOrNull() ?: return
        val sourcesJson = runCatching { JSONObject(response) }.getOrNull() ?: return
        val headers = mapOf("Referer" to "$vidrock/")

        runCatching { invokeVidRockSubtitles(tmdbId, season, episode, subtitleCallback) }

        sourcesJson.keys().asSequence().toList().forEach { key ->
            val sourceObj = sourcesJson.optJSONObject(key) ?: return@forEach
            val token = sourceObj.optString("url", "")
            val lang = sourceObj.optString("language", "Unknown")
            if (token.isBlank() || token == "null") return@forEach

            val finalUrl = runCatching { aesGcmDecryptToken(vidrockGcmKeyHex, token) }.getOrNull() ?: return@forEach
            val displayName = "Vidrock [$key] $lang"

            if (finalUrl.contains(".m3u8", ignoreCase = true)) {
                callback(directM3u8Link("Vidrock-$key", finalUrl, headers = headers, name = displayName))
            } else {
                callback(
                    newExtractorLink("Vidrock-$key", displayName, finalUrl, ExtractorLinkType.VIDEO) {
                        this.headers = headers
                    }
                )
            }
        }
    }

    // -------------------------------------------------------------------------------------------
    // Peachify (peachify.top -> none.eat-peach.sbs) - a different AES-256-GCM key, per-server/
    // per-dub-language sources, and its own subtitle payload. Ported from
    // VidboxExtractor.invokePeachify.
    // -------------------------------------------------------------------------------------------
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

    // -------------------------------------------------------------------------------------------
    // Link (vidlink.pro) - id is encrypted via a third-party service (enc-dec.app) before being
    // used in the URL; response carries either an "hls" playlist or a "file" per-resolution
    // quality map, plus its own caption list. Ported verbatim from VidboxExtractor.invokeVidlink
    // (already live-verified elsewhere in this repo).
    // -------------------------------------------------------------------------------------------
    private const val vidlink = "https://vidlink.pro"

    suspend fun invokeVidlink(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (tmdbId == null) return

        val encResponse = runCatching { app.get("https://enc-dec.app/api/enc-vidlink?text=$tmdbId").text }.getOrNull() ?: return
        val encData = runCatching { JSONObject(encResponse).optString("result") }.getOrNull()
            ?.takeIf { it.isNotEmpty() } ?: return

        val headers = mapOf("Referer" to "$vidlink/", "Origin" to vidlink)

        val apiUrl = if (season == null) {
            "$vidlink/api/b/movie/$encData"
        } else {
            if (episode == null) return
            "$vidlink/api/b/tv/$encData/$season/$episode"
        }

        val epResponse = runCatching { app.get(apiUrl, headers = headers).text }.getOrNull() ?: return
        val stream = runCatching { JSONObject(epResponse).optJSONObject("stream") }.getOrNull() ?: return

        stream.optJSONArray("captions")?.let { captions ->
            for (i in 0 until captions.length()) {
                val cap = captions.optJSONObject(i) ?: continue
                val subUrl = cap.optString("url").takeIf { it.isNotBlank() } ?: continue
                val lang = cap.optString("language", "Unknown")
                subtitleCallback(SubtitleFile(lang, subUrl))
            }
        }

        // "hls" streams carry a playlist URL; "file" streams carry a per-resolution quality map instead.
        val playlist = stream.optString("playlist").takeIf { it.isNotBlank() }
        if (playlist != null) {
            val headersJson = Regex("""[?&]headers=([^&]+)""").find(playlist)?.groupValues?.get(1)
                ?.let { URLDecoder.decode(it, "UTF-8") }

            var referer = "$vidlink/"
            if (!headersJson.isNullOrBlank()) {
                runCatching {
                    val obj = Gson().fromJson(headersJson, GsonObject::class.java)
                    obj["referer"]?.asString?.let { referer = it }
                }
            }

            val m3u8Url = playlist.substringBefore("?")
            callback(directM3u8Link("Vidlink", m3u8Url, headers = headers + ("Referer" to referer)))
            return
        }

        val qualities = stream.optJSONObject("qualities") ?: return
        qualities.keys().asSequence().toList().forEach { key ->
            val fileUrl = qualities.optJSONObject(key)?.optString("url")?.takeIf { it.isNotBlank() } ?: return@forEach
            callback(
                newExtractorLink("Vidlink", "Vidlink ${key}p", fileUrl, ExtractorLinkType.VIDEO) {
                    this.quality = key.toIntOrNull() ?: Qualities.Unknown.value
                    this.headers = headers
                }
            )
        }
    }

    // -------------------------------------------------------------------------------------------
    // Videasy (player.videasy.to, "S1" on framemovie.online) - fronts api.speedracelight.com;
    // every provider's response is XOR-keystream-encrypted (MvmCipher above) using a per-tmdbId
    // "seed" fetched first. Ported verbatim from VidboxExtractor.invokeVideasy (already
    // live-verified elsewhere in this repo).
    // -------------------------------------------------------------------------------------------
    private const val speedraceApi = "https://api.speedracelight.com"
    private val speedraceProviders = listOf("cdn", "lamovie", "downloader2", "hdmovie", "m4uhd", "superflix")

    suspend fun invokeVideasy(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        title: String?,
        year: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
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
                callback(directM3u8Link("Videasy-$provider", srcUrl, quality = quality, name = "Videasy [$provider] $rawLabel"))
            }
        }
    }

    suspend fun invokePeachify(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (tmdbId == null) return
        val headers = mapOf("Referer" to "https://peachify.top/", "Origin" to "https://peachify.top")

        listOf("air", "holly", "moviebox").amap { server ->
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
                        directM3u8Link(
                            "Peachify-$server",
                            finalUrl,
                            quality = quality.takeIf { it > 0 },
                            headers = srcHeaders,
                            name = "Peachify [$server] $dub",
                        )
                    )
                }
            }
        }
    }
}
