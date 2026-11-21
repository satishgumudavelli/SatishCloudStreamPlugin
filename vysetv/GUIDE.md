# Writing a scraper plugin for VyseTV

A plugin is **one JavaScript file** that finds playable streams for a film or an episode.
VyseTV downloads it from a repository (`repo.json`), runs it in a sandbox, and lists it as a
source next to the built-in ones (Settings → Sources). This guide is how to write one that works.
For a ready-made prompt to hand to an AI, see [PROMPT.md](PROMPT.md).

## 1. The contract

Define a global function `getStreams(query)`. It may be `async`.

```js
// query
{
  tmdbId: 872585, imdbId: 'tt15398776',   // imdbId can be null
  type: 'movie' | 'tv', isTv: false,
  title: 'Oppenheimer', originalTitle: 'Oppenheimer', year: 2023,
  titles: ['Oppenheimer', …aliases],      // every name TMDB knows it by (title, original, alternative titles)
  isAnime: false,
  season: null, episode: null             // numbers for 'tv'
}

// return an array (empty when the title isn't on the site)
[{
  url: 'https://…',            // required, http(s) only
  type: 'hls' | 'dash' | 'mp4',// 'mp4' also for mkv/webm files; guessed from the URL if left out
  quality: '1080p',            // text shown in the list
  server: 'MySite Fast',       // text shown in the list
  label: undefined,            // optional extra text
  audio: 'Hindi',              // optional: the language this link is dubbed in
  headers: { Referer: '…' }    // optional: sent with every request for this stream
}]
```

What the script can use:

| Global | What it is |
|---|---|
| `http(url, {method, headers, body, timeout})` | `Promise<{status, url, headers, text}>`. **Never throws**: on a network error `status` is `0` and `error` has the reason. Response `headers` are lower-case. `body` may be a string or an object (sent as JSON). Redirects are followed (at most 5). `timeout` in ms, capped at 20000. Default `User-Agent` is a desktop Chrome. |
| `JSON`, `RegExp`, `Promise`, `Math`, `Date`, `setTimeout` | the language |
| `btoa`, `atob` | base64 |
| `parseHtml(html)` and `unescapeHtml(s)` | an HTML tree you can `select('css')` (see *HTML helper*) |
| the **title helpers** (section 4) | `searchQueries`, `searchBest`, `bestMatch`, `rankMatches`, `parseTitle`, `titleScore`, `imdbMatches`, `normTitle` |
| `console.log` | prints to the app's log |

What it can **not** use: a browser DOM (`document`, `DOMParser`; use `parseHtml`, see *HTML helper* below), `fetch`, `XMLHttpRequest`, `require`/`import`,
Node APIs, `crypto`/`SubtleCrypto`, WebAssembly, cookies between calls (send them yourself in `headers`).
Read HTML with `parseHtml` (a small BeautifulSoup/Jsoup stand-in, below) or regular expressions, and JSON with `JSON.parse`.
If the site needs AES / SHA / HMAC, write it in plain JS inside the file (see *Crypto* below).

## 2. Limits you must design for

* **Time.** VyseTV gives a source 30 seconds. The script is stopped by the engine after 20 seconds of
  *computation*, and the whole call is dropped after 25–30 seconds. Aim for **under 10 seconds**. Run
  independent requests in parallel with `Promise.all`; never chain more round trips than needed.
* **Slow phones and TVs.** The engine is QuickJS (an interpreter, no JIT): CPU-heavy code runs
  roughly 20–50× slower than in Node, and a 32-bit Android TV is slower again. Pure-JS PBKDF2 with
  100 000 rounds took 0.2 s in Node and ~18 s in the app on a laptop. Avoid big loops, or stop
  starting new heavy work after a time budget (check `Date.now()`).
* **Only the web.** `http()` refuses `localhost`, `127.x`, `10.x`, `172.16–31.x`, `192.168.x`,
  `169.254.x` and `*.local`: a plugin cannot reach the user's own network.
* **No state.** Every call starts a fresh engine. Cache nothing.
* **The first few links count.** In auto-play VyseTV tries at most 3 links of a source, in your
  order. **Put the best, most reliable links first.**

