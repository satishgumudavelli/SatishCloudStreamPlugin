# Prompt: write a VyseTV scraper plugin

Copy everything below the line into an AI coding assistant that can run shell commands
(Claude Code, for example), fill in the two `{{…}}` placeholders, and let it work in this repository.
The assistant needs network access and Node 20+. The longer reference is [GUIDE.md](GUIDE.md).

---

You are writing ONE scraper plugin for VyseTV, a media app. A plugin is a single JavaScript file that
finds playable streams for a film or a TV episode on a website. Work in this repository; the plugin
goes in `plugins/`. Do not touch other plugins and do not run git.

**Site to port:** {{SITE_URL}}
**What I know about it (optional: search page, API, a CloudStream/Kotlin provider to port, notes):** {{NOTES}}

## The contract

Define a global `async function getStreams(query)`.

`query` = `{ tmdbId, imdbId (may be null), type: 'movie'|'tv', isTv, title, originalTitle, titles (the title, original title and TMDB's aliases), isAnime, year, season, episode }`
(season and episode are numbers for `tv`, otherwise null).

Return an array (empty `[]` when the title is not on the site; never throw for "not found") of
`{ url, type, quality, server, label?, audio?, headers? }`:

* `url`: a **direct stream URL** (http/https): an HLS playlist, a DASH manifest or a video file.
  Never a web page, embed page or archive.
* `type`: `'hls'`, `'dash'` or `'mp4'` (also for mkv/webm files). Set it correctly.
* `quality` (`'1080p'`) and `server` (`'SiteName Fast'`): text shown in the list.
* `audio`: the dubbed language, when the site says (`'Hindi'`).
* `headers`: what the CDN needs (`Referer`, `Origin`, `User-Agent`); used for the check and playback.

Globals available: `http(url, {method, headers, body, timeout})` →
`Promise<{status, url, headers (lower-case), text}>` which **never throws** (network error →
`status: 0`, `error`), plus `JSON`, `RegExp`, `Promise`, `Math`, `Date`, `setTimeout`,
`encodeURIComponent`/`decodeURIComponent`, `btoa`/`atob`, `console.log`, and the **title helpers** from `tools/helpers.js` (read that file first): `searchQueries`, `searchBest`, `bestMatch`, `rankMatches`, `parseTitle`, `titleScore`, `imdbMatches`.

**NOT available:** a browser DOM or `DOMParser` (use `parseHtml(html).select('css')` from `tools/helpers.js`: a Jsoup/BeautifulSoup-style tree with `select`, `selectFirst`, `attr`, `text`, `data`), `fetch`, `XMLHttpRequest`, `require`/`import`, Node APIs,
`URL`/`URLSearchParams`, `TextEncoder`, `Buffer`, `crypto`, WebAssembly. Parse HTML with `parseHtml` (or a regex for one small thing). If the
site needs AES/SHA/HMAC, implement it in plain JS inside the file.

Rules:

1. Standalone file, plain ES2020 (`var`/`function`/`async` are all fine), no imports.
2. **Fast**: finish in under 10 seconds. Run independent requests with `Promise.all`. The engine is an
   interpreter (about 20–50× slower than Node, slower still on TVs): avoid heavy loops; if you must
   do heavy work, stop starting new work after a time budget.
3. `http()` can't reach localhost or the local network, can't pass Cloudflare "Just a moment…"
   challenges or captchas, and doesn't keep cookies (send them in `headers`).
4. Put the most reliable and best links **first**: only the first ~3 links of a source are tried in auto-play.
5. Drop expired signed URLs. Never hard-code tokens or secrets. Don't invent links: a host you can't
   resolve is skipped.
6. If the site has no TMDB-id lookup, search by title **with the helpers, not your own matching**: `searchBest(query, search, get)` tries each string from `searchQueries(query)` until a listing matches (aliases, season variants, year check, right season are handled). When a page shows an IMDb link, also check `imdbMatches(pageText, query)` (`false` means a different film).
7. Start the file with a comment: the site, and the chain you followed (search → page → player → API).

## How to work

1. Explore the site with small Node scripts (Node has `fetch`): search → title page → player/embed → the
   request that returns the stream. Print `status` and the first 300 characters of each response.
2. Write `plugins/<id>.js` (a short lower-case id).
3. Test with the harness, which runs the plugin with the same rules as the app and **probes every
   stream** (HLS playlist and first piece, DASH manifest, file's first bytes):

   ```
   node tools/run-plugin.mjs plugins/<id>.js movie "Oppenheimer" 2023 872585 tt15398776
   node tools/run-plugin.mjs plugins/<id>.js movie "The Matrix" 1999 603 tt0133093
   node tools/run-plugin.mjs plugins/<id>.js tv "Breaking Bad" 2008 1396 tt0903747 1 1
   ```

   Arguments: `type title year tmdbId imdbId [season episode]`. If a title isn't on the site, try
   others you can see on it (find them with Node).
4. Done means: the right title is found, most streams show `[OK …]`, it runs in well under 20 s, and an
   unknown title returns `0 stream(s)` without error. Fix `FAIL`s: a `403`/`429` usually means missing
   headers; "not a playlist" means a page or an expired link.
5. Add it to `repo.json`:
   `{ "id": "<id>", "name": "<Site>", "version": "1", "note": "<one line>", "types": ["movie","tv"], "url": "plugins/<id>.js" }`
   (`types` is `["movie"]` for a film-only site).

## If it can't be done

Say so plainly and write no file. Typical reasons: a browser challenge (Cloudflare), a captcha, a
player that needs a real browser or WebAssembly, DRM, or hosts that are all dead. Do not fake a result.

## Final report (under 200 words)

The file path, the exact harness commands you ran with their real output lines (stream count and
`N/M stream(s) play`), what works (movies, shows, which hosts), and known limits.
