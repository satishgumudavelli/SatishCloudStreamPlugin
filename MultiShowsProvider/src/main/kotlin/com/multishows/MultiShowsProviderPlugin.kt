package com.multishows

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class MultiShowsProviderPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(MultiShowsProvider())
    }
}
