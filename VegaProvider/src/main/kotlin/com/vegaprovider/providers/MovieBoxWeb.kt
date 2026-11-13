package com.vegaprovider.providers

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.vegaprovider.core.VegaBase
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** MovieBox Web (urls.json key "movieBoxWeb"). Public h5 JSON API + Nuxt SSR detail page; captured live 2026-11-13. */
class MovieBoxWeb : VegaBase("movieBoxWeb", "https://officialmoviebox.com") {
    override var name = "MovieBox Web"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf("" to "Trending", "ONEROOM_MOVIE" to "Movies")

    private val hdr get() = mapOf(
        "User-Agent" to UA, "Accept" to "application/json", "Cookie" to "token=",
        "x-client-info" to "{\"timezone\":\"Asia/Colombo\"}", "x-source" to "",
    )

    private fun card(title: String, path: String, img: String?) =
        newMovieSearchResponse(title.replace(Regex("\\s*\\[.*?\\]\\s*$"), ""), "$mainUrl/moviesDetail/$path", TvType.Movie) { posterUrl = img }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        resolveHost()
        val tab = if (request.data.isEmpty()) "" else "&tabId=${request.data}"
        val list = JSONObject(app.get("$mainUrl/wefeed-h5api-bff/subject/trending?page=$page&perPage=18$tab", headers = hdr).text)
            .optJSONObject("data")?.optJSONArray("subjectList") ?: return null
        val items = (0 until list.length()).map { list.getJSONObject(it) }.filter { it.optBoolean("hasResource", true) }.map {
            card(it.optString("title"), it.optString("detailPath"), it.optJSONObject("cover")?.optString("url"))
        }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        resolveHost()
        val doc = app.get("$mainUrl/newWeb/searchResult?keyword=${URLEncoder.encode(query, "UTF-8")}", headers = hdr).document
        return doc.select("a.card[href^=/moviesDetail/]").mapNotNull { a ->
            val t = a.selectFirst("h2")?.attr("title")?.ifBlank { null } ?: a.selectFirst("h2")?.text() ?: return@mapNotNull null
            val img = a.selectFirst("img")
            card(t, a.attr("href").removePrefix("/moviesDetail/"), img?.attr("data-src")?.ifBlank { null } ?: img?.attr("src"))
        }
    }

    // Nuxt 3 devalue payload: flat array, ints are indices into it.
    private fun hydrate(a: JSONArray, i: Int, seen: HashMap<Int, Any?> = HashMap()): Any? {
        if (i < 0) return null
        if (seen.containsKey(i)) return seen[i]
        val v = a.get(i)
        return when (v) {
            is JSONArray -> {
                if (v.length() > 0 && v.get(0) is String) { val r = hydrate(a, v.getInt(1), seen); seen[i] = r; r }
                else { val l = ArrayList<Any?>(); seen[i] = l; for (k in 0 until v.length()) l.add(hydrate(a, v.getInt(k), seen)); l }
            }
            is JSONObject -> { val m = HashMap<String, Any?>(); seen[i] = m; v.keys().forEach { m[it] = hydrate(a, v.getInt(it), seen) }; m }
            JSONObject.NULL -> null
            else -> v
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun findDetail(x: Any?): Map<String, Any?>? {
        if (x is Map<*, *>) {
            if (x["subject"] is Map<*, *> && x["resource"] is Map<*, *>) return x as Map<String, Any?>
            x.values.forEach { findDetail(it)?.let { r -> return r } }
        } else if (x is List<*>) x.forEach { findDetail(it)?.let { r -> return r } }
        return null
    }

    override suspend fun load(url: String): LoadResponse? {
        resolveHost()
        val html = app.get(url, headers = hdr).text
        val raw = Regex("id=\"__NUXT_DATA__\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1) ?: return null
        val arr = JSONArray(raw)
        val detail = findDetail(hydrate(arr, 0)) ?: return null
        val s = detail["subject"] as Map<*, *>
        val sid = s["subjectId"].toString()
        val path = s["detailPath"].toString()
        val title = (s["title"] as? String ?: return null).replace(Regex("\\s*\\[.*?\\]\\s*$"), "")
        // ponytail: dubs are separate subjects; carried in the data string as "id|path|lang" and each is tried in loadLinks.
        val dubs = (s["dubs"] as? List<*>)?.mapNotNull { d ->
            (d as? Map<*, *>)?.let { "${it["subjectId"]}|${it["detailPath"]}|${it["lanName"] ?: "Original"}" }
        }?.ifEmpty { null } ?: listOf("$sid|$path|Original")
        val dubStr = dubs.joinToString(";")
        val poster = (s["cover"] as? Map<*, *>)?.get("url") as? String
        val year = (s["releaseDate"] as? String)?.take(4)?.toIntOrNull()
        val plot = s["description"] as? String
        if ((s["subjectType"] as? Number)?.toInt() != 2) {
            return newMovieLoadResponse(title, url, TvType.Movie, "$dubStr@0@0") { posterUrl = poster; this.year = year; this.plot = plot }
        }
        val episodes = ArrayList<Episode>()
        ((detail["resource"] as Map<*, *>)["seasons"] as? List<*>)?.forEach { se ->
            val m = se as? Map<*, *> ?: return@forEach
            val sn = (m["se"] as? Number)?.toInt() ?: return@forEach
            if (sn <= 0) return@forEach
            val eps = (m["allEp"] as? String)?.split(",")?.mapNotNull { it.trim().toIntOrNull() }?.filter { it > 0 }?.ifEmpty { null }
                ?: (1..((m["maxEp"] as? Number)?.toInt() ?: 0)).toList()
            eps.forEach { e -> episodes.add(newEpisode("$dubStr@$sn@$e") { season = sn; episode = e }) }
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) { posterUrl = poster; this.year = year; this.plot = plot }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val (dubStr, se, ep) = data.split("@")
        var any = false
        for (d in dubStr.split(";")) {
            val (sid, path, lang) = d.split("|")
            val q = if (se != "0") "&se=$se&ep=$ep" else ""
            val ref = "$mainUrl/movies/$path?id=$sid&type=%2Fmovie%2Fdetail&detailSe=${if (se != "0") se else ""}&detailEp=${if (se != "0") ep else ""}&lang=en"
            val res = runCatching {
                JSONObject(app.get("$mainUrl/wefeed-h5api-bff/subject/play?subjectId=$sid&detailPath=$path$q", headers = hdr + ("Referer" to ref)).text)
            }.getOrNull()?.optJSONObject("data") ?: continue
            for (key in listOf("streams", "hls", "dash")) {
                val l = res.optJSONArray(key) ?: continue
                for (k in 0 until l.length()) {
                    val o = l.getJSONObject(k)
                    val u = o.optString("url")
                    if (u.isEmpty() || o.optBoolean("vipLocked")) continue
                    val fmt = o.optString("format").uppercase()
                    val type = when {
                        fmt == "HLS" || u.substringBefore("?").endsWith(".m3u8") -> ExtractorLinkType.M3U8
                        fmt == "DASH" || u.substringBefore("?").endsWith(".mpd") -> ExtractorLinkType.DASH
                        else -> ExtractorLinkType.VIDEO
                    }
                    val rs = o.optString("resolutions")
                    val qual = rs.split(",").mapNotNull { it.toIntOrNull() }.maxOrNull() ?: Qualities.Unknown.value
                    callback(newExtractorLink(name, "$name $lang $rs".trim(), u, type) {
                        referer = "$mainUrl/"; quality = qual
                    })
                    any = true
                    if (o.optString("id").isNotEmpty()) runCatching {
                        val caps = JSONObject(app.get("$mainUrl/wefeed-h5api-bff/subject/caption?format=${o.optString("format")}&id=${o.optString("id")}&subjectId=$sid&detailPath=$path", headers = hdr + ("Referer" to ref)).text)
                            .optJSONObject("data")?.optJSONArray("captions")
                        if (caps != null) for (c in 0 until caps.length()) caps.getJSONObject(c).let {
                            if (it.optString("url").isNotEmpty()) subtitleCallback(SubtitleFile(it.optString("lanName", "Subtitle"), it.optString("url")))
                        }
                    }
                }
            }
        }
        return any
    }
}
