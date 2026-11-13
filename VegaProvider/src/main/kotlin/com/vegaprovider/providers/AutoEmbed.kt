package com.vegaprovider.providers

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.vegaprovider.core.VegaBase
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import android.util.Base64

/**
 * autoEmbed: Cinemeta browse/meta + movy.sx (api.wecollege.net) streams. Cinemeta and movy hops captured live 2026-11-13.
 * ponytail: cinejoy and vidstuck extractors from the TS are not ported (unverified); only movy.
 */
class AutoEmbed : VegaBase("autoembed", "https://v3-cinemeta.strem.io") {
    override var name = "AutoEmbed"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "top/catalog/movie/top" to "Popular Movies",
        "top/catalog/series/top" to "Popular TV Shows",
        "imdbRating/catalog/movie/imdbRating" to "Featured Movies",
        "imdbRating/catalog/series/imdbRating" to "Featured TV Shows",
    )

    private val catalogs = "https://cinemeta-catalogs.strem.io"

    private fun JSONArray?.cards(): List<SearchResponse> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i ->
            val m = getJSONObject(i)
            val id = m.optString("imdb_id").ifBlank { m.optString("id") }
            if (id.isBlank()) return@mapNotNull null
            val type = m.optString("type")
            val tt = if (type == "series") TvType.TvSeries else TvType.Movie
            newMovieSearchResponse(m.optString("name"), "$type/$id", tt) { posterUrl = m.optString("poster").ifBlank { null } }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        resolveHost()
        val skip = if (page > 1) "/skip=${(page - 1) * 100}" else ""
        val metas = JSONObject(app.get("$catalogs/${request.data}$skip.json").text).optJSONArray("metas")
        return newHomePageResponse(request.name, metas.cards())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        resolveHost()
        val q = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        return listOf("series", "movie").flatMap {
            runCatching { JSONObject(app.get("$catalogs/top/catalog/$it/top/search=$q.json").text).optJSONArray("metas").cards() }
                .getOrDefault(emptyList())
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        resolveHost()
        val type = url.substringBefore('/')
        val id = url.substringAfter('/')
        val m = JSONObject(app.get("$mainUrl/meta/$type/$id.json").text).optJSONObject("meta") ?: return null
        val title = m.optString("name")
        val year = m.optString("year").take(4)
        val tmdb = m.optInt("moviedb_id").toString()
        val poster = m.optString("poster").ifBlank { null }
        val bg = m.optString("background").ifBlank { null }
        val desc = m.optString("description").ifBlank { null }
        val tags = m.optJSONArray("genres")?.let { g -> (0 until g.length()).map { g.getString(it) } }
        fun payload(s: Int, e: Int) = JSONObject().put("tmdb", tmdb).put("imdb", id).put("title", title)
            .put("year", year).put("type", type).put("season", s).put("episode", e).toString()
        if (type != "series") {
            return newMovieLoadResponse(title, url, TvType.Movie, payload(0, 0)) {
                posterUrl = poster; backgroundPosterUrl = bg; plot = desc; this.tags = tags; this.year = year.toIntOrNull()
            }
        }
        val now = System.currentTimeMillis()
        val vids = m.optJSONArray("videos") ?: JSONArray()
        val eps = (0 until vids.length()).map { vids.getJSONObject(it) }.mapNotNull { v ->
            val s = v.optInt("season"); val e = v.optInt("episode", v.optInt("number"))
            // skip specials and unaired episodes (same as the TS)
            if (s <= 0 || e <= 0 || v.optString("released").let { it.length >= 10 && it > java.time.Instant.ofEpochMilli(now).toString() }) return@mapNotNull null
            newEpisode(payload(s, e)) {
                season = s; episode = e; name = v.optString("name").ifBlank { v.optString("title") }.ifBlank { null }
                posterUrl = v.optString("thumbnail").ifBlank { null }; description = v.optString("overview").ifBlank { v.optString("description") }.ifBlank { null }
            }
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
            posterUrl = poster; backgroundPosterUrl = bg; plot = desc; this.tags = tags; this.year = year.toIntOrNull()
        }
    }

    // ---- movy (api.wecollege.net): GET /seed, then /<server>/sources returns a payload XOR'd with a seed-derived keystream ----
    private val api = "https://api.wecollege.net"
    private val origin = "https://www.movy.sx"
    private val hdr = mapOf("User-Agent" to UA, "Origin" to origin, "Referer" to "$origin/")
    private val servers = mapOf(
        "miami" to "Miami", "boise" to "Boise", "atlanta" to "Atlanta", "austin" to "Austin",
        "delhi" to "Delhi (Hindi)", "phoenix" to "Phoenix", "dallas" to "Dallas", "vegas" to "Vegas",
    )

    private fun scr(x0: Int): Int {
        var x = x0
        x = x xor (x ushr 16); x *= -0x7a143595
        x = x xor (x ushr 13); x *= -0x3d4d51cb
        return x xor (x ushr 16)
    }

    private fun decode(cipher: String, seed: String, mediaId: Int): String? {
        val raw = Base64.decode(cipher.trim(), Base64.URL_SAFE or Base64.NO_WRAP)
        val s = IntArray(61); val has = BooleanArray(61)
        var h = -0x7ee3623b
        for (c in seed) h = (h xor c.code) * 0x1000193
        var n = scr(scr(h) xor scr(mediaId xor -0x61c88647))
        for (e in 0 until 8) {
            val a = Integer.remainderUnsigned(n, 61)
            n = Integer.rotateRight(n + -0x61c88647, 7 + (7 and e))
            s[a] = n xor scr(n); has[a] = true
            n = scr(n + a)
        }
        var acc = scr(-0x5a5a5a5b xor n)
        val out = ByteArray(raw.size)
        var l = 0; var i = 0
        while (i < raw.size) {
            val d = Integer.remainderUnsigned(acc, 61)
            val r = if (has[d]) s[d] else 0
            val c = -0x61c88647 * (l + 1)
            var b = (acc xor (r xor c)) or (acc and (r xor c) and (if (has[d]) -1 else 0))
            b = Integer.rotateRight(b + acc, d and 31) xor Integer.rotateRight(acc, (d * 7) and 31)
            acc = scr(b + -0x61c88647)
            s[d] = acc; has[d] = true; l++
            for (k in 0 until 4) if (i < raw.size) { out[i] = (raw[i].toInt() xor (acc ushr (8 * k))).toByte(); i++ }
        }
        if (!(out.size > 4 && out[0] == 'm'.code.toByte() && out[1] == 'v'.code.toByte() && out[2] == 'm'.code.toByte() && out[3] == '1'.code.toByte())) return null
        return String(out, 4, out.size - 4, Charsets.UTF_8)
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val p = JSONObject(data)
        val tmdb = p.optString("tmdb")
        if (tmdb.isBlank() || tmdb == "0") return false
        val seed = runCatching { JSONObject(app.get("$api/seed?mediaId=$tmdb", headers = hdr).text).getString("seed") }.getOrNull() ?: return false
        val isMovie = p.optString("type") != "series"
        val title = URLEncoder.encode(URLEncoder.encode(p.optString("title"), "UTF-8").replace("+", "%20"), "UTF-8")
        val q = "title=$title&mediaType=${if (isMovie) "movie" else "tv"}&year=${p.optString("year")}&tmdbId=$tmdb&enc=2&seed=$seed&imdbId=${p.optString("imdb")}" +
            if (isMovie) "" else "&seasonId=${p.optInt("season")}&episodeId=${p.optInt("episode")}"
        var any = false
        coroutineScope {
            servers.map { (id, label) ->
                async {
                    runCatching {
                        val j = JSONObject(decode(app.get("$api/$id/sources?$q", headers = hdr).text, seed, tmdb.toInt()) ?: return@runCatching)
                        j.optJSONArray("subtitles")?.let { subs ->
                            for (i in 0 until subs.length()) {
                                val s = subs.getJSONObject(i)
                                val u = s.optString("url").ifBlank { s.optString("file") }
                                if (u.isNotBlank()) subtitleCallback(SubtitleFile(s.optString("language").ifBlank { s.optString("label") }.ifBlank { "en" }, u))
                            }
                        }
                        // master playlist when present (player picks quality), else the individual sources
                        val urls = j.optString("playlist").takeIf { it.isNotBlank() }?.let { listOf(it to "") }
                            ?: j.optJSONArray("sources")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { it.optString("url") to it.optString("quality") } }
                            .orEmpty()
                        for ((u, ql) in urls) {
                            if (u.isBlank()) continue
                            val type = if (u.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            callback(newExtractorLink("Movy", "Movy - $label" + if (ql.isNotBlank()) " ($ql)" else "", u, type) {
                                quality = getQualityFromName(ql); referer = "$origin/"; headers = hdr
                            })
                            any = true
                        }
                    }
                }
            }.awaitAll()
        }
        return any
    }
}
