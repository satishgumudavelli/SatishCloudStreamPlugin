package com.tmdb

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

/**
 * cinemaos.tech/.in's real native source pipeline - ported from CinemaOsProvider's
 * CinemaOsExtractor (same domains.json DomainResolver target). See CinemaOsExtractor.kt for full
 * detail on how this was reverse-engineered from the site's own client JS.
 */
private const val cinemaosFallbackDomain = "cinemaos.tech"

// Shared across providers in this repo, keyed by "name" - see VidboxProvider's DomainResolver.kt.
private const val domainsJsonUrl =
    "https://raw.githubusercontent.com/satishgumudavelli/SatishCloudStreamPlugin/master/domains.json"

private val cinemaosSources = mapOf(
    "ms" to "MovSrc", "vp" to "Vapor", "vr" to "VidRock", "va" to "VidApi", "ts" to "TouStream",
    "vy" to "Videasy", "fx" to "FlaxMovies", "py" to "Peachify", "mrsub" to "Miruro (Sub)",
    "mrdub" to "Miruro (Dub)", "tesub" to "TryEmbed (Sub)", "tedub" to "TryEmbed (Dub)",
    "mt" to "MeowTV", "cs" to "CineSu", "iy" to "Icefy", "vl" to "VidLink", "vz" to "VidZee",
    "nhd" to "nhdapi", "vdn" to "VidNest", "zm" to "02Movie", "vx" to "VixSrc", "fz" to "FlixTrz",
    "vs" to "VidSrc", "vdy" to "Vidify", "lm" to "LookMovie", "fs" to "FShareTV", "cz" to "Cinezo",
    "fn" to "Fsonic", "sc" to "Screenscape",
)

object TmdbExtractor {

    private val domainResolver = DomainResolver(
        domainsJsonUrl = domainsJsonUrl,
        targetName = "cinemaos",
        fallbackDomain = cinemaosFallbackDomain,
    )

    suspend fun resolve(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        title: String?,
        year: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (tmdbId == null) return
        val base = domainResolver.resolveMainUrl()
        val type = if (season == null) "movie" else "tv"

        cinemaosSources.keys.toList().amap { source ->
            val params = mutableListOf("id" to "$tmdbId", "type" to type, "source" to source)
            if (season != null) params.add("season" to "$season")
            if (episode != null) params.add("episode" to "$episode")
            val qs = params.joinToString("&") { (k, v) -> "$k=$v" }

            val response = runCatching { app.get("$base/api/vyla-stream?$qs").text }.getOrNull() ?: return@amap
            val json = runCatching { JSONObject(response) }.getOrNull() ?: return@amap
            if (!json.optBoolean("ok", false)) return@amap

            val label = cinemaosSources[source] ?: source

            json.optJSONArray("subtitles")?.let { subs ->
                for (i in 0 until subs.length()) {
                    val sub = subs.optJSONObject(i) ?: continue
                    val subUrl = sub.optString("url").takeIf { it.isNotBlank() } ?: continue
                    val lang = sub.optString("language").takeIf { it.isNotBlank() } ?: sub.optString("label", "Unknown")
                    subtitleCallback(SubtitleFile(lang, subUrl))
                }
            }

            val subStreams = json.optJSONArray("subStreams")
            if (subStreams == null || subStreams.length() == 0) {
                val url = json.optString("rawUrl").takeIf { it.isNotBlank() } ?: json.optString("url").takeIf { it.isNotBlank() } ?: return@amap
                val headers = json.optJSONObject("headers")?.let { h -> h.keys().asSequence().associateWith { k -> h.optString(k) } } ?: emptyMap()
                generateM3u8("CinemaOS-$source", url, "", headers = headers).forEach(callback)
                return@amap
            }

            for (i in 0 until subStreams.length()) {
                val stream = subStreams.optJSONObject(i) ?: continue
                val streamUrl = stream.optString("rawUrl").takeIf { it.isNotBlank() }
                    ?: stream.optString("url").takeIf { it.isNotBlank() } ?: continue
                val streamHeaders = stream.optJSONObject("headers")?.let { h ->
                    h.keys().asSequence().associateWith { k -> h.optString(k) }
                } ?: emptyMap()
                val name = stream.optString("name").takeIf { it.isNotBlank() }
                val quality = getQualityFromName(stream.optString("quality", ""))

                if (stream.optString("streamType") == "mp4") {
                    callback(
                        newExtractorLink("CinemaOS-$source", "CinemaOS [$label] ${name ?: ""}", streamUrl, ExtractorLinkType.VIDEO) {
                            this.headers = streamHeaders
                            this.quality = quality
                        }
                    )
                } else {
                    generateM3u8("CinemaOS-$source", streamUrl, "", headers = streamHeaders, quality = quality).forEach(callback)
                }
            }
        }
    }
}
