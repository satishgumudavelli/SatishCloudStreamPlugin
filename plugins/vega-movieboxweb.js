// VyseTV plugin ported from the CloudStream provider "MovieBoxWeb" (officialmoviebox.com).
// Chain: /newWeb/searchResult HTML -> /moviesDetail/<path> (Nuxt devalue payload: subject, dubs, seasons)
// -> /wefeed-h5api-bff/subject/play (mp4/hls/dash streams, one subject per dub language).

let DOMAINS = ['https://officialmoviebox.com'];
const UA = 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36';
const HDR = { 'User-Agent': UA, Accept: 'application/json', Cookie: 'token=', 'x-client-info': '{"timezone":"Asia/Colombo"}', 'x-source': '' };

const unent = (s) => String(s || '').replace(/&amp;/g, '&').replace(/&#0?39;|&apos;/g, "'");
const get = (url, headers) => http(url, { headers: Object.assign({}, HDR, headers || {}), timeout: 12000 });

// Nuxt 3 devalue payload: flat array, numbers are indices into it.
function hydrate(a, i, seen) {
  if (typeof i !== 'number' || i < 0) return null;
  if (i in seen) return seen[i];
  const v = a[i];
  if (Array.isArray(v)) {
    if (typeof v[0] === 'string') { const r = hydrate(a, v[1], seen); seen[i] = r; return r; }
    const l = []; seen[i] = l; for (const k of v) l.push(hydrate(a, k, seen)); return l;
  }
  if (v && typeof v === 'object') {
    const m = {}; seen[i] = m; for (const k in v) m[k] = hydrate(a, v[k], seen); return m;
  }
  return v;
}
function findDetail(x, depth) {
  if (!x || typeof x !== 'object' || depth > 12) return null;
  if (!Array.isArray(x) && x.subject && x.resource) return x;
  for (const k in x) { const r = findDetail(x[k], depth + 1); if (r) return r; }
  return null;
}
async function detail(base, path) {
  const r = await get(base + '/moviesDetail/' + path);
  const m = /id="__NUXT_DATA__"[^>]*>([\s\S]*?)<\/script>/.exec(r.text);
  if (!m) return null;
  try { return findDetail(hydrate(JSON.parse(m[1]), 0, {}), 0); } catch (e) { return null; }
}

async function search(base, title) {
  const r = await get(base + '/newWeb/searchResult?keyword=' + encodeURIComponent(title));
  const out = [], re = /<a href="\/moviesDetail\/([^"]+)"[^>]*class="[^"]*card[^"]*"[\s\S]*?<h2[^>]*?(?:title="([^"]*)")?[^>]*>([^<]*)<\/h2>/g;
  let m;
  while ((m = re.exec(r.text))) out.push({ path: m[1], title: unent(m[2] || m[3]).replace(/\s*\[.*?\]\s*/g, ' ').trim() });
  return out;
}

