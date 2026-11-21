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
// VyseTV plugin ported from the CloudStream provider "Zeefliz" (zeefliz1.xyz + nexdrive.store link pages).
// Chain: search -> post -> nexdrive page -> zee-dl "G-Direct" (googleusercontent mkv) / HubCloud.
// Skipped (not resolvable from a script): zcloud.lat (Cloudflare challenge), vegadrive, filebee.
var HOSTS = ['https://zeefliz1.xyz'];
var MAXN = 4;

function parseSearch(html) {
  var out = [], re = /<h3 class="entry-title"><a href="([^"]+)"[^>]*>([^<]*)/g, m;
  while ((m = re.exec(html))) out.push({ href: m[1], title: unent(m[2]).replace(/^Download\s*/i, '') });
  return out;
}
// (heading, nexdrive url) pairs in page order
function blocks(html) {
  var out = [], heading = '', re = /<(h[345])[^>]*>([\s\S]*?)<\/\1>|<a [^>]*href="([^"]*nexdrive[^"]*)"[^>]*>([\s\S]*?)<\/a>/g, m;
  while ((m = re.exec(html))) {
    if (m[3]) out.push({ heading: heading, url: m[3], text: unent(m[4]) });
    else heading = unent(m[2]);
  }
  return out;
}
async function resolveNex(url, label, out) {
  var page = await getText(url);
  var m, re = /href="(https?:[^"]+)"/g, seen = {}, jobs = [];
  while ((m = re.exec(page))) {
    var l = unent(m[1]);
    if (seen[l]) continue;
    seen[l] = 1;
    if (/zee-dl/.test(l)) jobs.push(zeedl(l, label, out));
    else if (/hubcloud|vcloud/.test(l)) jobs.push(hubcloud(l, label, out, 'Zeefliz'));
  }
  await Promise.all(jobs);
}
async function zeedl(link, label, out) {
  var t = await getText(link.replace('lol//embed', 'lol/embed'));
  var m = /var reurl\s*=\s*"[^"]*?link=([^"]+)"/.exec(t);
  if (!m) return;
  var u = m[1];
  if (!/^https?:/.test(u) || isZip(u)) return;
  out.push({ url: u, type: 'mp4', quality: qualityOf(label), server: 'Zeefliz G-Direct', label: label.slice(0, 80), audio: audioOf(label) || undefined });
}

async function getStreams(q) {
  HOSTS = await siteDomains('zeefliz', HOSTS); // domains.json first (the repository's), then this list
  // each search string in turn (the first is as fast as before); later ones only when nothing matched
  var picks = [], qs = searchQueries(q), t0 = Date.now(), imdbTried = false;
  // the site's search for the IMDb id; the post's page must name it (sure, so it goes after the first two strings)
  async function tryImdb() {
    var byId = await searchByImdb(q, async function (text) {
      var res = await firstOk(HOSTS, '/?s=' + encodeURIComponent(text), null, function (t) { return /entry-title/.test(t); });
      return res ? parseSearch(res.text).filter(function (p) { return q.isTv || !/season/i.test(p.title); }) : [];
    }, function (p) { return getText(p.href); });
    if (byId) {
      var ib = blocks(byId.page);
      if (q.isTv) {
        var sre2 = new RegExp('(?:season\\s*0?' + (q.season || 1) + '|\\bS0?' + (q.season || 1) + ')\\b(?!\\s*[-–]\\s*\\d)', 'i');
        ib = ib.filter(function (b) { return sre2.test(b.heading) && /single episode/i.test(b.heading); });
      }
      ib.forEach(function (b) { picks.push(b); });
    }
  }
  for (var k = 0; k < qs.length && !picks.length; k++) {
    if (k === 2) { imdbTried = true; await tryImdb(); if (picks.length) break; }
    if (Date.now() - t0 > 12000) break; // the later strings are long shots: stay inside the time limit
    var res = await firstOk(HOSTS, '/?s=' + encodeURIComponent(qs[k]), null, function (t) { return /entry-title/.test(t); });
    if (!res) continue;
    var found = parseSearch(res.text).filter(function (p) { return q.isTv || !/season/i.test(p.title); });
    var posts = rankMatches(found, q, function (p) { return p.title.replace(/^\[18\+\]\s*/, ''); }).slice(0, 4);
    for (var i = 0; i < posts.length && !picks.length; i++) {
      var html = await getText(posts[i].href);
      if (imdbMatches(html, q) === false) continue;
      var bl = blocks(html);
      if (q.isTv) {
        var sre = new RegExp('(?:season\\s*0?' + (q.season || 1) + '|\\bS0?' + (q.season || 1) + ')\\b(?!\\s*[-–]\\s*\\d)', 'i');
        bl = bl.filter(function (b) { return sre.test(b.heading) && /single episode/i.test(b.heading); });
      }
      bl.forEach(function (b) { picks.push(b); });
    }
  }
  if (!picks.length && !imdbTried) await tryImdb();
  // mid qualities first (720p/1080p), small first
  picks.sort(function (a, b) {
    var qa = +(/(\d{3,4})p/.exec(a.heading + a.text) || [0, 0])[1], qb = +(/(\d{3,4})p/.exec(b.heading + b.text) || [0, 0])[1];
    var ra = qa >= 720 && qa <= 1080 ? 0 : 1, rb = qb >= 720 && qb <= 1080 ? 0 : 1;
    return ra - rb || sizeMB(a.heading) - sizeMB(b.heading);
  });
  picks = picks.slice(0, MAXN);
  var out = [];
  await Promise.all(picks.map(async function (p) {
    var label = (q.isTv ? p.text + ' ' : '') + p.heading;
    if (!q.isTv) return resolveNex(p.url, label, out);
    // episode page: "Episodes: N" heading followed by the host links
    var page = await getText(p.url), re = /<h4[^>]*>([\s\S]*?)<\/h4>|<a [^>]*href="(https?:[^"]+)"/g, m, cur = 0, jobs = [];
    while ((m = re.exec(page))) {
      if (m[1] !== undefined) { var e = /Episodes?:\s*(\d+)/i.exec(unent(m[1])); cur = e ? +e[1] : 0; continue; }
      if (cur !== (q.episode || 1)) continue;
      var l = unent(m[2]);
      if (/zee-dl/.test(l)) jobs.push(zeedl(l, label, out));
      else if (/hubcloud|vcloud/.test(l)) jobs.push(hubcloud(l, label, out, 'Zeefliz'));
    }
    await Promise.all(jobs);
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
