// VyseTV plugin ported from the CloudStream PixelflixProvider.
// pixelflix.cc ids are TMDB ids, so no site search is needed: the Kotlin provider calls the
// embed backend embed.reelsdownload.online (native extract APIs) with tmdb/imdb ids.
// Not ported: the vidbolt.xyz scrapers (FastVa, Quasar, ... Ninetta) - that API now answers 403/404.

const BASE = 'https://embed.reelsdownload.online/api';
const UA = 'Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36';

// Endpoints and their query shapes, as in PixelflixExtractor.kt.
function endpoints(q) {
  const tv = q.type === 'tv' || q.isTv;
  const common = 'type=' + (tv ? 'tv' : 'movie') +
    (tv ? '&season=' + (q.season || 1) + '&episode=' + (q.episode || 1) : '');
  const ty = '&title=' + encodeURIComponent(q.title || '') + (q.year ? '&year=' + q.year : '');
  const ids = '&tmdb_id=' + q.tmdbId + (q.imdbId ? '&imdb_id=' + q.imdbId : '');
  return [
    ['Redflix', 'redflix-extract?' + common + '&tmdb_id=' + q.tmdbId],
    ['Cinemaos', 'cinemaos-extract?' + common + ids + ty],
    ['Moviebox', 'moviebox?' + common + ids + ty],
  ];
}

// Responses are either NDJSON ({"source":{..}} per line) or one JSON ({"sources":[..]}).
function parseSources(text) {
  const out = [];
  const lines = text.split('\n');
  for (const line of lines) {
    let j;
    try { j = JSON.parse(line); } catch (e) { continue; }
    if (j.source) out.push(j.source);
    if (j.sources) for (const s of j.sources) out.push(s);
  }
  return out;
}

async function getStreams(query) {
  if (!query.tmdbId) return [];
  const results = await Promise.all(endpoints(query).map(async ([name, path]) => {
    const r = await http(BASE + '/' + path, { timeout: 15000, headers: { 'User-Agent': UA } });
    if (r.status !== 200) return [];
    return parseSources(r.text)
      .filter((s) => s.url && s.verified !== false) // unverified mirrors are slow/dead
      .map((s) => {
        const url = s.url.charAt(0) === '/' ? 'https://embed.reelsdownload.online' + s.url : s.url;
        const t = String(s.type || '').toLowerCase();
        const label = s.label || '';
        const lang = /-\s*(.+)$/.exec(label);
        return {
          url,
          type: t === 'dash' || t === 'mpd' ? 'dash' : t === 'hls' || t === 'm3u8' ? 'hls' : 'mp4',
          quality: s.quality || '',
          server: 'Pixelflix ' + (s.scraperName || name),
          label,
          audio: lang ? lang[1] : undefined,
          headers: s.headers || undefined,
        };
      });
  }));
  const seen = {};
  return [].concat(...results).filter((s) => !seen[s.url] && (seen[s.url] = 1));
}

// ---- run guard (snippet: tools/run-guard.js) ----
//
// Paste this, unchanged, at the END of a plugin (after the domains snippet if it has one). tools/domains.test.mjs checks the copies.
//
// A plugin run has 20 s of script time and the app stops waiting at 25 s: a plugin still waiting for a slow host then loses everything
// it had found. This guard gives every run a time budget and a retry for flaky connections:
//   - each request's timeout is clipped to what is left of the budget (BUDGET_MS: 16 s unless the plugin sets its own earlier in the
//     file), and once the budget is used up requests fail at once (status 0, "out of time"), so the plugin finishes with what it has
//   - a GET whose connection was reset or refused is tried again, twice at most and only while 3 s are left: some networks reset
//     connections at random and a retry usually gets through. A request that got an answer, timed out, could not resolve its name,
//     or is not a GET is never retried (pass retry: false to switch it off for one request).
var DEADLINE = 0;
var BUDGET_MS = typeof BUDGET_MS === 'number' ? BUDGET_MS : 16000;
var _guardedHttp = http;
http = async function (url, opts) {
  opts = opts || {};
  var method = String(opts.method || 'GET').toUpperCase(), r;
  for (var attempt = 0; attempt < 3; attempt++) {
    var o = opts;
    if (DEADLINE) {
      var left = DEADLINE - Date.now();
      if (left < 400) return { status: 0, error: 'out of time', text: '', headers: {}, url: String(url) };
      o = Object.assign({}, opts, { timeout: Math.min(opts.timeout || 15000, left) });
    }
    r = await _guardedHttp(url, o);
    var why = String(r.error || '');
    if (r.status !== 0 || method !== 'GET' || opts.retry === false) return r;
    if (!/reset|ECONNRESET|Connection failed|connection closed|fetch failed|refused|EPIPE/i.test(why) || /ENOTFOUND|host lookup|timeout|out of time/i.test(why)) return r;
    if (DEADLINE && DEADLINE - Date.now() < 3000) return r;
  }
  return r;
};
var _guardedGetStreams = getStreams;
getStreams = async function (q) {
  DEADLINE = Date.now() + BUDGET_MS;
  return _guardedGetStreams(q);
};
