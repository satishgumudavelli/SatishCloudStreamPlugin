package com.vidbox

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.base64Encode
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

const val vidrock = "https://vidrock.ru"
const val vidlink = "https://vidlink.pro"
const val moviesClubApi = "https://moviesapi.club"

object VidboxExtractor {

    private fun vidrockEncode(
        tmdb: Int?,
        type: String,
        season: Int?,
        episode: Int?,
    ): String {
        val zw = base64Decode("eDdrOW1QcVQycld2WTh6QTViQzNuRjZoSjJsSzRtTjk=")
        val s = if (type == "tv" && season != null && episode != null) {
            "${tmdb}_${season}_${episode}"
        } else {
            tmdb
        }.toString()
        val keySpec = SecretKeySpec(zw.toByteArray(Charsets.UTF_8), "AES")
        val ivSpec = IvParameterSpec(zw.substring(0, 16).toByteArray(Charsets.UTF_8))
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
        val encrypted = cipher.doFinal(s.toByteArray(Charsets.UTF_8))
        return base64Encode(encrypted)
            .replace("+", "-")
            .replace("/", "_")
            .replace("=", "")
    }

    suspend fun invokevidrock(
        tmdbId: Int?,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        if (tmdbId == null) return
        val type = if (season == null) "movie" else "tv"
        val encoded = vidrockEncode(tmdbId, type, season, episode)
        val response = runCatching { app.get("$vidrock/api/$type/$encoded").text }.getOrNull() ?: return
        val sourcesJson = runCatching { JSONObject(response) }.getOrNull() ?: return

        val vidrockHeaders = mapOf("Origin" to vidrock)

        sourcesJson.keys().asSequence().toList().forEach { key ->
            val sourceObj = sourcesJson.optJSONObject(key) ?: return@forEach
            val rawUrl = sourceObj.optString("url", "")
            val lang = sourceObj.optString("language", "Unknown")
            if (rawUrl.isBlank() || rawUrl == "null") return@forEach

            val safeUrl = if (rawUrl.contains("%")) URLDecoder.decode(rawUrl, "UTF-8") else rawUrl
            val displayName = "Vidrock [$key] $lang"

            when {
                safeUrl.contains("/playlist/") -> {
                    val playlistResponse = runCatching { app.get(safeUrl, headers = vidrockHeaders).text }.getOrNull() ?: return@forEach
                    val playlistArray = runCatching { JSONArray(playlistResponse) }.getOrNull() ?: return@forEach
                    for (j in 0 until playlistArray.length()) {
                        val item = playlistArray.optJSONObject(j) ?: continue
                        val itemUrl = item.optString("url", "")
                        if (itemUrl.isBlank()) continue
                        val res = item.optInt("resolution", 0)
                        callback(
                            newExtractorLink("Vidrock-$key", displayName, itemUrl, ExtractorLinkType.M3U8) {
                                this.headers = vidrockHeaders
                                this.quality = getQualityFromName("$res")
                            }
                        )
                    }
                }
                safeUrl.contains(".m3u8", ignoreCase = true) -> {
                    generateM3u8("Vidrock-$key", safeUrl, "", headers = vidrockHeaders).forEach(callback)
                }
                else -> {
                    callback(
                        newExtractorLink("Vidrock-$key", displayName, safeUrl, ExtractorLinkType.VIDEO) {
                            this.headers = vidrockHeaders
                        }
                    )
                }
            }
        }
    }

    suspend fun invokeVidlink(
        tmdbId: Int?,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        if (tmdbId == null) return

        val encResponse = runCatching { app.get("https://enc-dec.app/api/enc-vidlink?text=$tmdbId").text }.getOrNull() ?: return
        val encData = runCatching { JSONObject(encResponse).optString("result") }.getOrNull()
            ?.takeIf { it.isNotEmpty() } ?: return

        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$vidlink/",
            "Origin" to vidlink
        )

        val apiUrl = if (season == null) {
            "$vidlink/api/b/movie/$encData"
        } else {
            if (episode == null) return
            "$vidlink/api/b/tv/$encData/$season/$episode"
        }

        val epResponse = runCatching { app.get(apiUrl, headers = headers).text }.getOrNull() ?: return
        val data = runCatching { Gson().fromJson(epResponse, VidlinkResponse::class.java) }.getOrNull() ?: return
        val m3u8 = data.stream.playlist

        val headersJson = Regex("""[?&]headers=([^&]+)""").find(m3u8)?.groupValues?.get(1)
            ?.let { URLDecoder.decode(it, "UTF-8") }

        var referer = "$vidlink/"
        if (!headersJson.isNullOrBlank()) {
            runCatching {
                val obj = Gson().fromJson(headersJson, JsonObject::class.java)
                obj["referer"]?.asString?.let { referer = it }
            }
        }

        val m3u8Url = m3u8.substringBefore("?")
        generateM3u8("Vidlink", m3u8Url, referer, headers = headers).forEach(callback)
    }

    suspend fun invokeMoviesApi(
        id: Int?,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        if (id == null) return
        val href = if (season == null) "$moviesClubApi/movie/$id" else "$moviesClubApi/tv/$id-$season-$episode"
        val pageDoc = runCatching { app.get(href).document }.getOrNull() ?: return
        val iframeElement = pageDoc.selectFirst("iframe[src], iframe[data-src]") ?: return
        val iframeSrc = iframeElement.attr("src").ifEmpty { iframeElement.attr("data-src") }
        if (iframeSrc.isEmpty()) return
        val iframeDoc = runCatching { app.get(iframeSrc).document }.getOrNull() ?: return
        val scriptData = iframeDoc.select("script")
            .firstOrNull { it.data().contains("function(p,a,c,k,e,d)") }?.data()
            ?: iframeDoc.selectFirst("script")?.data() ?: return
        val unpacked = runCatching { getAndUnpack(scriptData) }.getOrNull() ?: scriptData
        val m3u8 = Regex("""sources:\[\{file:"(.*?)"""").find(unpacked)?.groupValues?.get(1) ?: return

        generateM3u8("MoviesApi Club", m3u8, iframeSrc, headers = mapOf("Referer" to iframeSrc)).forEach(callback)
    }

    data class VidlinkResponse(
        @SerializedName("stream") val stream: VidlinkStream
    )

    data class VidlinkStream(
        @SerializedName("playlist") val playlist: String
    )
}
