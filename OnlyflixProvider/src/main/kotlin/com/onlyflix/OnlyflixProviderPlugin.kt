package com.onlyflix

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class OnlyflixProviderPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(OnlyflixProvider())
    }
}