## 3. What VyseTV does with your streams

Before playing a link it checks that it really serves video; a link that fails is skipped:

* `hls`: the playlist is fetched (must contain `#EXTM3U`), then its first variant, then its first
  media piece must answer.
* `dash`: the URL must answer with a real manifest (the body contains `<MPD`). A relay's error page
  is rejected.
* `mp4` (any file): a ranged `GET` of the first bytes must return `200`/`206`.

Consequences:

* Return **direct stream URLs**: not a web page with a player, not an embed page, not a `.zip`.
* Set `type` correctly. A link labelled `mp4` that really serves HLS is fetched as a file and may be
  rejected or mis-played. If a host serves HLS from a URL without `.m3u8`, say `type: 'hls'`.
* If a CDN rejects the default user agent or a `Referer`, put what it needs in `headers`
  (`'User-Agent': 'ExoPlayer'` and no `Referer` is one real example). The same headers are used for
  the check and for playback.
* Short-lived signed URLs are fine, but **drop expired ones** (look at `X-Amz-Expires`, `exp=`, …).

## 4. How to build one, step by step

1. **Pick the site's real path to a stream.** Open the site in a browser with the network tab. Note:
   search request → title page → player/embed page → API that returns the stream. Many sites are
   keyed by TMDB id and need no search at all: `https://site/api/movie/{tmdbId}`.
2. **Reproduce it with `http()`**, one call per step, in a scratch script. Print `status` and the
   first 300 characters of `text` for each step: most bugs are a 403/404 or an HTML page where you
   expected JSON.
3. **Match titles with the helpers** (see *Title helpers* below): don't write your own matching. Search the
   site with each string from `searchQueries(query)` until a listing matches; the matcher compares names
   ignoring case and punctuation, checks a film's year, and for a show prefers the right season.
4. **Resolve the player** to a direct URL. Hosts you can't resolve (a browser-only challenge, a
   captcha, WebAssembly, a DRM stream) are **skipped honestly** — never invent a link.
5. **Return `[]`** when the title isn't there. Never throw for "not found".
6. **Test it** with the harness (next section), on a movie *and* an episode.

### HTML helper (BeautifulSoup / Jsoup in JavaScript)

There is no browser DOM, so `parseHtml` gives you a small tree with CSS selectors. Most CloudStream
providers read pages with Jsoup `select(...)`; the same selectors work here.

```js
var doc = parseHtml((await http(url)).text);       // parse once, select many times
doc.select('div.card a[href^=http]').forEach(function (a) {
  console.log(a.text(), a.attr('href'));
});
var title = doc.selectFirst('h1.entry-title');     // null when missing
var og = doc.selectFirst('meta[property=og:image]');
var script = doc.selectFirst('script').data();     // a <script>'s source (text() gives '')
```

| | |
|---|---|
| `doc.select(css)` / `doc.selectFirst(css)` | array of matches in page order / the first or `null`. Also on any node (searches inside it). |
| `node.attr(name)`, `node.hasAttr(name)` | attribute value (`''` when missing, entities decoded) |
| `node.text()` | the text inside, tags and scripts left out, entities decoded, whitespace collapsed |
| `node.data()`, `node.html()`, `node.outerHtml()` | raw text inside (script source) / inner markup / the element's markup |
| `node.tag`, `node.parent`, `node.children`, `node.next()`, `node.prev()` | navigation (elements only) |
| `unescapeHtml(s)` | `Tom &amp; Jerry` → `Tom & Jerry` |

Selectors: `tag`, `*`, `.class`, `#id`, `[attr]`, `[attr=v]`, `[attr^=v]`, `[attr$=v]`, `[attr*=v]`, `[attr~=v]`,
compound (`a.btn[href]`), descendant (`a b`), child (`a > b`), `a + b`, `a ~ b`, lists (`a, b`), `:contains(text)`
(case-insensitive, like Jsoup), `:first-child`, `:last-child`, `:nth-child(n|odd|even)`, `:not(compound)`.

