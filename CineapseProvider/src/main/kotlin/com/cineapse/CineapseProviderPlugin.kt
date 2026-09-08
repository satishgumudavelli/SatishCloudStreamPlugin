package com.cineapse

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class CineapseProviderPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(CineapseProvider())
    }
}
