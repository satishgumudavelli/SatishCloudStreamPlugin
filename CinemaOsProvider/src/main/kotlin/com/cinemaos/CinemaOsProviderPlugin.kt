package com.cinemaos

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class CinemaOsProviderPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(CinemaOsProvider())
    }
}
