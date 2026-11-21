# VyseTV scraper plugins

A plugin is a JavaScript file that finds the streams for a film or an episode. Plugins live in a
repository (a folder on GitHub, or any web server) described by a `repo.json`.

Add one in **Settings → Sources → Plugins** by pasting the link to its `repo.json`
(a `github.com/…/blob/…/repo.json` page link and a bare `github.com/user/repo` link also work;
the latter reads `repo.json` on the default branch). Every plugin it lists becomes a source in the
list above: reorder it, switch it off, and the player asks it like the built-in sources.
The ✓ **refresh** button on the repository downloads its scripts again.

## repo.json

```json
{
  "name": "My repository",
  "plugins": [
    { "id": "mysite", "name": "My Site", "version": "1", "note": "Films, 1080p",
      "types": ["movie", "tv"], "url": "mysite.js" }
  ]
}
```

* `id`: letters, digits, `_ . -` (kept stable: it names the source in your saved order).
* `url`: the script, absolute or relative to `repo.json`. At most 100 plugins, 512 KB each.
* `types`: `movie`, `tv` or both (a `movie`-only plugin is not asked for episodes).

## The script

Define `getStreams(query)` (it may be `async`). See `example/demo.js`.

```js
// query: { tmdbId, imdbId, type: 'movie'|'tv', isTv, title, originalTitle, year, season, episode }
async function getStreams(query) {
  const r = await http('https://site.example/api?q=' + encodeURIComponent(query.title));
  const data = JSON.parse(r.text);
  return [{ url: data.m3u8, type: 'hls', quality: '1080p', server: 'Site',
            headers: { Referer: 'https://site.example/' } }];
}
```

Each returned stream: `url` (http/https, required), `type` (`hls`, `dash` or `mp4`; guessed from the
link if left out), `quality`, `server`, `label`, `audio` (the language, e.g. `Hindi`), `headers`.
The player checks that a stream really serves video before it plays it.

Available to the script: `http(url, {method, headers, body, timeout})`, `JSON`, `RegExp`, `Promise`,
`setTimeout`, `btoa`/`atob`, `console.log`. There is no DOM: read pages with regular expressions
or `JSON.parse`.

## Safety

Scripts run in an isolated QuickJS engine with a time limit (20 seconds). They have no access to
files or other apps, and `http()` refuses this device's own network (localhost, 192.168.x.x,
10.x.x.x…), so a plugin cannot reach TorrServer, a router or other devices. Scripts are downloaded
only when you add or refresh a repository, never in the background. Still: a plugin decides which
streams you are shown, so only add repositories you trust. Plugins are stored on this device and
are not synced to your account.

## This repository

`repo.json` lists the plugins in `plugins/`, ported from the CloudStream providers in this repository
(MultiShows, Movies4u, Cineapse and some Vega sites are not here: their hosts are dead, behind a
browser challenge, or need a real browser).

Add it in VyseTV → Settings → Sources → Plugins:

    https://github.com/satishgumudavelli/SatishCloudStreamPlugin/blob/vysetv-js-plugins/vysetv/repo.json

Test a plugin from a terminal (probes every stream it returns):

    node vysetv/tools/run-plugin.mjs vysetv/plugins/almostott.js movie "Oppenheimer" 2023 872585 tt15398776
    node vysetv/tools/run-plugin.mjs vysetv/plugins/almostott.js tv "Breaking Bad" 2008 1396 tt0903747 1 1

Sites move and block often, so a plugin that works today may need updating: that is what the refresh
button on the repository is for.
