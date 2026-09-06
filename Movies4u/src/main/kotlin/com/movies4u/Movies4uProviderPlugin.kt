package com.movies4u

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class Movies4uProviderPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(Movies4uProvider())
    }
}
