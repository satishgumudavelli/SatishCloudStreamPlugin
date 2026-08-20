package com.vidbox

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class VidboxProviderPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(VidboxProvider())
    }
}
