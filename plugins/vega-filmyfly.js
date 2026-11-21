// VyseTV plugin ported from the CloudStream provider "Filmyfly (Vega)" (Filmyfly.kt).
// Chain: public JSON search API -> /movie/<id>/x.html -> linkmake.in/view/<id> -> filesdl .../cloud/<id> buttons ->
//   Cloud Direct (r2.dev) and PixelDrain (/api/file/<id>). The 10Gbps zdownload.php button (301 -> googleusercontent mkv)
//   is skipped: http() cannot read a redirect without downloading the file.
// Movies only: the site's series are whole-season packs, which cannot be matched to an episode.
// Skipped (as in the Kotlin): /drive/ pages (JS cookie challenge). HubCloud/GDFlix/gofile buttons are not resolved here.

var HOSTS = ['https://filmyfly.gift', 'https://filmyfly.farm', 'https://filmyfly.ong', 'https://filmyfly.net'];

function qualityOf(s) {
  var m = /(\d{3,4})p/i.exec(s || '');
  return m ? m[1] + 'p' : (/4k|uhd/i.test(s || '') ? '2160p' : '');
}

// Domains redirect to the live one (.farm -> .kred) and drop the path, so resolve the origin from "/" first.
// All domains are tried in parallel; the first that answers wins.
async function page(path) {
  var rs = await Promise.all(HOSTS.map(function (h) { return http(h + '/', { timeout: 8000 }); }));
  for (var i = 0; i < rs.length; i++) {
    if (rs[i].status !== 200) continue;
    var r = await http((/^https?:\/\/[^\/]+/.exec(rs[i].url) || [HOSTS[i]])[0] + path, { timeout: 12000 });
    if (r.status === 200 && r.text) return r;
  }
  return null;
}

async function getStreams(query) {
  HOSTS = await siteDomains('filmyfly', HOSTS); // domains.json first (the repository's), then this list
  if (query.isTv || !query.title) return [];
  var hit = await searchBest(query, async function (text) {
    var s = await http('https://webfind.filmyflydla.space/search?q=' + encodeURIComponent(text) + '&page=1&per_page=40', { timeout: 15000 });
    try { return JSON.parse(s.text).results || []; } catch (e) { return []; }
  }, function (r) { return r.title; });
  if (!hit) return [];

  var d = await page('/movie/' + hit.filmyfly + '/x.html');
  var lm = d && /class="[^"]*dlbtn[^"]*"[\s\S]{0,300}?href="([^"]+)"/.exec(d.text);
  if (!lm) return [];
  var l = await http(lm[1].replace(/&amp;/g, '&'), { timeout: 15000 });
  var out = [], seen = {};
  var btns = [];
  l.text.replace(/<a[^>]*href="([^"]+\/cloud\/[^"]+)"[^>]*>([\s\S]*?)<\/a>/g, function (_, href, inner) {
    btns.push({ href: href, label: inner.replace(/<[^>]+>/g, '').replace(/Download/i, '').trim() });
  });

  function add(url, type, label, server) {
    if (seen[url]) return;
    seen[url] = 1;
    var q = qualityOf(label);
    out.push({ url: url, type: type, quality: q, server: server, label: label, audio: 'Hindi+English' });
  }

  await Promise.all(btns.map(async function (b) {
    var p = await http(b.href, { timeout: 15000 });
    if (p.status !== 200) return;
    var html = p.text;
    var r2 = /<a[^>]*class=['"]button2['"][^>]*href=['"]([^'"]*r2\.dev[^'"]*)['"]/.exec(html) ||
      /<a[^>]*href=['"]([^'"]*r2\.dev[^'"]*)['"]/.exec(html);
    if (r2) add(r2[1].replace(/&amp;/g, '&'), 'mp4', b.label, 'Filmyfly FilesDL');
    var pd = /href=['"][^'"]*(?:pixeldrain|iwebp\.store)[^'"]*\/u\/([A-Za-z0-9]+)/.exec(html);
    if (pd) add('https://pixeldrain.com/api/file/' + pd[1], 'mp4', b.label, 'Filmyfly PixelDrain');
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
// The file is read once per run (5 s limit); when it can't be read the plugin's own list is used.

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
      var r = await http(url || DOMAINS_URL, { timeout: 5000 });
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
