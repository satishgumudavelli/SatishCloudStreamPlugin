package com.almostott

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AlmostOttProviderPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(AlmostOttProvider())
    }
}
