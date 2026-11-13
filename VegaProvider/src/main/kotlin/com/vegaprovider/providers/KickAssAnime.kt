package com.vegaprovider.providers

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.vegaprovider.core.VegaBase
import org.json.JSONObject

/** kickAssAnime (urls.json key "kickAssAnime"). JSON API under /api, krussdomi cat-player HLS; captured live 2026-11-13. */
class KickAssAnime : VegaBase("kickAssAnime", "https://kaa.lt") {
    override var name = "KickAssAnime (Vega)"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    override val mainPage = mainPageOf(
        "/api/show/trending?" to "Trending",
        "/api/show/popular?" to "Popular",
        "/api/show/recent?type=sub&" to "Recent (Sub)",
        "/api/show/recent?type=dub&" to "Recent (Dub)",
        "/api/anime?" to "All Anime",
    )

    private val hdr get() = mapOf("User-Agent" to UA, "Accept" to "application/json, text/plain, */*")

    private suspend fun api(path: String) = JSONObject(app.get("$mainUrl$path", headers = hdr).text)

    private fun JSONObject.poster() = optJSONObject("poster")?.optString("hq")?.takeIf { it.isNotBlank() }?.let { "$mainUrl/image/poster/$it.webp" }

    private fun JSONObject.cards() = optJSONArray("result")?.let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            val t = o.optString("title_en").ifBlank { o.optString("title") }
            newAnimeSearchResponse(t, "$mainUrl/${o.optString("slug")}", if (o.optString("type") == "movie") TvType.AnimeMovie else TvType.Anime) {
                posterUrl = o.poster()
            }
        }
    } ?: emptyList()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        resolveHost()
        return newHomePageResponse(request.name, api("${request.data}page=$page").cards())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        resolveHost()
        val r = app.post("$mainUrl/api/fsearch", headers = hdr + mapOf("Content-Type" to "application/json", "Referer" to "$mainUrl/"), json = mapOf("page" to 1, "query" to query))
        return JSONObject(r.text).cards()
    }

    override suspend fun load(url: String): LoadResponse? {
        resolveHost()
        val slug = url.removePrefix(mainUrl).trim('/').substringBefore('/')
        val d = runCatching { api("/api/show/$slug") }.getOrNull() ?: return null
        val title = d.optString("title_en").ifBlank { d.optString("title") }.ifBlank { return null }
        val langs = runCatching { api("/api/show/$slug/language").optJSONArray("result") }.getOrNull()
            ?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty().ifEmpty { listOf("ja-JP") }
        val perLang = langs.mapNotNull { lang ->
            val eps = ArrayList<JSONObject>()
            var p = 1
            do {
                val j = runCatching { api("/api/show/$slug/episodes?page=$p&lang=$lang") }.getOrNull() ?: break
                j.optJSONArray("result")?.let { a -> (0 until a.length()).forEach { eps.add(a.getJSONObject(it)) } }
                val pages = j.optJSONArray("pages")?.length() ?: 1
            } while (++p <= pages)
            if (eps.isEmpty()) return@mapNotNull null
            // dub/other locales can list episodes with no servers (TS does the same probe on ep 1)
            if (lang != "ja-JP") {
                val f = eps[0]
                val ok = runCatching { api("/api/show/$slug/episode/ep-${f.optString("episode_string")}-${f.optString("slug")}").optJSONArray("servers")?.length() ?: 0 }.getOrDefault(1) > 0
                if (!ok) return@mapNotNull null
            }
            lang to eps.map { e ->
                newEpisode("$slug/ep-${e.optString("episode_string")}-${e.optString("slug")}") {
                    episode = e.optInt("episode_number")
                    name = e.optString("title").takeIf { it.isNotBlank() }
                    posterUrl = e.optJSONObject("thumbnail")?.optString("hq")?.takeIf { it.isNotBlank() }?.let { "$mainUrl/image/thumbnail/$it.webp" }
                }
            }
        }
        val type = if (d.optString("type") == "movie") TvType.AnimeMovie else TvType.Anime
        return newAnimeLoadResponse(title, "$mainUrl/$slug", type) {
            posterUrl = d.poster()
            plot = d.optString("synopsis")
            year = d.optInt("year").takeIf { it > 0 }
            // ponytail: ja-JP -> Subbed, en-US -> Dubbed, other locales ignored
            perLang.forEach { (lang, eps) ->
                when (lang) {
                    "ja-JP" -> addEpisodes(DubStatus.Subbed, eps)
                    "en-US" -> addEpisodes(DubStatus.Dubbed, eps)
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val (slug, ep) = data.split('/', limit = 2)
        val servers = runCatching { api("/api/show/$slug/episode/$ep").optJSONArray("servers") }.getOrNull() ?: return false
        var any = false
        for (i in 0 until servers.length()) {
            val s = servers.getJSONObject(i)
            // ponytail: only the HLS "manifest" player is handled; the DASH (type=dash, bl.krussdomi.com mpd) server is skipped
            val src = s.optString("src").replace("/cat-player/vast", "/cat-player/player")
            if (src.isBlank() || src.contains("type=dash")) continue
            val html = runCatching { app.get(src, headers = hdr + ("Referer" to "$mainUrl/")).text }.getOrNull()?.replace("&quot;", "\"") ?: continue
            val m = Regex("manifest\":\\[\\d+,\"([^\"]+\\.m3u8[^\"]*)\"]").find(html)?.groupValues?.get(1)?.replace("\\", "") ?: continue
            val origin = Regex("^https?://[^/]+").find(src)?.value ?: mainUrl
            val h = mapOf("User-Agent" to UA, "Origin" to origin, "Referer" to src)
            Regex("\"language\":\\[\\d+,\"([^\"]+)\"][^}]+?\"name\":\\[\\d+,\"([^\"]+)\"][^}]+?\"src\":\\[\\d+,\"([^\"]+)\"]").findAll(html).forEach {
                subtitleCallback(SubtitleFile(it.groupValues[2], it.groupValues[3].replace("\\", "")))
            }
            callback(newExtractorLink(s.optString("name"), "${s.optString("name")} (Auto)", m, ExtractorLinkType.M3U8) {
                referer = src
                headers = h
            })
            any = true
        }
        return any
    }
}
