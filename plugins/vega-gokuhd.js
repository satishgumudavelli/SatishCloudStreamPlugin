// VyseTV plugin ported from the CloudStream provider "GokuHD" (gokuhd.nl, Hindi-dubbed anime).
// Chain: filter the newest pages of /series/ and /movies/ by title (site search sits behind a Cloudflare
// challenge) -> post -> nexdrive page(s) -> fastdl.beer embed -> direct googleusercontent mp4.
// Skipped (honest): vcloud/vegadrive/filebee (filebee API is dead)/1fichier/megaup/vikingfile/gofile.
// Limit: only titles on the newest 6 pages of each list are findable.

let BASE = 'https://gokuhd.nl';
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';
const get = (url) => http(url, { headers: { 'User-Agent': UA, Referer: BASE + '/' }, timeout: 12000 });

const ent = (s) => String(s).replace(/&#8211;|&#8212;/g, '-').replace(/&#8217;|&#039;|&#8216;/g, "'").replace(/&#038;|&amp;/g, '&').replace(/&#\d+;/g, '');
const strip = (h) => ent(h.replace(/<[^>]*>/g, ' ')).replace(/\s+/g, ' ').trim();

async function listPosts(kind) {
  const pages = await Promise.all([1, 2, 3, 4, 5, 6].map((p) => get(BASE + '/' + kind + '/' + (p > 1 ? 'page/' + p + '/' : ''))));
  const out = [], re = /<h2 class="entry-title"[^>]*>\s*<a href="([^"]+)"[^>]*>([^<]*)/g;
  for (const r of pages) { let m; while ((m = re.exec(r.text))) out.push({ url: m[1], raw: ent(m[2]) }); }
  return out;
}

// Does a series post title ("Season 1 - 18", "Season 1 & 2", "Season 3") cover season n?
function coversSeason(raw, n) {
  const m = /seasons?\s*0?(\d+)(?:\s*(?:-|&|and|to)\s*0?(\d+))?/i.exec(raw);
  if (!m) return n === 1;
  const a = +m[1], b = m[2] ? +m[2] : a;
  return n >= Math.min(a, b) && n <= Math.max(a, b);
}

const fastdl = async (url) => {
  const r = await get(url);
  const reurl = (/var reurl\s*=\s*"([^"]+)"/.exec(r.text) || [])[1];
  if (!reurl) return null;
  const link = (/[?&]link=(.+)$/.exec(reurl) || [])[1];
  let u = reurl;
  if (link) { try { u = decodeURIComponent(link); } catch (e) { u = link; } }
  return /^https?:/.test(u) && !/\.zip(\?|$)/i.test(u) ? u : null;
};
const fastdlLinks = (html) => (html.match(/https?:\/\/fastdl\.[a-z]+\/embed\.php\?download=[\w-]+/g) || []);

const qual = (h) => { const m = /(2160|1080|720|480)p?/i.exec(h) || /(4k)/i.exec(h); return m ? (/4k/i.test(m[1]) ? '2160p' : m[1] + 'p') : 'auto'; };

async function getStreams(query) {
  // the site's domains: domains.json first, then this one; the first that answers is used
  for (const d of await siteDomains('gokuHD', [BASE])) {
    const r = await http(d + '/', { headers: { 'User-Agent': UA }, timeout: 6000 });
    if (r.status >= 200 && r.status < 400) { BASE = d; break; }
  }
  const isTv = !!query.isTv, season = query.season || 1, episode = query.episode || 1;
  // season ranges ("Season 1 - 18") are checked by coversSeason, so the season is left out of the title match
  const posts = rankMatches(await listPosts(isTv ? 'series' : 'movies'), query, (p) => { const i = parseTitle(ent(p.raw)); return { title: i.title, year: isTv ? null : i.year }; })
    .filter((p) => !isTv || coversSeason(p.raw, season));
  if (!posts.length) return [];
  const html = (await get(posts[0].url)).text;
  // quality heading (h4) -> its nexdrive buttons
  const groups = [];
  for (const chunk of html.split('<h4').slice(1)) {
    const head = strip(chunk.split('</h4>')[0]);
    const links = (chunk.split('</h4>').slice(1).join('').match(/https?:\/\/nexdrive\.[a-z]+\/[\w-]+\//g) || []);
    if (links.length) groups.push({ head, links });
  }
  const anySeasonHead = groups.some((g) => /season/i.test(g.head));
  const wanted = groups.filter((g) => !isTv || !anySeasonHead || new RegExp('season\\s*0?' + season + '\\b', 'i').test(g.head))
    .filter((g) => !/batch|zip/i.test(g.head)).slice(0, 4);
  const out = [], seen = {};
  const push = async (g, link) => {
    const u = await fastdl(link);
    if (!u || seen[u]) return;
    seen[u] = 1;
    out.push({ url: u, type: 'mp4', quality: qual(g.head), server: 'GokuHD', label: 'GokuHD ' + g.head.slice(0, 60), audio: 'Hindi' });
  };
  await Promise.all(wanted.map(async (g) => {
    for (const nd of g.links.slice(0, 2)) {
      const page = (await get(nd)).text;
      if (!isTv) { for (const l of fastdlLinks(page)) await push(g, l); continue; }
      // episodes: "Episode: N" headings (absolute numbering); take the n-th of this season group
      const eps = page.split('<h4').slice(1).map((c) => ({ n: +((/episodes?[:\s-]*(\d+)/i.exec(strip(c.split('</h4>')[0])) || [])[1]), links: fastdlLinks(c) }))
        .filter((e) => e.n && e.links.length).sort((a, b) => a.n - b.n);
      const hit = eps.find((e) => e.n === episode && !anySeasonHead && season === 1) || eps[episode - 1];
      if (hit) for (const l of hit.links) await push(g, l);
    }
  }));
  return out;
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
