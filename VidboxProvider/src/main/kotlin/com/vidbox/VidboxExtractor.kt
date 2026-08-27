package com.vidbox

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

const val vidrock = "https://vidrock.net"
const val vidlink = "https://vidlink.pro"

object VidboxExtractor {

    // Emits the raw m3u8 URL directly instead of running it through generateM3u8's quality-splitter.
    // Splitting a master into per-resolution sub-playlists drops any #EXT-X-MEDIA alternate audio
    // groups declared only in the master (confirmed on a real Nxsha capture: Hindi/Korean dubs) -
    // the split links silently lose audio-track selection. The unmodified master lets the player's
    // own HLS engine parse it and expose whatever audio tracks/qualities it declares.
    private suspend fun directM3u8Link(
        source: String,
        streamUrl: String,
        referer: String = "",
        headers: Map<String, String> = emptyMap(),
        quality: Int? = null,
        name: String = source,
    ): ExtractorLink = newExtractorLink(source, name, streamUrl, ExtractorLinkType.M3U8) {
        this.referer = referer
        this.headers = headers
        this.quality = quality ?: Qualities.Unknown.value
    }

    // -------------------------------------------------------------------------------------------
    // Rock (vidrock.net) - id goes in the URL as plain text; each returned stream URL is its own
    // AES-256-GCM token: base64url(nonce[12] + ciphertext + tag[16]).
    // -------------------------------------------------------------------------------------------
    private const val vidrockGcmKeyHex = "7f3e9c2a8b5d1f4e6a9c3b7d2e5f8a1c4b6d9e2f5a8c1b4d7e9f2a5c8b1d4e7f"

