package com.pixelflix

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class PixelflixProviderPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(PixelflixProvider())
    }
}
