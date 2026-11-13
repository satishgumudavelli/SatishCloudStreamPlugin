package com.vegaprovider.providers

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.vegaprovider.core.VegaBase
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID

/** mxplayer (urls.json key "mxplayer"). Public api.mxplayer.in JSON (needs any userid + JSON content-type); captured live 2026-11-13. */
class MxPlayer : VegaBase("mxplayer", "https://www.mxplayer.in") {
    override var name = "MX Player (Vega)"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "browseLangFilterIds=hi&type=1" to "Hindi Movies",
        "browseLangFilterIds=te&type=1" to "Telugu Movies",
        "genreFilterIds=48efa872f6f17facebf6149dfc536ee1&type=2" to "Drama Shows",
        "genreFilterIds=b413dff55bdad743c577a8bea3b65044&type=2" to "Crime Shows",
        "genreFilterIds=2dd5daf25be5619543524f360c73c3d8&type=2" to "Thriller Shows",
    )

    private val api = "https://api.mxplayer.in/v1/web"
    private val cdn = "https://d3sgzbosmwirao.cloudfront.net/"
    private val img = "https://qqcdnpictest.mxplay.com/"
    private val userId = UUID.randomUUID().toString()
    private val end get() = "&device-density=2&userid=$userId&platform=com.mxplay.desktop&content-languages=hi,en&kids-mode-enabled=false"
    private val hdr get() = mapOf("User-Agent" to UA, "Referer" to "$mainUrl/", "Origin" to mainUrl, "Content-Type" to "application/json")

    private fun JSONObject.poster(): String? {
        val a = optJSONArray("imageInfo") ?: return null
        val o = (0 until a.length()).map { a.getJSONObject(it) }
        return (o.firstOrNull { it.optString("type") == "portrait_large" } ?: o.firstOrNull())?.optString("url")?.takeIf { it.isNotBlank() }?.let { img + it }
    }

    /** First non-DRM HLS url of an MX stream object, or null. ponytail: DASH ignored, HLS only. */
    private fun JSONObject.hls(): String? {
        val s = optJSONObject("stream") ?: return null
        if (s.optBoolean("drmProtect")) return null
        val u = s.optJSONObject("mxplay")?.optJSONObject("hls")?.optString("high")?.takeIf { it.isNotBlank() && it != "null" }
            ?: s.optJSONObject("hls")?.optString("high")?.takeIf { it.isNotBlank() && it != "null" }
            ?: s.optJSONObject("thirdParty")?.optString("hlsUrl")?.takeIf { it.isNotBlank() && it != "null" }
            ?: return null
        return if (u.startsWith("http")) u else cdn + u
    }

    // Movies carry their stream in the url (the API has no by-id movie lookup); shows carry the show id.
    private fun JSONObject.card(): SearchResponse? {
        val t = optString("title")
        if (optString("type") == "tvshow") return newTvSeriesSearchResponse(t, "show:" + optString("id"), TvType.TvSeries) { posterUrl = poster() }
        val h = hls() ?: return null
        val j = JSONObject().put("t", t).put("p", poster()).put("d", optString("description")).put("h", h).toString()
        return if (optString("type") == "movie") newMovieSearchResponse(t, j, TvType.Movie) { posterUrl = poster() } else null
    }

    private fun JSONObject.items(key: String) = optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        resolveHost()
        val j = JSONObject(app.get("$api/detail/browseItem?pageNum=$page&pageSize=20&isCustomized=true&${request.data}$end", headers = hdr).text)
        return newHomePageResponse(request.name, j.items("items").mapNotNull { it.card() })
    }

    override suspend fun search(query: String): List<SearchResponse> {
        resolveHost()
        val j = JSONObject(app.post("$api/search/resultv2?query=${URLEncoder.encode(query, "UTF-8")}$end", headers = hdr, json = emptyMap<String, String>()).text)
        return j.items("sections").flatMap { it.items("items") }.mapNotNull { it.card() }
    }

    override suspend fun load(url: String): LoadResponse? {
        resolveHost()
        if (url.startsWith("{")) {
            val m = JSONObject(url)
            return newMovieLoadResponse(m.optString("t"), url, TvType.Movie, m.optString("h")) { posterUrl = m.optString("p"); plot = m.optString("d") }
        }
        val id = url.removePrefix("show:")
        val d = JSONObject(app.get("$api/detail/collection?type=tvshow&id=$id$end", headers = hdr).text)
        val title = d.optString("title").ifBlank { return null }
        val seasons = d.items("tabs").firstOrNull { it.optString("type") == "tvshowepisodes" }?.items("containers") ?: emptyList()
        val eps = ArrayList<Episode>()
        for (s in seasons) {
            val sid = s.optString("id")
            var next: String? = ""
            var guard = 0
            while (next != null && guard++ < 20) {
                val e = JSONObject(app.get("$api/detail/tab/tvshowepisodes?type=season&${next}&id=$sid&sortOrder=0$end", headers = hdr).text)
                e.items("items").forEach {
                    val h = it.hls() ?: return@forEach
                    eps.add(newEpisode(h) { name = it.optString("title"); season = s.optInt("sequence", 1); episode = it.optInt("sequence"); posterUrl = it.poster() })
                }
                next = e.optString("next").takeIf { it.isNotBlank() && it != "null" }
            }
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) { posterUrl = d.poster(); plot = d.optString("description") }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ): Boolean {
        callback(newExtractorLink("MX Player", "MX Player", data, ExtractorLinkType.M3U8) { referer = "$mainUrl/" })
        return true
    }
}