It was checked against BeautifulSoup (`soup.select`) on real pages: the same elements, in the same order.
It reads untidy real-world HTML (unclosed `<li>`/`<p>`/`<td>`, unquoted attributes, `>` inside attribute
values, comments, scripts). Speed in the app: a 60–90 KB page parses in about 50–80 ms (a TV is several
times slower), a 1.5 MB page in about 1.5 s: **parse once** and keep the tree.

### Title helpers

Every plugin can call these (the app loads them before your script; the test harness loads the same
file, `tools/helpers.js`). They exist so that all plugins match titles the same, well-tested way.

| Function | What it does |
|---|---|
| `searchQueries(query)` | Strings to type into the site's search, best first: the title, the same without punctuation (`Dune: Part Two` → `dune part two`, many search boxes choke on colons), the original title and aliases; for a show past season 1 also `Title Season 2` and `Title S2`; last of all the title with its year and the title without its subtitle. |
| `searchByImdb(query, search, pageOf, accept)` | The last resort: ask the site's search for the IMDb id (many sites keep it in the post, so this finds the film whatever its title is called). A result counts only when its **page** names the same id (`pageOf(item)` returns the page text); a site that ignores the term and shows recent posts finds nothing. `accept(item)` (optional) skips listings that can't be the one, such as the wrong season. Returns `{item, page}` or `null`. Put it after the first two search strings, and keep a time budget (about 12 s) for the rest. |
| `searchBest(query, search, get)` | Tries each of those strings with `search(text)` (return an **array** of listings, `[]` for none) until one has a match; returns the best listing or `null`. |
| `bestMatch(items, query, get)` / `rankMatches(items, query, get)` | The best matching listing / all matches, best first. A film's year must be within 1 of the listing's year when both are known; a show is never rejected for its year; the right season ranks higher; an alias only counts when the listing's year fits (TMDB lists odd aliases). |
| `parseTitle(raw)` | `{title, year, season}` read out of a listing like `Oppenheimer (2023) BluRay 1080p` or `Breaking Bad Season 2 Complete`. |
| `titleScore(candidate, query)` | 0–100: 100 same name, 60 the listing continues the name, 40 the site dropped a subtitle. |
| `imdbMatches(pageText, query)` | `true` the page names this film's IMDb id (a sure match), `false` it names other ids but not this one (a different film: skip), `null` no id on the page. |

`get(item)` tells the helpers where a listing's title is: return the text, or `{title, year, season}`.

```js
var hit = await searchBest(q, async function (text) {
  var r = await http(BASE + '/search?q=' + encodeURIComponent(text));
  return parseListings(r.text);               // [{ name: 'Dune Part Two (2024) 1080p', url: '…' }, …]
}, function (l) { return l.name; });
if (!hit) return [];
var page = await http(hit.url);
if (imdbMatches(page.text, q) === false) return [];   // the site's page is another film
```

Plugins that use the helpers need a VyseTV build that has them (the same repository version).

### Skeleton

```js
// VyseTV plugin: MySite (https://mysite.example)
var BASE = 'https://mysite.example';
var H = { Referer: BASE + '/' };

async function getJson(url) {
  var r = await http(url, { headers: H, timeout: 12000 });
  if (r.status !== 200) return null;
  try { return JSON.parse(r.text); } catch (e) { return null; }
}

async function getStreams(q) {
  var path = q.isTv ? '/api/tv/' + q.tmdbId + '/' + q.season + '/' + q.episode : '/api/movie/' + q.tmdbId;
  var j = await getJson(BASE + path);
  if (!j || !j.sources) return [];
  return j.sources
    .filter(function (s) { return /^https:/.test(s.url); })
    .map(function (s) {
      return { url: s.url, type: /\.m3u8/.test(s.url) ? 'hls' : 'mp4',
               quality: s.quality || '', server: 'MySite', headers: H };
    });
}
```

### Crypto

There is no `crypto`. If a site encrypts its answer, port the algorithm to plain JS in the file
(small AES/SHA-256/HMAC/PBKDF2 are ~100 lines). Verify it against Node's `crypto` in a scratch test,
and watch the time budget (see Limits). AES-GCM tags need not be verified.

