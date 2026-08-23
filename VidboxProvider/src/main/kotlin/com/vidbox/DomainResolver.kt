package com.vidbox

import com.lagradost.cloudstream3.app
import org.json.JSONObject

/**
 * Resolves the first reachable domain for [targetName] out of a shared `domains.json`
 * (`{"domains": [{"name": ..., "domain": ...}, ...]}`), falling back to [fallbackDomain] if the
 * list can't be fetched or nothing in it responds. Resolved once per process and cached.
 *
 * Provider-agnostic and self-contained on purpose - this file is meant to be copied verbatim
 * into another provider's module (e.g. CinemaOsProvider) rather than shared via a Gradle module
 * dependency, since each provider here already compiles to its own independent .cs3 and none of
 * them depend on another provider's module.
 */
class DomainResolver(
    private val domainsJsonUrl: String,
    private val targetName: String,
    private val fallbackDomain: String,
    private val headers: Map<String, String> = emptyMap(),
) {
    @Volatile
    private var resolved: String? = null

    /** Every domain listed under [targetName] in domains.json, in listed order (first = preferred). */
    private suspend fun candidates(): List<String> {
        val json = runCatching { JSONObject(app.get(domainsJsonUrl).text) }.getOrNull() ?: return emptyList()
        val domains = json.optJSONArray("domains") ?: return emptyList()
        return (0 until domains.length()).mapNotNull { i ->
            val item = domains.optJSONObject(i) ?: return@mapNotNull null
            item.optString("domain").takeIf {
                it.isNotBlank() && item.optString("name").equals(targetName, ignoreCase = true)
            }
        }
    }

    private suspend fun isReachable(domain: String): Boolean =
        runCatching { app.get("https://$domain", headers = headers).isSuccessful }.getOrDefault(false)

    suspend fun resolveMainUrl(): String {
        resolved?.let { return it }
        val list = candidates().ifEmpty { listOf(fallbackDomain) }
        val chosen = list.firstOrNull { isReachable(it) } ?: list.first()
        return "https://$chosen".also { resolved = it }
    }
}