async function getStreams(query) {
  DOMAINS = await siteDomains('movieBoxWeb', DOMAINS); // domains.json first (the repository's), then this list
  const names = [query.title, query.originalTitle].filter(Boolean);
  for (const base of DOMAINS) {
    let det = null;
    const tried = {};
    for (const text of searchQueries(query)) {
      // series come as one card per season ("Breaking Bad S2"): rankMatches puts the requested season first
      const cards = rankMatches(await search(base, text), query, (c) => c.title).filter((c) => !tried[c.path]);
      for (const c of cards.slice(0, 4)) {
        tried[c.path] = 1;
        const d = await detail(base, c.path);
        if (!d) continue;
        const s = d.subject, isSeries = +s.subjectType === 2;
        const yr = parseInt(String(s.releaseDate || '').slice(0, 4), 10);
        if (isSeries !== !!query.isTv) continue;
        if (query.year && yr && Math.abs(yr - +query.year) > 1) continue;
        det = d; break;
      }
      if (det) break;
    }
    if (!det) continue;
    const s = det.subject;
    const dubs = (s.dubs || []).map((d) => ({ id: d.subjectId, path: d.detailPath, lang: d.lanName || 'Original' }));
    if (!dubs.length) dubs.push({ id: s.subjectId, path: s.detailPath, lang: 'Original' });
    const se = query.isTv ? (query.season || 1) : 0, ep = query.isTv ? (query.episode || 1) : 0;
    const out = [];
    await Promise.all(dubs.map(async (d) => {
      const ref = base + '/movies/' + d.path + '?id=' + d.id + '&type=%2Fmovie%2Fdetail&detailSe=' + (se || '') + '&detailEp=' + (se ? ep : '') + '&lang=en';
      const r = await get(base + '/wefeed-h5api-bff/subject/play?subjectId=' + d.id + '&detailPath=' + d.path + (se ? '&se=' + se + '&ep=' + ep : ''), { Referer: ref });
      let data; try { data = JSON.parse(r.text).data; } catch (e) { return; }
      if (!data) return;
      for (const key of ['streams', 'hls', 'dash']) {
        for (const o of data[key] || []) {
          const u = o.url;
          if (!u || o.vipLocked) continue;
          const fmt = String(o.format || '').toUpperCase(), bare = u.split('?')[0];
          const type = fmt === 'HLS' || /\.m3u8$/.test(bare) ? 'hls' : fmt === 'DASH' || /\.mpd$/.test(bare) ? 'dash' : 'mp4';
          const rs = String(o.resolutions || '');
          const h = Math.max.apply(null, rs.split(',').map((x) => parseInt(x, 10) || 0));
          out.push({ url: u, type, quality: h ? h + 'p' : 'auto', server: 'MovieBox', label: ('MovieBox ' + d.lang + ' ' + rs).trim(),
            audio: d.lang === 'Original' ? undefined : d.lang, headers: { Referer: base + '/', 'User-Agent': UA } });
        }
      }
    }));
    if (out.length) return out;
  }
  return [];
}

// ---- domains.json (snippet: tools/site-domains.js) ----
//
// Sites change domain often. The repository keeps one file, domains.json, that lists the current domains
// ({"domains": [{"name": "Moviesmod", "domain": "moviesmod.fi"}, ...]}, a name may appear several times, the first
// is preferred), so a rotation is fixed by editing that file: no plugin update. A plugin keeps its own list as
// the fallback:
//
//   var DOMAINS = ['https://moviesmod.fi'];
//   async function getStreams(q) {
//     DOMAINS = await siteDomains('Moviesmod', DOMAINS);   // listed ones first, then the plugin's own
//
// The file is read once per run (3 s limit); when it can't be read the plugin's own list is used.

var DOMAINS_URL = 'https://raw.githubusercontent.com/satishgumudavelli/SatishCloudStreamPlugin/refs/heads/master/domains.json';
var _domainsFile = null;

async function siteDomains(name, fallback, url) {
  var out = [], seen = {};
  function add(d) {
    d = String(d == null ? '' : d).replace(/^ +| +$/g, '').replace(/\/+$/g, '');
    if (!d) return;
    if (!/^https?:\/\//i.test(d)) d = 'https://' + d;
    var k = d.toLowerCase();
    if (seen[k]) return;
    seen[k] = 1;
    out.push(d);
  }
  try {
    if (!_domainsFile) {
      var r = await http(url || DOMAINS_URL, { timeout: 3000 });
      _domainsFile = r.status === 200 ? JSON.parse(r.text) : { domains: [] };
    }
    (_domainsFile.domains || []).forEach(function (x) {
      if (x && String(x.name).toLowerCase() === String(name).toLowerCase()) add(x.domain);
    });
  } catch (e) {
    _domainsFile = { domains: [] }; // not readable: ask once, then go on with the plugin's own
  }
  (fallback || []).forEach(add);
  return out;
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
