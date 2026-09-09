package com.cineapse

import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper

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
 * The player also offers several named alternate "servers" (Lightning, Wither, ...). Switching
 * to "Wither" specifically was confirmed live to produce no new network request - but a
 * captured request body (research.md Task 5d) shows "Wither" is explicitly locked
 * (`witherUnlocked: false`), which explains that result without it being a general property of
 * Servers. The other internal provider codenames (`svr_l1s2b3`, `svr_s0l1r2`, `svr_yoru`,
 * `svr_cinesrc`, `svr_saga`) were never individually tested, so only the one default-resolved
 * source is exposed here - exposing more is a follow-up, not something to guess at.
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

        // The player always opens on S1E1 by default - only script a navigation when a
        // different episode is actually requested, so the default case doesn't pay for an
        // extra (and unnecessary) episode-switch round-trip within the timeout budget.
        val navigateScript = if (mediaType == "tv" && season != null && episode != null &&
            (season != 1 || episode != 1)
        ) {
            episodeNavigateScript(season, episode)
        } else null

        // Called directly (per WebViewResolver's own doc comment: "When used as Interceptor
        // additionalUrls cannot be returned, use WebViewResolver(...).resolveUsingWebView(...)")
        // rather than via `app.get(url, interceptor = ...)` - that style makes OkHttp's
        // `chain.proceed()` actually re-fetch the matched URL for real just to read its `.url`
        // off the Response and discard the body, then generateM3u8 below fetches the same URL
        // again anyway to parse it. Calling resolveUsingWebView directly skips that wasted
        // duplicate fetch.
        val resolver = WebViewResolver(
            // Must NOT match any `/media/{opaque}` path - a live Playwright capture
            // (research.md Task 5j) proved individual HLS *segments* are served under
            // that exact same disguised shape (fake `.gif`/`.webp`/`.css`/`.jpg`/`.ico`
            // extensions - confirmed by fetching a real playlist body and seeing its own
            // #EXTINF segment URIs use them), and segment requests fire before the real
            // playlist in page load order. `.m3u8` (confirmed live, `content-type:
            // application/vnd.apple.mpegurl`) and `.txt` (research.md Task 5g's master
            // playlist) are the only extensions seen on an actual playlist across two
            // independent real sessions, so matching only those is what's safe.
            Regex("""https?://[^"'\s]+?\.(?:m3u8|txt)(?:\?[^"'\s]*)?"""),
            script = navigateScript,
            useOkhttp = false,
            // The real flow is: solve a WASM PoW challenge (difficulty 21, ~1M+ nonce
            // tries observed - research.md Task 5g) -> token -> a hidden player iframe
            // that itself retries its own postMessage handshake for up to 20s before
            // giving up (Task 5h) -> "get sources" -> media. Both stages are confirmed
            // by real source (Task 5i) to run unmodified inside this same WebView, so
            // matching WebViewResolver's own upstream DEFAULT_TIMEOUT (60s) - rather than
            // an arbitrary shorter value - is the justified margin for that full chain on
            // a real (slower-than-desktop) device.
            timeout = 60_000L,
        )
        val mediaRequest = runCatching {
            resolver.resolveUsingWebView(url).first
        }.getOrNull() ?: return

        val mediaUrl = mediaRequest.url.toString()
        if (!Regex("""\.(?:m3u8|txt)(?:\?|$)""").containsMatchIn(mediaUrl)) return

        // generateM3u8 fetches the body and validates it's a real Master/Media playlist via
        // HlsPlaylistParser before returning anything - a real check we didn't have before,
        // since the matched URL's extension alone doesn't prove the body is actual HLS (segments
        // under this same disguised /media/{opaque} path use fake image/style extensions too -
        // research.md Task 5j). `returnThis=true` (the default) keeps emitting the master itself
        // alongside any split variants, so #EXT-X-MEDIA audio groups declared only on the master
        // aren't lost the way CinemaOsExtractor's own comment warns a pure quality-split would.
        val links = runCatching {
            M3u8Helper.generateM3u8(
                "Cineapse",
                mediaUrl,
                referer = "$base/",
                headers = mapOf("Referer" to "$base/", "Origin" to base),
            )
        }.getOrNull() ?: return

        links.forEach(callback)
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
