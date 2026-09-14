package com.framemovie

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class FrameMovieProviderPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(FrameMovieProvider())
    }
}
