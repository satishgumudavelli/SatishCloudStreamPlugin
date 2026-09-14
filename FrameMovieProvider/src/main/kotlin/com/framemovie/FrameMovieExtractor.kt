package com.framemovie

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
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
    // own AES-256-GCM token. Ported from VidboxExtractor.invokevidrock.
    // -------------------------------------------------------------------------------------------
    private const val vidrock = "https://vidrock.net"
    private const val vidrockGcmKeyHex = "7f3e9c2a8b5d1f4e6a9c3b7d2e5f8a1c4b6d9e2f5a8c1b4d7e9f2a5c8b1d4e7f"

    suspend fun invokeVidRock(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit
    ) {
        if (tmdbId == null) return
        val url = if (season == null) "$vidrock/api/movie/$tmdbId" else "$vidrock/api/tv/$tmdbId/$season/$episode"
        val response = runCatching { app.get(url).text }.getOrNull() ?: return
        val sourcesJson = runCatching { JSONObject(response) }.getOrNull() ?: return
        val headers = mapOf("Referer" to "$vidrock/")

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
