package com.tmdb

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class TmdbProviderPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(TmdbProvider())
    }
}
