// ---- shared helpers (inlined per plugin) ----
function unent(s) {
  return String(s || '').replace(/&amp;/g, '&').replace(/&#0?39;|&#8217;|&rsquo;/g, "'").replace(/&#8211;|&ndash;/g, '-')
    .replace(/&#038;/g, '&').replace(/&quot;/g, '"').replace(/&nbsp;/g, ' ').replace(/<[^>]+>/g, '').replace(/\s+/g, ' ').trim();
}
function qualityOf(s) {
  var m = /(\d{3,4})p/i.exec(s || '');
  return m ? m[1] + 'p' : (/4k|uhd/i.test(s || '') ? '2160p' : '');
}
function audioOf(name) {
  var langs = ['Hindi', 'English', 'Tamil', 'Telugu', 'Malayalam', 'Kannada', 'Bengali', 'Punjabi', 'Marathi', 'Japanese', 'Korean'];
  var f = langs.filter(function (l) { return new RegExp('\\b' + l, 'i').test(name || ''); });
  return f.join('+') || (/\bdual\b/i.test(name) ? 'Dual' : /\bmulti\b/i.test(name) ? 'Multi' : '');
}
function sizeMB(s) {
  var m = /([\d.]+)\s*(GB|MB)/i.exec(s || '');
  return m ? (/GB/i.test(m[2]) ? m[1] * 1024 : +m[1]) : 1e9;
}
function absUrl(base, h) {
  if (/^https?:/i.test(h)) return h;
  var o = /^(https?:\/\/[^\/]+)/.exec(base)[1];
  return h.charAt(0) === '/' ? o + h : base.replace(/[^\/]*$/, '') + h;
}
function hostOf(u) { return (/^https?:\/\/([^\/?#]+)/i.exec(u) || [])[1] || ''; }
function b64d(x) { return atob(x.replace(/-/g, '+').replace(/_/g, '/')); }
function rot13(s) { return s.replace(/[a-zA-Z]/g, function (c) { var b = c <= 'Z' ? 65 : 97; return String.fromCharCode((c.charCodeAt(0) - b + 13) % 26 + b); }); }
async function getText(url, headers, timeout) {
  var r = await http(url, { headers: headers || {}, timeout: timeout || 15000 });
  return r.status >= 200 && r.status < 400 ? r.text : '';
}
async function firstOk(hosts, path, headers, test) {
  for (var i = 0; i < hosts.length; i++) {
    var r = await http(hosts[i] + path, { headers: headers || {}, timeout: 12000 });
    if (r.status === 200 && (!test || test(r.text))) return { base: hosts[i], text: r.text };
  }
  return null;
}
function dedupe(out) {
  var seen = {};
  return out.filter(function (s) { if (seen[s.url]) return false; seen[s.url] = 1; return true; });
}
function isZip(u) { var d = u; try { d = decodeURIComponent(u); } catch (e) {} return /\.zip/i.test(d); }

// greenmotors.* shortener -> real target
async function greenmotors(url) {
  var t = await getText(url);
  var m = /s\('o','([^']+)',180/.exec(t);
  if (!m) return null;
  try { return atob(JSON.parse(atob(rot13(atob(atob(m[1]))))).o); } catch (e) { return null; }
}

// HubCloud / vcloud drive page -> direct links (HubCloudExtractor.kt)
var HC_HOSTS = ['https://hubcloud.mov', 'https://hubcloud.pw', 'https://hubcloud.ist'];
async function hubcloud(url, label, out, tag) {
  var drive = null, base = hostOf(url), i;
  var tries = [url];
  HC_HOSTS.forEach(function (h) { tries.push(url.replace(/^https?:\/\/[^\/]+/, h)); });
  for (i = 0; i < tries.length && !drive; i++) {
    var r = await http(tries[i], { timeout: 12000 });
    if (r.status === 200 && /id="download"|hubcloud\.php|atob\(atob/.test(r.text)) drive = r.text;
  }
  if (!drive) return;
  var gen = (/id="download"[^>]*href="([^"#]+)"/.exec(drive) || /href="([^"]+)"[^>]*id="download"/.exec(drive) || [])[1]
    || (/href="(https?:[^"]*hubcloud\.php[^"]*)"/.exec(drive) || [])[1];
  if (!gen) { var a = /atob\(atob\(['"]([^'"]+)['"]\)\)/.exec(drive); if (a) try { gen = atob(atob(a[1])); } catch (e) {} }
  if (!gen) return;
  gen = unent(gen);
  var page = await getText(gen);
  if (!page) return;
  var header = unent((/<div class="card-header[^>]*>([\s\S]*?)<\/div>/.exec(page) || [])[1] || '');
  var size = unent((/id="size"[^>]*>([^<]*)/.exec(page) || [])[1] || '');
  var q = qualityOf(header) || qualityOf(label);
  var btns = [], re = /<a\s[^>]*>[\s\S]*?<\/a>/g, m;
  while ((m = re.exec(page))) {
    if (!/class="[^"]*\bbtn\b/.test(m[0])) continue;
    var h = /href="([^"]+)"/.exec(m[0]);
    if (!h) continue;
    var txt = unent(m[0]);
    if (/telegram|watch online|android app/i.test(txt) || !/^https?:/.test(h[1])) continue;
    btns.push({ href: unent(h[1]), txt: txt });
  }
  await Promise.all(btns.map(async function (b) {
    var href = b.href, final = null;
    if (isZip(href) || /r2\.cloudflarestorage\.com|fuckingfast\./.test(href)) return;
    var pd = /pixeldrain\.[a-z]+\/u\/([A-Za-z0-9]+)/.exec(href);
    if (pd) final = 'https://pixeldrain.com/api/file/' + pd[1] + '?download';
    else if (/10Gbps|pixel\./i.test(b.txt + href) && !/\.(mkv|mp4)(\?|$)/i.test(href)) {
      var r = await http(href, { timeout: 15000 });
      var u = r.url || '';
      var lm = /[?&]link=(.+)$/.exec(u);
      if (lm) { try { final = decodeURIComponent(lm[1]); } catch (e) { final = lm[1]; } }
      else if (/\.(mkv|mp4)(\?|$)/i.test(u)) final = u;
      var pd2 = /pixeldrain\.[a-z]+\/u\/([A-Za-z0-9]+)/.exec(u);
      if (pd2) final = 'https://pixeldrain.com/api/file/' + pd2[1] + '?download';
    } else final = href;
    if (!final || isZip(final)) return;
    out.push({ url: final, type: 'mp4', quality: q, server: (tag || 'HubCloud') + ' ' + b.txt.replace(/\[.*?\]/g, '').replace(/\s+/g, ' ').trim().slice(0, 30),
      label: (size || '') + (header ? ' ' + header.slice(0, 60) : ''), audio: audioOf(header + ' ' + label) || undefined });
  }));
}
// VyseTV plugin ported from the CloudStream provider "FourKHdHub" (4khdhub.one).
// Chain: search -> detail -> greenmotors short link -> HubCloud drive -> direct file links.
var HOSTS = ['https://4khdhub.one'];
var MAX = 6; // resolved files per request (script time limit is 20 s)

async function pickPage(q) {
  var base = HOSTS[0];
  var c = await searchBest(q, async function (s) {
    var res = await firstOk(HOSTS, '/?s=' + encodeURIComponent(s), null, function (t) { return /movie-card/.test(t); });
    if (!res) return [];
    base = res.base;
    var cards = [], re = /<a href="([^"]+)" class="movie-card"[\s\S]*?<h3 class="movie-card-title">([^<]*)<\/h3>\s*<p class="movie-card-meta">([^<]*)/g, m;
    while ((m = re.exec(res.text))) cards.push({ href: m[1], title: unent(m[2]), meta: m[3], series: /-series-\d+/.test(m[1]) });
    return cards.filter(function (x) { return x.series === !!q.isTv; });
  }, function (x) { return { title: x.title, year: (/\b(?:19|20)\d{2}\b/.exec(x.meta) || [])[0] }; });
  return c ? absUrl(base + '/', c.href) : null;
}

function collectMovie(html) {
  var items = html.split('class="download-item').slice(1), out = [];
  items.forEach(function (it) {
    var name = unent((/class="flex-1[^>]*>([\s\S]*?)<br/.exec(it) || [])[1] || '');
    var size = (/>(\d[\d.]*\s*(?:GB|MB))</.exec(it) || [])[1] || '';
    var file = unent((/class="file-title">([\s\S]*?)<\/div>/.exec(it) || [])[1] || '');
    var re = /<a [^>]*href="([^"]+)"[^>]*class="btn[^"]*"[^>]*>([\s\S]*?)<\/a>/g, m;
    while ((m = re.exec(it)))
      if (/HubCloud/i.test(m[2])) out.push({ url: unent(m[1]), label: name + ' ' + file, size: size });
  });
  return out;
}
function collectTv(html, season, ep) {
  var out = [];
  html.split('class="season-item').slice(1).forEach(function (s) {
    var sn = +((/class="episode-number">\D*(\d+)/.exec(s) || [])[1] || 1);
    if (sn !== season) return;
    var qt = unent((/class="episode-title">([\s\S]*?)<\/h3>/.exec(s) || [])[1] || '');
    s.split('class="episode-download-item"').slice(1).forEach(function (e) {
      var n = +((/badge-psa">\D*(\d+)/.exec(e) || [])[1] || 0);
      if (n !== ep) return;
      var size = unent((/badge-size">([^<]*)/.exec(e) || [])[1] || '');
      var re = /<a [^>]*href="([^"]+)"[^>]*class="btn[^"]*"[^>]*>([\s\S]*?)<\/a>/g, m;
      while ((m = re.exec(e))) if (/HubCloud/i.test(m[2])) out.push({ url: unent(m[1]), label: qt, size: size });
    });
  });
  return out;
}

async function getStreams(query) {
  HOSTS = await siteDomains('4khdhub', HOSTS); // domains.json first (the repository's), then this list
  var page = await pickPage(query);
  if (!page) return [];
  var html = await getText(page);
  var files = query.isTv ? collectTv(html, query.season || 1, query.episode || 1) : collectMovie(html);
  // smaller files first, 4K REMUX packs are too big to start fast
  files.sort(function (a, b) { return sizeMB(a.size) - sizeMB(b.size); });
  files = files.slice(0, MAX);
  var out = [];
  await Promise.all(files.map(async function (f) {
    var real = /greenmotors\./.test(f.url) ? await greenmotors(f.url) : f.url;
    if (real && /hubcloud|vcloud/.test(real)) await hubcloud(real, f.label, out, '4KHDHub');
  }));
  return dedupe(out);
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
