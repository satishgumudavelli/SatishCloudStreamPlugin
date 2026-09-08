package com.cineapse

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * Cineapse gates real playback behind a WASM proof-of-work challenge and an encrypted,
 * session-scoped token (specs/004-cineapse-provider/research.md Task 5/5b) - reimplementing
 * that algorithm was investigated live and ruled out (no simple nonce->jsResult relationship,
 * and the real WASM call couldn't be observed via script injection). A further live capture
 * (Task 5c) showed an ordinary browser-driven session completes the challenge and reaches a
 * real playable `.m3u8` without any special handling, so this lets a real WebView run the
 * site's own unmodified JS and captures the resulting media request - the same fallback pattern
 * `CinemaOsExtractor.invokeCinemaosWebview` already uses in this repo for a different site.
 *
 * The player also offers several named alternate "servers" (Lightning, Wither, ...) via an
 * in-page menu, but switching between them was confirmed live to produce no new network
 * request at all (the `<video>` stays on one `blob:` URL fed by hls.js) - so only the one
 * default server is actually a distinct fetchable source; the others are not exposed here.
 *
 * Episodes, in contrast, are NOT addressable via URL query params (`?season=&episode=` is
 * silently ignored - confirmed live) - the player always opens on S1E1 and episode switching
 * happens only through the in-page Season/Episode UI. Switching episodes was confirmed live to
 * trigger a genuinely new token exchange and a genuinely distinct `.m3u8`, so a non-default
 * episode is reached by driving that same UI via [WebViewResolver]'s `script` hook rather than
 * guessing a URL scheme that doesn't exist.
 */
object CineapseExtractor {

    suspend fun invoke(
        mediaType: String,
        tmdbId: Int,
        season: Int?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit,
    ) {
        val base = CineapseApi.resolveMainUrl()
        val path = if (mediaType == "tv") "tv/$tmdbId" else "movie/$tmdbId"
        val url = "$base/stream/$path?fs=1"

        val navigateScript = if (mediaType == "tv" && season != null && episode != null) {
            episodeNavigateScript(season, episode)
        } else null

        val mediaRes = runCatching {
            app.get(
                url,
                interceptor = WebViewResolver(
                    Regex("""https?://[^"'\s]+?\.m3u8(?:\?[^"'\s]*)?"""),
                    script = navigateScript,
                    useOkhttp = false,
                    timeout = 30_000L,
                )
            )
        }.getOrNull() ?: return

        val mediaUrl = mediaRes.url
        if (!mediaUrl.contains(".m3u8", ignoreCase = true)) return

        callback(
            newExtractorLink("Cineapse", "Cineapse", mediaUrl, ExtractorLinkType.M3U8) {
                this.referer = "$base/"
                this.headers = mapOf("Referer" to "$base/", "Origin" to base)
            }
        )
    }

    // Drives the player's own Season <select> and Episode-list UI (verified live: the native
    // <select> responds to a value change + dispatched "change" event; the episode rows are
    // plain unstyled divs with no stable class/role, matched here by their "{n}. " text prefix,
    // which is the one stable thing about them - not by title text, which we don't have).
    private fun episodeNavigateScript(season: Int, episode: Int): String = """
        (function() {
            var idoc = document.querySelector('iframe').contentDocument;
            function clickByPrefix(prefix) {
                var all = idoc.querySelectorAll('*');
                for (var i = 0; i < all.length; i++) {
                    var el = all[i];
                    if (el.children.length === 0 && el.textContent.trim().indexOf(prefix) === 0) {
                        var row = el.closest('div[class]') || el.parentElement;
                        row.click();
                        return true;
                    }
                }
                return false;
            }
            function openEpisodes() {
                var buttons = idoc.querySelectorAll('button');
                for (var i = 0; i < buttons.length; i++) {
                    if (buttons[i].textContent.trim() === 'Episodes') { buttons[i].click(); return; }
                }
            }
            function pickEpisode() {
                openEpisodes();
                setTimeout(function() { clickByPrefix('$episode. '); }, 800);
            }
            if ($season !== 1) {
                var combo = idoc.querySelector('select');
                if (combo) {
                    for (var i = 0; i < combo.options.length; i++) {
                        if (combo.options[i].textContent.trim() === 'Season $season') {
                            combo.selectedIndex = i;
                            combo.dispatchEvent(new Event('change', { bubbles: true }));
                            break;
                        }
                    }
                }
                setTimeout(pickEpisode, 1500);
            } else {
                pickEpisode();
            }
        })();
    """.trimIndent()
}