    suspend fun invokevidrock(
        tmdbId: Int?,
        season: Int? = null,
        episode: Int? = null,
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
    // Echo (vidlink.pro)
    // -------------------------------------------------------------------------------------------
    suspend fun invokeVidlink(
        tmdbId: Int?,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (tmdbId == null) return

        val encResponse = runCatching { app.get("https://enc-dec.app/api/enc-vidlink?text=$tmdbId").text }.getOrNull() ?: return
        val encData = runCatching { JSONObject(encResponse).optString("result") }.getOrNull()
            ?.takeIf { it.isNotEmpty() } ?: return

        val headers = mapOf(
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
                    val obj = Gson().fromJson(headersJson, JsonObject::class.java)
                    obj["referer"]?.asString?.let { referer = it }
                }
            }

            val m3u8Url = playlist.substringBefore("?")
            callback(directM3u8Link("Vidlink", m3u8Url, referer = referer, headers = headers))
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
    // Nxsha (nxsha.space) - every request/response body is CryptoJS.AES.encrypt(json, passphrase),
    // url-safe base64. servers -> per-server sources, both round trips through the same cipher.
    // -------------------------------------------------------------------------------------------
    private const val nxshaApi = "https://nxsha.space"
    private const val nxshaPassphrase = "S8x!Jk4ZP1uG8\$my"

    private fun nxshaEncode(obj: JSONObject): String {
        obj.put("_req_ts", System.currentTimeMillis())
        obj.put("_req_salt", (1..10).map { "0123456789abcdefghijklmnopqrstuvwxyz".random() }.joinToString(""))
        return CryptoJsAes.encryptUrlSafe(obj.toString(), nxshaPassphrase)
    }

    private fun nxshaDecode(hash: String): JSONObject = JSONObject(CryptoJsAes.decryptUrlSafe(hash, nxshaPassphrase))

    suspend fun invokeNxsha(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (tmdbId == null) return
        val type = if (season == null) "movie" else "tv"
        val baseObjStr = JSONObject().apply {
            put("tmdbId", tmdbId)
            put("imdb_id", "")
            put("type", type)
            if (season != null) put("season", season)
            if (episode != null) put("episode", episode)
        }.toString()

        val serversResp = runCatching { app.get("$nxshaApi/api/servers?q=${nxshaEncode(JSONObject(baseObjStr))}").text }.getOrNull() ?: return
        val serversHash = runCatching { JSONObject(serversResp).optString("_hash") }.getOrNull()?.takeIf { it.isNotBlank() } ?: return
        val servers = runCatching { nxshaDecode(serversHash).optJSONArray("servers") }.getOrNull() ?: return
        val headers = mapOf("Referer" to "$nxshaApi/")

        runCatching {
            val subsResp = app.get("$nxshaApi/api/subtitles?q=${nxshaEncode(JSONObject(baseObjStr))}").text
            val subsHash = JSONObject(subsResp).optString("_hash").takeIf { it.isNotBlank() }!!
            val subs = nxshaDecode(subsHash).optJSONArray("subtitles")!!
            for (i in 0 until subs.length()) {
                val sub = subs.optJSONObject(i) ?: continue
                val subUrl = sub.optString("uri").takeIf { it.isNotBlank() } ?: continue
                val lang = sub.optString("title").takeIf { it.isNotBlank() } ?: sub.optString("language", "Unknown")
                subtitleCallback(SubtitleFile(lang, subUrl))
            }
        }

        (0 until servers.length()).toList().amap { i ->
            val scraper = servers.optJSONObject(i)?.optString("scraper")?.takeIf { it.isNotBlank() } ?: return@amap
            // Omitting ex_lang returns every available language/quality variant in one call instead
            // of locking the response to a single hardcoded language.
            val sourcesObj = JSONObject(baseObjStr).apply {
                put("provider", scraper)
            }
            val sourcesResp = runCatching { app.get("$nxshaApi/api/sources?q=${nxshaEncode(sourcesObj)}").text }.getOrNull() ?: return@amap
            val sourcesHash = runCatching { JSONObject(sourcesResp).optString("_hash") }.getOrNull()?.takeIf { it.isNotBlank() } ?: return@amap
            val sources = runCatching { nxshaDecode(sourcesHash).optJSONArray("sources") }.getOrNull() ?: return@amap

            for (s in 0 until sources.length()) {
                val src = sources.optJSONObject(s) ?: continue
                val url = src.optString("url").takeIf { it.isNotBlank() } ?: continue
                // "label"/"quality" doubles as either a resolution ("MAIN") or a dub language ("Hindi")
                // depending on the scraper - either way it must be in the name or same-scraper variants collapse.
                val label = src.optString("label").takeIf { it.isNotBlank() } ?: src.optString("quality", "")
                val suffix = if (label.isNotBlank()) "-$label" else ""

                if (src.optString("type") == "mp4") {
                    callback(
                        newExtractorLink("Nxsha-$scraper$suffix", "Nxsha [$scraper] $label", url, ExtractorLinkType.VIDEO) {
                            this.headers = headers
                        }
                    )
                } else {
                    callback(directM3u8Link("Nxsha-$scraper$suffix", url, headers = headers, name = "Nxsha [$scraper] $label"))
                }
            }
        }
    }

    // -------------------------------------------------------------------------------------------
    // Videasy ("4K") / Vidking ("Rock" alias "Vidking") - both are the same speedracelight.com
    // backend under different vidbox branding: seeded XOR-stream cipher (MvmCipher), "mvm1" magic.
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
            val qs = params.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }

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
                // "quality" is a free-form label ("1080p", "Vimeos", "Hindi", ...) - getQualityFromName
                // parses the resolution ones and falls back to Unknown for anything else, so the raw
                // label is kept in the name too or a dub language like "Hindi" would be lost entirely.
                val rawLabel = src.optString("quality")
                val quality = getQualityFromName(rawLabel)
                callback(directM3u8Link("Videasy-$provider", srcUrl, quality = quality, name = "Videasy [$provider] $rawLabel"))
            }
        }
    }

    // -------------------------------------------------------------------------------------------
    // 111movies (111movies.net -> player.vidlove.cc backend api.shows.st)
    // -------------------------------------------------------------------------------------------
    private const val showsStApi = "https://api.shows.st"
    private const val vidloveOrigin = "https://vidlove.cc"

    suspend fun invoke111Movies(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (tmdbId == null) return
        val url = if (season == null) "$showsStApi/movie?id=$tmdbId&mode=json"
        else "$showsStApi/tv?id=$tmdbId&season=$season&episode=$episode&mode=json"
        val headers = mapOf("Referer" to "$vidloveOrigin/", "Origin" to vidloveOrigin)

        val json = runCatching { JSONObject(app.get(url, headers = headers).text) }.getOrNull() ?: return

        json.optJSONArray("subtitles")?.let { subs ->
            for (i in 0 until subs.length()) {
                val sub = subs.optJSONObject(i) ?: continue
                val subUrl = sub.optString("file").takeIf { it.isNotBlank() } ?: continue
                val lang = sub.optString("label", "Unknown")
                subtitleCallback(SubtitleFile(lang, subUrl))
            }
        }

        val streamUrl = json.optJSONObject("source")?.optString("url")?.takeIf { it.isNotBlank() } ?: return
        callback(directM3u8Link("111Movies", streamUrl, headers = headers))
    }

    // -------------------------------------------------------------------------------------------
    // Peachify (peachify.top -> none.eat-peach.sbs)
    // -------------------------------------------------------------------------------------------
    private const val peachifyApi = "https://none.eat-peach.sbs"
    private const val peachifyGcmKeyHex = "a8f2a1b5e9c470814f6b2c3a5d8e7f9c1a2b3c4d5e3f7a8b8cad1e2d0a4d5c5d"

    private fun decryptPeachifyUrl(payload: String): String {
        val parts = payload.split(".")
        require(parts.size == 3) { "unexpected peachify payload shape" }
        val iv = base64UrlDecode(parts[0])
        val ct = base64UrlDecode(parts[1])
        val tag = base64UrlDecode(parts[2])
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(hexToBytes(peachifyGcmKeyHex), "AES"),
            javax.crypto.spec.GCMParameterSpec(128, iv)
        )
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

    // -------------------------------------------------------------------------------------------
    // French (frembed.casa) - redirect aggregator into Voe/Dood/Uqload/etc, resolved via
    // loadExtractor. Response shape has drifted from a "links" array to flat "link"/"link1".."
    // link7"(+"vostfr" audio variants) fields, each a path to /api/stream?...&server=<field>
    // that 302s to the real embed host - confirmed live (uqload.vc/playmogo.com/vido.lol 302
    // targets all match bundled loadExtractor hosts).
    // -------------------------------------------------------------------------------------------
    private const val frembedApi = "https://frembed.casa"

    suspend fun invokeFrembed(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (tmdbId == null) return
        val headers = mapOf("Referer" to "$frembedApi/", "Origin" to frembedApi)
        val url = if (season == null) "$frembedApi/api/films?id=$tmdbId&idType=tmdb"
        else "$frembedApi/api/series?id=$tmdbId&idType=tmdb&sa=$season&epi=$episode"

        val json = runCatching { JSONObject(app.get(url, headers = headers).text) }.getOrNull() ?: return
        val linkPaths = json.keys().asSequence()
            .filter { it == "link" || it.startsWith("link") }
            .mapNotNull { json.optString(it).takeIf { path -> path.isNotBlank() } }
            .toList()

        linkPaths.amap { path ->
            val resolved = runCatching { app.get("$frembedApi$path", headers = headers, allowRedirects = false) }.getOrNull() ?: return@amap
            val target = resolved.headers["location"] ?: return@amap
            loadExtractor(target, frembedApi, subtitleCallback, callback)
        }
    }

    // -------------------------------------------------------------------------------------------
    // Vidnest (new.vidnest.fun) - shuffled-alphabet base64, not encryption.
    // -------------------------------------------------------------------------------------------
    private const val vidnestApi = "https://new.vidnest.fun"
    private val vidnestProviders = listOf(
        "hollymoviehd", "videasy", "buzz", "vidzee", "nextgencloudfabric", "klikxxi", "vidxyz", "allmovies", "vidlink"
    )
    private const val vidnestAlphabet = "RB0fpH8ZEyVLkv7c2i6MAJ5u3IKFDxlS1NTsnGaqmXYdUrtzjwObCgQP94hoeW+/="
    private const val standardB64Alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="

    private fun vidnestDecode(data: String): String {
        val map = vidnestAlphabet.zip(standardB64Alphabet).toMap()
        val translated = data.map { map[it] ?: it }.joinToString("")
        return String(Base64.decode(translated, Base64.DEFAULT), Charsets.UTF_8)
    }

    suspend fun invokeVidnest(tmdbId: Int?, season: Int?, episode: Int?, callback: (ExtractorLink) -> Unit) {
        if (tmdbId == null) return

        vidnestProviders.amap { provider ->
            val url = if (season == null) "$vidnestApi/$provider/movie/$tmdbId"
            else "$vidnestApi/$provider/tv/$tmdbId/$season/$episode"
            val json = runCatching { JSONObject(app.get(url).text) }.getOrNull() ?: return@amap
            val data = json.optString("data").takeIf { it.isNotBlank() } ?: return@amap
            val decoded = runCatching { JSONObject(vidnestDecode(data)) }.getOrNull() ?: return@amap

            val streams = decoded.optJSONArray("streams")
            if (streams != null) {
                for (i in 0 until streams.length()) {
                    val s = streams.optJSONObject(i) ?: continue
                    emitVidnestStream(provider, s, callback)
                }
                return@amap
            }

            // Some providers (videasy, buzz, nextgencloudfabric) return one flat stream object
            // instead of a "streams" array - {url, hls?, headers?} or {all_urls:[mirror,...]}.
            val urls = decoded.optJSONArray("all_urls")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
            } ?: decoded.optString("url").takeIf { it.isNotBlank() }?.let { listOf(it) } ?: return@amap

            val refHeaders = decoded.optJSONObject("headers")?.let { h ->
                h.keys().asSequence().associateWith { k -> h.optString(k) }
            } ?: decoded.optString("referer").takeIf { it.isNotBlank() }?.let { mapOf("Referer" to it) }
            ?: emptyMap()

            urls.forEach { streamUrl ->
                callback(directM3u8Link("Vidnest-$provider", streamUrl, headers = refHeaders))
            }
        }
    }

    private suspend fun emitVidnestStream(provider: String, s: JSONObject, callback: (ExtractorLink) -> Unit) {
        val streamUrl = s.optString("url").takeIf { it.isNotBlank() } ?: return
        val lang = s.optString("language", "")
        val quality = s.optInt("quality", 0)
        val refHeaders = s.optJSONObject("headers")?.optString("Referer")
            ?.takeIf { it.isNotBlank() }?.let { mapOf("Referer" to it) } ?: emptyMap()

        if (s.optString("type") == "mp4") {
            callback(
                newExtractorLink("Vidnest-$provider", "Vidnest [$provider] $lang", streamUrl, ExtractorLinkType.VIDEO) {
                    this.headers = refHeaders
                    if (quality > 0) this.quality = quality
                }
            )
        } else {
            callback(
                directM3u8Link(
                    "Vidnest-$provider",
                    streamUrl,
                    quality = quality.takeIf { it > 0 },
                    headers = refHeaders,
                    name = "Vidnest [$provider] $lang",
                )
            )
        }
    }

    // -------------------------------------------------------------------------------------------
    // Mplay (rozgarlelo.modiplay.xyz)
    // -------------------------------------------------------------------------------------------
    private const val modiplayApi = "https://rozgarlelo.modiplay.xyz"

    suspend fun invokeMplay(tmdbId: Int?, season: Int?, episode: Int?, callback: (ExtractorLink) -> Unit) {
        if (tmdbId == null) return
        val embedUrl = if (season == null) "$modiplayApi/embed/tmdb/movie?id=$tmdbId"
        else "$modiplayApi/embed/tmdb/tv?id=$tmdbId&s=$season&e=$episode"

        val doc = runCatching { app.get(embedUrl).document }.getOrNull() ?: return
        val iframeSrc = doc.selectFirst("#playerFrame")?.attr("src")?.takeIf { it.isNotBlank() } ?: return
        val proxyUrl = if (iframeSrc.startsWith("http")) iframeSrc else "$modiplayApi$iframeSrc"

        val proxyHtml = runCatching { app.get(proxyUrl).text }.getOrNull() ?: return
        val m3u8Path = Regex("""/proxy\.php\?serve_m3u8=1[^"'\s]+""").find(proxyHtml)?.value ?: return
        val m3u8Url = if (m3u8Path.startsWith("http")) m3u8Path else "$modiplayApi$m3u8Path"

        callback(newExtractorLink("Mplay", "Mplay", m3u8Url, ExtractorLinkType.M3U8))
    }

    // -------------------------------------------------------------------------------------------
    // Xpass (play.xpass.top)
    // -------------------------------------------------------------------------------------------
    private const val xpassApi = "https://play.xpass.top"

    suspend fun invokeXpass(tmdbId: Int?, season: Int?, episode: Int?, callback: (ExtractorLink) -> Unit) {
        if (tmdbId == null) return
        val embedUrl = if (season == null) "$xpassApi/e/movie/$tmdbId" else "$xpassApi/e/tv/$tmdbId/$season/$episode"
        val html = runCatching { app.get(embedUrl).text }.getOrNull() ?: return
        val backupsJson = Regex("""var backups=(\[.*?]);""").find(html)?.groupValues?.get(1) ?: return
        val backups = runCatching { JSONArray(backupsJson) }.getOrNull() ?: return
        val headers = mapOf("Referer" to "$xpassApi/")

        (0 until backups.length()).toList().amap { i ->
            val backup = backups.optJSONObject(i) ?: return@amap
            val rawUrl = backup.optString("url").takeIf { it.isNotBlank() } ?: return@amap
            val full = if (rawUrl.startsWith("http")) rawUrl else "$xpassApi$rawUrl"
            val json = runCatching { JSONObject(app.get(full, headers = headers).text) }.getOrNull() ?: return@amap
            val playlist = json.optJSONArray("playlist") ?: return@amap
            val name = backup.optString("name", "Xpass")

            for (p in 0 until playlist.length()) {
                val sources = playlist.optJSONObject(p)?.optJSONArray("sources") ?: continue
                for (s in 0 until sources.length()) {
                    val file = sources.optJSONObject(s)?.optString("file")?.takeIf { it.isNotBlank() } ?: continue
                    callback(directM3u8Link("Xpass-$name", file, headers = headers))
                }
            }
        }
    }

    // -------------------------------------------------------------------------------------------
    // 2Embed (2embed.stream) - plain HTTP only, no decryption.
    // -------------------------------------------------------------------------------------------
    suspend fun invoke2Embed(tmdbId: Int?, season: Int?, episode: Int?, callback: (ExtractorLink) -> Unit) {
        if (tmdbId == null) return
        val url = if (season == null) "http://king.2embed.stream/serverusa2/movie/$tmdbId"
        else "http://king.2embed.stream/serverusa2/tv/$tmdbId/$season/$episode?seasonInfo=false"
        val html = runCatching { app.get(url).text }.getOrNull() ?: return
        val headers = mapOf("Referer" to "http://king.2embed.stream/")

        Regex("""file\s*:\s*"([^"]+)"""").findAll(html).map { it.groupValues[1] }.forEach { file ->
            callback(directM3u8Link("2Embed", file, headers = headers))
        }
    }

    // -------------------------------------------------------------------------------------------
    // Cinemaos (cinemaos.tech) - HMAC-derived per-request secret, response may be AES-256-GCM
    // (optionally PBKDF2-derived key) encrypted.
    // -------------------------------------------------------------------------------------------
    private const val cinemaosPrimaryKey = "a7f3b9c2e8d4f1a6b5c9e2d7f4a8b3c6e1d9f7a4b2c8e5d3f9a6b4c1e7d2f8a5"
    private const val cinemaosSecondaryKey = "d3f8a5b2c9e6d1f7a4b8c5e2d9f3a6b1c7e4d8f2a9b5c3e7d4f1a8b6c2e9d5f3"
    private const val cinemaosEncKeyHex = "a1b2c3d4e4f6477658455678901477567890abcdef1234567890abcdef123456"
    private const val cinemaosGt = "6775dc8e702c08643385273df088c14952c590ddda02d14f"

    private fun cinemaosSecret(tmdbId: Int, season: Int?, episode: Int?): String {
        val parts = mutableListOf("tmdbId:$tmdbId")
        if (season != null) parts.add("seasonId:$season")
        if (episode != null) parts.add("episodeId:$episode")
        val content = parts.joinToString("|")
        return hmacSha256Hex(cinemaosSecondaryKey, hmacSha256Hex(cinemaosPrimaryKey, content))
    }

    suspend fun invokeCinemaos(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        title: String?,
        year: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (tmdbId == null) return
        val type = if (season == null) "movie" else "tv"
        val secret = cinemaosSecret(tmdbId, season, episode)
        val params = mutableListOf(
            "type" to type, "tmdbId" to "$tmdbId", "scraper" to "vf", "secret" to secret, "_gt" to cinemaosGt,
            "t" to (title ?: ""), "ry" to (year?.toString() ?: "")
        )
        if (season != null) params.add("seasonId" to "$season")
        if (episode != null) params.add("episodeId" to "$episode")
        val qs = params.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val headers = mapOf("Referer" to "https://cinemaos.tech/watch/$type/$tmdbId")

        val response = runCatching { app.get("https://cinemaos.tech/api/providerv5/scrape?$qs", headers = headers).text }.getOrNull() ?: return
        val json = runCatching { JSONObject(response) }.getOrNull() ?: return

        val resolved: JSONObject = if (json.optBoolean("encrypted", false) && json.has("data")) {
            val data = json.optJSONObject("data") ?: return
            val keyBytes = if (data.has("salt") && data.optInt("version", 0) >= 1) {
                pbkdf2Sha256(cinemaosEncKeyHex, data.optString("salt"), 100000, 32)
            } else hexToBytes(cinemaosEncKeyHex)
            val pt = runCatching {
                aesGcmDecrypt(keyBytes, data.optString("cin"), data.optString("encrypted"), data.optString("mao"))
            }.getOrNull() ?: return
            runCatching { JSONObject(pt) }.getOrNull() ?: return
        } else json

        // Confirmed live shape: {"name": <providerName>, "sources": {...}, "captions": [...]}.
        // "sources" came back empty for every title probed, so its populated shape is still a guess -
        // handled as both an object (key -> url/quality) and the older array assumption below.
        resolved.optJSONArray("captions")?.let { caps ->
            for (i in 0 until caps.length()) {
                val cap = caps.optJSONObject(i) ?: continue
                val subUrl = cap.optString("url").takeIf { it.isNotBlank() } ?: continue
                val lang = cap.optString("language").takeIf { it.isNotBlank() } ?: cap.optString("label", "Unknown")
                subtitleCallback(SubtitleFile(lang, subUrl))
            }
        }

        resolved.optJSONObject("sources")?.let { sourcesObj ->
            sourcesObj.keys().asSequence().toList().forEach { key ->
                val entry = sourcesObj.opt(key)
                val srcUrl = (entry as? JSONObject)?.optString("url")?.takeIf { it.isNotBlank() }
                    ?: (entry as? String)?.takeIf { it.isNotBlank() }
                if (srcUrl != null) callback(directM3u8Link("Cinemaos-$key", srcUrl, headers = headers))
            }
            if (sourcesObj.length() > 0) return
        }

        val streamUrl = resolved.optJSONObject("source")?.optString("url")?.takeIf { it.isNotBlank() }
            ?: resolved.optString("url").takeIf { it.isNotBlank() }
            ?: resolved.optJSONObject("stream")?.optString("playlist")?.takeIf { it.isNotBlank() }
            ?: resolved.optJSONArray("sources")?.optJSONObject(0)?.optString("url")?.takeIf { it.isNotBlank() }
            ?: return

        callback(directM3u8Link("Cinemaos", streamUrl, headers = headers))
    }

    // -------------------------------------------------------------------------------------------
    // Max (ythd.org -> cloudorchestranova.com -> data.vidsrcme.ru) - the API's `stream_urls` is
    // WASM-ChaCha20-encrypted (see WasmStreamDecryptor); each decrypted master.m3u8 also needs a
    // short-lived, IP-bound JWT from that host's own /generate.php stamped on as ?token=.
    // -------------------------------------------------------------------------------------------
    private const val vidsrcmeApi = "https://data.vidsrcme.ru"
    private val maxHeaders = mapOf("Referer" to "https://cloudorchestranova.com/", "Origin" to "https://cloudorchestranova.com")

    suspend fun invokeMax(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (tmdbId == null) return
        val type = if (season == null) "movie" else "tv"
        var apiUrl = "$vidsrcmeApi/api.php?type=$type&tmdb=$tmdbId&stream_urls"
        if (season != null) apiUrl += "&season=$season"
        if (episode != null) apiUrl += "&episode=$episode"

        val json = runCatching { JSONObject(app.get(apiUrl, headers = maxHeaders).text) }.getOrNull() ?: return
        val data = json.optJSONObject("data") ?: return

        json.optJSONArray("default_subs")?.let { subs ->
            for (i in 0 until subs.length()) {
                val sub = subs.optJSONObject(i) ?: continue
                val subUrl = sub.optString("url").takeIf { it.isNotBlank() }
                    ?: sub.optString("file").takeIf { it.isNotBlank() } ?: continue
                val lang = sub.optString("label").takeIf { it.isNotBlank() } ?: sub.optString("lang", "Unknown")
                subtitleCallback(SubtitleFile(lang, subUrl))
            }
        }

        val streamUrlsRaw = data.opt("stream_urls")
        val masterUrls: List<String> = when (streamUrlsRaw) {
            is JSONArray -> (0 until streamUrlsRaw.length()).mapNotNull { streamUrlsRaw.optString(it).takeIf(String::isNotBlank) }
            is String -> {
                val vs = json.optJSONObject("vs") ?: return
                val wasmBytes = vs.optString("wasm_url").takeIf { it.isNotBlank() }?.let {
                    runCatching { app.get(it, headers = maxHeaders).body.bytes() }.getOrNull()
                } ?: vs.optString("wasm").takeIf { it.isNotBlank() }?.let { Base64.decode(it, Base64.DEFAULT) }
                if (wasmBytes == null) return
                runCatching { WasmStreamDecryptor.decrypt(wasmBytes, streamUrlsRaw) }.getOrNull() ?: return
            }
            else -> return
        }

        masterUrls.forEachIndexed { i, masterUrl ->
            val origin = runCatching { java.net.URL(masterUrl) }.getOrNull()?.let { "${it.protocol}://${it.host}" } ?: return@forEachIndexed
            val token = runCatching { app.get("$origin/generate.php", headers = maxHeaders).text.trim() }.getOrNull()
            val finalUrl = if (token.isNullOrBlank()) masterUrl else masterUrl + (if ("?" in masterUrl) "&" else "?") + "token=$token"
            callback(directM3u8Link("Max", finalUrl, headers = maxHeaders, name = "Max${if (masterUrls.size > 1) " ${i + 1}" else ""}"))
        }
    }

    // -------------------------------------------------------------------------------------------
    // Tongo (nontongo.win -> vibuxer.com) - nontongo's loadmix.php/view1.php hops are both
    // directly reachable by URL (no need to scrape the embed page first); the final vibuxer.com
    // page is a StreamHG-style packed JWPlayer script whose `links` object offers a same-host
    // relative m3u8 (hls4) plus two CDN fallbacks (hls3/hls2) - hls4 needs no extra token/referer.
    // -------------------------------------------------------------------------------------------
    private const val nontongoApi = "https://nontongo.win"

    suspend fun invokeTongo(tmdbId: Int?, season: Int?, episode: Int?, callback: (ExtractorLink) -> Unit) {
        if (tmdbId == null) return
        val type = if (season == null) "movie" else "tv"
        var loadmixUrl = "$nontongoApi/x1/promax/loadmix.php?id=$tmdbId&type=$type"
        if (season != null) loadmixUrl += "&season=$season"
        if (episode != null) loadmixUrl += "&episode=$episode"

        val loadmixHtml = runCatching { app.get(loadmixUrl, headers = vidboxHeaders).text }.getOrNull() ?: return
        val viewUrl = Regex("""id=["']player["'][^>]*src=["']([^"']+)["']""").find(loadmixHtml)?.groupValues?.get(1) ?: return

        val viewHtml = runCatching { app.get(viewUrl, headers = mapOf("Referer" to loadmixUrl)).text }.getOrNull() ?: return
        val atobBlob = Regex("""atob\("([^"]+)"\)""").find(viewHtml)?.groupValues?.get(1) ?: return
        val decodedHtml = runCatching { String(Base64.decode(atobBlob, Base64.DEFAULT), Charsets.UTF_8) }.getOrNull() ?: return
        val vibuxerUrl = Regex("""src=["'](https://vibuxer\.com/[^"']+)["']""").find(decodedHtml)?.groupValues?.get(1) ?: return

        val vibuxerHtml = runCatching { app.get(vibuxerUrl, headers = mapOf("Referer" to viewUrl)).text }.getOrNull() ?: return
        val packedScript = Regex("""eval\(function\(p,a,c,k,e,d\).*?split\('\|'\)\)\)""", RegexOption.DOT_MATCHES_ALL)
            .find(vibuxerHtml)?.value ?: return
        val unpacked = runCatching { getAndUnpack(packedScript) }.getOrNull() ?: return
        val links = Regex("""var links=(\{.*?\});""").find(unpacked)?.groupValues?.get(1)
            ?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return

        val vibuxerOrigin = "https://vibuxer.com"
        val chosen = links.optString("hls4").takeIf { it.isNotBlank() }
            ?: links.optString("hls3").takeIf { it.isNotBlank() }
            ?: links.optString("hls2").takeIf { it.isNotBlank() }
            ?: return
        val streamUrl = if (chosen.startsWith("http")) chosen else "$vibuxerOrigin$chosen"

        callback(directM3u8Link("Tongo", streamUrl, referer = vibuxerOrigin, headers = mapOf("Referer" to "$vibuxerOrigin/")))
    }

    // -------------------------------------------------------------------------------------------
    // Rive (rivestream.app) - plain GET, no session/cookies needed, just a static secretKey
    // lifted from its client bundle. Movie-only: the same key 401s on requestID=tvVideoProvider
    // (TV apparently needs a different one not yet found).
    // -------------------------------------------------------------------------------------------
    private const val riveApi = "https://www.rivestream.app/api/backendfetch"
    private const val riveSecretKey = "LTcxZGI2MjNk"
    private val riveProviders = listOf(
        "apex", "pulse", "solstice", "quasar", "horizon", "primevids", "flowcast", "asiacloud", "citadel", "hindicast", "guru"
    )

    suspend fun invokeRive(
        tmdbId: Int?,
        season: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (tmdbId == null || season != null) return
        val headers = mapOf("Referer" to "https://www.rivestream.app/")

        riveProviders.amap { service ->
            val url = "$riveApi?requestID=movieVideoProvider&id=$tmdbId&service=$service&secretKey=$riveSecretKey&proxyMode=undefined"
            val data = runCatching { JSONObject(app.get(url, headers = headers).text).optJSONObject("data") }.getOrNull() ?: return@amap

            data.optJSONArray("captions")?.let { caps ->
                for (i in 0 until caps.length()) {
                    val cap = caps.optJSONObject(i) ?: continue
                    val subUrl = cap.optString("file").takeIf { it.isNotBlank() } ?: continue
                    val lang = cap.optString("label").takeIf { it.isNotBlank() } ?: cap.optString("language", "Unknown")
                    subtitleCallback(SubtitleFile(lang, subUrl))
                }
            }

            val sources = data.optJSONArray("sources") ?: return@amap
            for (i in 0 until sources.length()) {
                val src = sources.optJSONObject(i) ?: continue
                val srcUrl = src.optString("url").takeIf { it.isNotBlank() } ?: continue
                val label = src.opt("quality")?.toString() ?: ""
                val name = "Rive [$service] $label"
                if (src.optString("format") == "hls") {
                    callback(directM3u8Link("Rive-$service", srcUrl, headers = headers, quality = getQualityFromName(label), name = name))
                } else {
                    callback(
                        newExtractorLink("Rive-$service", name, srcUrl, ExtractorLinkType.VIDEO) {
                            this.headers = headers
                            this.quality = getQualityFromName(label)
                        }
                    )
                }
            }
        }
    }

    // -------------------------------------------------------------------------------------------
    // Bravo (moviesapi.to) - standard JWPlayer packed-script iframe aggregator. Unreachable from
    // the research sandbox (network-level block), so this is the known pattern, not live-verified.
    // -------------------------------------------------------------------------------------------
    private const val moviesApiTo = "https://moviesapi.to"

    suspend fun invokeBravo(tmdbId: Int?, season: Int?, episode: Int?, callback: (ExtractorLink) -> Unit) {
        if (tmdbId == null) return
        val href = if (season == null) "$moviesApiTo/movie/$tmdbId" else "$moviesApiTo/tv/$tmdbId/$season/$episode"
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

        callback(directM3u8Link("Bravo", m3u8, referer = iframeSrc, headers = mapOf("Referer" to iframeSrc)))
    }
}