## 5. Testing from a terminal

`vysetv/tools/run-plugin.mjs` runs a plugin like the app does (same `http()` rules, including the
local-network block) and **probes every stream it returns** the way VyseTV does:

```
node vysetv/tools/run-plugin.mjs vysetv/plugins/mysite.js movie "Oppenheimer" 2023 872585 tt15398776
node vysetv/tools/run-plugin.mjs vysetv/plugins/mysite.js tv "Breaking Bad" 2008 1396 tt0903747 1 1
```

Output: how many streams and how long, then one `[OK …]` / `[FAIL …]` line per stream, then `N/M stream(s) play`.

Check:

* `N/M` is mostly `M/M`. A few `FAIL`s from a flaky CDN are normal; all of them is a bug.
* It finishes in well under 20 s.
* A title that isn't on the site gives `0 stream(s)` and no crash.
* Both a movie and an episode work (unless the site has only one).

The harness probes with a short `Mozilla/5.0` user agent; a stream that only plays with the
headers you return will probe as `FAIL` unless you put those headers in `headers`.

Test in the real engine too (the app's QuickJS is stricter than Node: no `fetch`, no DOM, ES2020):
add the repository in VyseTV → Settings → Sources → Plugins, play a title and pick your source.

## 6. Publishing

1. Put the file in `plugins/` (for example `plugins/mysite.js`).
2. Add it to `repo.json`:

```json
{ "id": "mysite", "name": "My Site", "version": "1", "note": "Films and shows, 1080p",
  "types": ["movie", "tv"], "url": "plugins/mysite.js" }
```

* `id`: letters, digits, `_ . -`; keep it stable (it names the source in the user's saved order).
* `types`: `["movie"]`, `["tv"]` or both. A movie-only plugin is never asked for episodes.
* Raise `version` when you change the script.

3. Commit and push. Scripts are never changed in the background by default: a user presses the **refresh**
   button on the repository, or switches on **Update automatically** (Settings → Sources → Plugins). With it
   on, VyseTV checks the repositories when it starts (at most once a day) and downloads **only the plugins
   whose `version` changed**, so **raise `version` whenever you change a script** (a plugin with no version is
   downloaded every time). A plugin that disappears from `repo.json` is removed, and a repository that can't be
   reached keeps what it has. Raw GitHub links can lag by a few minutes after a push.

## 7. Common problems

| Symptom | Cause |
|---|---|
| `0 stream(s)` everywhere | the site changed its API or moved domain; print each step's `status` and `text` |
| `status 0`, `error: this device's network is off limits` | the URL resolves to a private address (a LAN or loopback host) |
| `FAIL 403` / `FAIL 429` on a stream | the CDN wants other headers (`Referer`, `Origin`, `User-Agent`) — set `headers` |
| `FAIL not a playlist` | the URL is a web page, not an HLS playlist, or the link expired |
| Works in the harness, nothing in the app | a global that exists in Node but not in the engine (`fetch`, `URL`, `Buffer`, `TextDecoder`, `structuredClone`) |
| Slow in the app, fast in Node | CPU-heavy code (crypto, big regex over huge pages) — see Limits |
| Site shows a "Just a moment…" page | a Cloudflare browser challenge: `http()` can't pass it; skip that site |
| The video plays but there's no audio list | the link was not a real stream (an error page); check it with `ffprobe URL` |
| Links die after a minute | signed URLs: fetch them fresh inside `getStreams`, never hard-code them |

`URL`, `URLSearchParams`, `TextEncoder` and `Buffer` are **not** available; build query strings with
`encodeURIComponent` and split URLs with regular expressions.

## 8. Safety and good manners

* A plugin decides which streams the user is shown. Write only what you would be comfortable
  reading in a review: no hidden redirects, no tracking, no calls to anything but the site you port.
* Don't put secrets (accounts, API keys that belong to someone else) in a plugin: repositories are
  public and the script is copied to every user's device.
* Respect the sites: no loops that hammer an API, no more requests than needed.
