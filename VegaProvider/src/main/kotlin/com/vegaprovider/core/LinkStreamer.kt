package com.vegaprovider.core

import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Resolves every source in parallel and emits each link as soon as it passes its own quick reachability check
 * (LinkVerifier), so a slow source only delays itself. If every link fails the check they are all emitted at the end,
 * so a false negative can never hide every source.
 */
object LinkStreamer {
    suspend fun stream(
        sources: List<String>,
        callback: (ExtractorLink) -> Unit,
        resolve: suspend (String, (ExtractorLink) -> Unit) -> Unit,
    ): Boolean {
        val found = java.util.Collections.synchronizedList(ArrayList<ExtractorLink>())
        val lock = Any()
        var emitted = false
        coroutineScope {
            val cb: (ExtractorLink) -> Unit = { l ->
                found.add(l)
                launch { if (LinkVerifier.isPlayable(l)) synchronized(lock) { emitted = true; callback(l) } }
            }
            sources.map { s -> async { runCatching { resolve(s, cb) } } }.awaitAll()
        }
        if (!emitted) found.toList().forEach(callback)
        return found.isNotEmpty()
    }
}
