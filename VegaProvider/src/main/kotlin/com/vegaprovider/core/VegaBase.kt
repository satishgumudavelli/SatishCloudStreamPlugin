package com.vegaprovider.core

import com.lagradost.cloudstream3.MainAPI

/** Base for every provider here: host comes from domains.json (key) with a hardcoded fallback. */
abstract class VegaBase(private val key: String, fallbackUrl: String) : MainAPI() {
    override var mainUrl = fallbackUrl
    override var name = key
    override val hasMainPage = true

    private val resolver by lazy {
        DomainResolver(DOMAINS_URL, key, fallbackUrl.removePrefix("https://").removePrefix("http://").trimEnd('/'))
    }

    /** Call at the top of every entry point; swaps mainUrl to the first reachable domain. */
    protected suspend fun resolveHost() {
        mainUrl = runCatching { resolver.resolveMainUrl() }.getOrDefault(mainUrl)
    }

    companion object {
        const val DOMAINS_URL =
            "https://raw.githubusercontent.com/satishgumudavelli/SatishCloudStreamPlugin/master/domains.json"
        const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
