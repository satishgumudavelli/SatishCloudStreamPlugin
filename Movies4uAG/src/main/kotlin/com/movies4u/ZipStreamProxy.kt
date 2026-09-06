package com.movies4u

import fi.iki.elonen.NanoHTTPD
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.InflaterInputStream

data class ZipEntryRef(
    val zipUrl: String,
    val dataOffset: Long,
    val compSize: Long,
    val uncompSize: Long,
    val stored: Boolean, // true = zip method 0 (no compression), false = method 8 (deflate)
)

// A single embedded HTTP server (OS-assigned port) that turns a registered zip entry into its
// own playable url: GET it and this streams that one episode's bytes, range-fetching only its
// compressed span from the remote zip and inflating on the fly - the player never needs the rest
// of the archive downloaded. Range requests on the LOCAL url are honored by skipping (decompress
// + discard) up to the requested start, since a deflate stream can't be seeked into directly -
// correct but slow for a large forward seek.
object ZipStreamProxy {
    private val entries = ConcurrentHashMap<String, ZipEntryRef>()
    private val counter = AtomicInteger(0)

    private val server: NanoHTTPD by lazy {
        object : NanoHTTPD(0) {
            override fun serve(session: IHTTPSession): Response {
                val token = session.uri.trimStart('/')
                val entry = entries[token]
                    ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
                return runCatching { serveEntry(entry, session.headers["range"]) }
                    .getOrElse { newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", it.message) }
            }
        }.apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) }
    }

    fun register(ref: ZipEntryRef): String {
        val port = server.listeningPort
        val token = "e${counter.incrementAndGet()}"
        entries[token] = ref
        return "http://127.0.0.1:$port/$token"
    }

    fun stop() {
        runCatching { server.stop() }
    }

    private fun serveEntry(entry: ZipEntryRef, rangeHeader: String?): NanoHTTPD.Response {
        val startByte = rangeHeader?.removePrefix("bytes=")?.substringBefore("-")?.toLongOrNull() ?: 0L

        val conn = (URL(entry.zipUrl).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Range", "bytes=${entry.dataOffset}-${entry.dataOffset + entry.compSize - 1}")
            connectTimeout = 15000
            readTimeout = 20000
        }
        val rawStream = conn.inputStream
        val decompressed = if (entry.stored) rawStream else InflaterInputStream(rawStream, java.util.zip.Inflater(true), 65536)

        if (startByte > 0) decompressed.skip(startByte)
        val remaining = entry.uncompSize - startByte

        val response = NanoHTTPD.newFixedLengthResponse(
            if (startByte > 0) NanoHTTPD.Response.Status.PARTIAL_CONTENT else NanoHTTPD.Response.Status.OK,
            "video/x-matroska",
            decompressed,
            remaining
        )
        if (startByte > 0) {
            response.addHeader("Content-Range", "bytes $startByte-${entry.uncompSize - 1}/${entry.uncompSize}")
        }
        response.addHeader("Accept-Ranges", "bytes")
        return response
    }
}
