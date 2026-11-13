package com.vegaprovider.extractors

import org.json.JSONObject
import java.util.Base64

/**
 * greenmotors.club/.cc "Redirecting ..." shortener (used by hdhub4u + 4khdhub). The page inlines
 * s('o','<b64(b64(rot13(b64(json))))>',180) and json.o is b64 of the real target; no timer needed
 * for this hop (verified 2026-11-13 -> hubcloud.pw/drive/.. and hblinks.lol/archives/..).
 */
object GreenMotorsDecoder {
    fun isShort(url: String) = url.contains("greenmotors.")

    fun decode(html: String): String? = runCatching {
        val raw = Regex("""s\('o','([^']+)',180""").find(html)?.groupValues?.get(1) ?: return null
        val b = Base64.getDecoder()
        fun d(x: String) = String(b.decode(x))
        val rot = d(d(raw)).map { c ->
            when (c) {
                in 'a'..'z' -> 'a' + (c - 'a' + 13) % 26
                in 'A'..'Z' -> 'A' + (c - 'A' + 13) % 26
                else -> c
            }
        }.joinToString("")
        d(JSONObject(d(rot)).getString("o"))
    }.getOrNull()
}
