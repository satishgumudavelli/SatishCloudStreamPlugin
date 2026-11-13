package com.vegaprovider

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.vegaprovider.providers.AnimeWorld
import com.vegaprovider.providers.AutoEmbed
import com.vegaprovider.providers.DramaCool
import com.vegaprovider.providers.ExtraMovies
import com.vegaprovider.providers.ExtraMoviesSarl
import com.vegaprovider.providers.Filmyfly
import com.vegaprovider.providers.FourKHdHub
import com.vegaprovider.providers.GokuHD
import com.vegaprovider.providers.Hdhub4u
import com.vegaprovider.providers.KatMovieFix
import com.vegaprovider.providers.KickAssAnime
import com.vegaprovider.providers.KmMovies
import com.vegaprovider.providers.LuxMovies
import com.vegaprovider.providers.MovieBoxWeb
import com.vegaprovider.providers.MovieLinkDb
import com.vegaprovider.providers.Movies4uVega
import com.vegaprovider.providers.MoviesDrive
import com.vegaprovider.providers.MoviesMod
import com.vegaprovider.providers.Moviezwap
import com.vegaprovider.providers.MultiMovies
import com.vegaprovider.providers.MxPlayer
import com.vegaprovider.providers.SkyMovieHD
import com.vegaprovider.providers.Topmovies
import com.vegaprovider.providers.UhdMovies
import com.vegaprovider.providers.Vega
import com.vegaprovider.providers.Zeefliz

@CloudstreamPlugin
class VegaProviderPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(AnimeWorld())
        registerMainAPI(AutoEmbed())
        registerMainAPI(DramaCool())
        registerMainAPI(ExtraMovies())
        registerMainAPI(ExtraMoviesSarl())
        registerMainAPI(Filmyfly())
        registerMainAPI(FourKHdHub())
        registerMainAPI(GokuHD())
        registerMainAPI(Hdhub4u())
        registerMainAPI(KatMovieFix())
        registerMainAPI(KickAssAnime())
        registerMainAPI(KmMovies())
        registerMainAPI(LuxMovies())
        registerMainAPI(MovieBoxWeb())
        registerMainAPI(MovieLinkDb())
        registerMainAPI(Movies4uVega())
        registerMainAPI(MoviesDrive())
        registerMainAPI(MoviesMod())
        registerMainAPI(Moviezwap())
        registerMainAPI(MultiMovies())
        registerMainAPI(MxPlayer())
        registerMainAPI(SkyMovieHD())
        registerMainAPI(Topmovies())
        registerMainAPI(UhdMovies())
        registerMainAPI(Vega())
        registerMainAPI(Zeefliz())
    }
}
