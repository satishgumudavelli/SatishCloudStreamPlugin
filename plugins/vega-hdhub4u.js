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
// VyseTV plugin ported from the CloudStream provider "Hdhub4u" (HDHub4u, domains.json name "hdhub").
// Chain: the site's search index (typesense on search.pingora.fyi: by IMDb id, then by title) -> the post page on the
// current domain -> its download links: greenmotors short link, hubdrive, hubcloud, hblinks, hubcdn, hubstream,
// vidara -> direct file / HLS links. Playdrive "WATCH" embeds need a WebView and are skipped.
//
// The site's domain changes all the time. The landing page https://hdhub4u.fr/ looks the current one up with a few
// endpoints (see discoverDomain): the plugin does the same first, then uses domains.json (name "hdhub"), then its own list.
//
// Checked against the live site when the connection got through: the post page was read (7 download links for Oppenheimer) and the
// chain gave 11 streams (6 of 8 played). From some networks the connection to the site is reset at random (see
// tools/diagnose-host.mjs), so the page fetch is retried a few times. The page reading follows the Kotlin provider's selectors;
// the logic also has tests against a made-up site (tools/hdhub.test.mjs).
var HOSTS = ['https://new15.hdhub4u.cl'];
var LANDING = 'https://hdhub4u.fr';
var MAX = 8; // resolved links per request (script time limit is 20 s)
var UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36';
var HDH = { Cookie: 'xla=s4t', Referer: 'https://google.com', 'User-Agent': UA };
var SEARCH = 'https://search.pingora.fyi/collections/post/documents/search';
var SERIES_RE = /\bseason\b|\bS\d{1,2}\b|web series/i;

// The current domain, the way the landing page itself finds it: it asks a list of lookup endpoints for
// <endpoint>?v=<year month day hour> and gets JSON back: "c" is the current site (base64), "h" the landing domain.
// Ours are asked first (they answer in a fraction of a second); the landing page's own list is the fallback.
var LOOKUPS = ['https://h4.suncdn.org/host/', 'https://points.topapii.com/host/', 'https://ml.theapii.org/host/', 'https://dns.pingora.fyi/v2/host', 'https://cdn.hub4u.cloud/host/'];
async function discoverDomain() {
  var d = new Date(), v = 1e6 * d.getFullYear() + 1e4 * (d.getMonth() + 1) + 100 * d.getDate() + d.getHours() + 1;
  async function ask(ep) {
    var r = await http(ep + '?v=' + v, { headers: { 'User-Agent': UA }, timeout: 4000 });
    if (r.status !== 200) return null;
    try {
      var j = JSON.parse(r.text), site = j && j.c ? atob(j.c) : '';
      return /^https?:\/\/[^\/]+/.test(site) ? /^(https?:\/\/[^\/]+)/.exec(site)[1] : null;
    } catch (e) { return null; }
  }
  // all endpoints at once (one that stalls costs nothing): the first answer in list order wins
  var first = (await Promise.all(LOOKUPS.map(ask))).filter(Boolean)[0];
  if (first) return first;
  // none answered: the landing page carries its own (current) list
  try {
    var land = await http(LANDING + '/', { headers: { 'User-Agent': UA }, timeout: 5000 });
    var m = land.status === 200 && /const _rx=\[([^\]]*)\]/.exec(land.text);
    if (m) {
      var own = [], re = /atob\("([^"]+)"\)/g, x;
      while ((x = re.exec(m[1]))) { try { var u = atob(x[1]); if (/^https?:\/\//.test(u) && LOOKUPS.indexOf(u) < 0) own.push(u); } catch (e) {} }
      return (await Promise.all(own.map(ask))).filter(Boolean)[0] || null;
    }
  } catch (e) {}
  return null;
}

function cleanTitle(t) { return unent(String(t || '').replace(/Download/gi, '')); }

// the index's posts for a search text: [{ title, link, imdb }]
async function searchIndex(text) {
  var url = SEARCH + '?q=' + encodeURIComponent(text) + '&query_by=post_title,category,stars,director,imdb_id' +
    '&query_by_weights=4,2,2,2,4&sort_by=sort_by_date:desc&limit=15&highlight_fields=none&page=1';
  var r = await http(url, { headers: { Cookie: 'xla=s4t', Referer: HOSTS[0] + '/', Accept: 'application/json', 'User-Agent': UA }, timeout: 12000 });
  if (r.status !== 200) return [];
  var j;
  try { j = JSON.parse(r.text); } catch (e) { return []; }
  return (j.hits || []).map(function (h) { return h.document || {}; }).filter(function (d) { return d.post_title && d.permalink; })
    .map(function (d) { return { title: cleanTitle(d.post_title), link: d.permalink, imdb: d.imdb_id || '' }; });
}

// a post for this film or show: the one that names its IMDb id, otherwise the best title match; a show's post must
// be for the season asked (the site has one post per season)
async function pickPost(q) {
  function fits(p) { return q.isTv ? SERIES_RE.test(p.title) && (!q.season || parseTitle(p.title).season === null || parseTitle(p.title).season === q.season) : !SERIES_RE.test(p.title); }
  if (q.imdbId) {
    var byId = (await searchIndex(q.imdbId)).filter(function (p) { return p.imdb === q.imdbId && fits(p); });
    if (byId.length) return byId[0];
  }
  return searchBest(q, async function (text) { return (await searchIndex(text)).filter(fits); }, function (p) { return parseTitle(p.title); });
}

// the post page: the index keeps old domains in its links, so the path is tried on every current domain.
// The connection to the site is flaky (about half of the handshakes are reset, measured with tools/diagnose-host.mjs):
// a connection that fails is tried again a few times; an answer of any kind is not.
async function fetchPost(link) {
  var path = link.replace(/^https?:\/\/[^\/]+/, ''), tries = HOSTS.map(function (h) { return { url: h + path, again: 2 }; }); // (each request also retries a reset connection itself: tools/run-guard.js)
  tries.push({ url: link, again: 1 }); // the index's own link (usually a dead domain): once
  var t0 = Date.now();
  for (var i = 0; i < tries.length; i++) {
    for (var n = 0; n < tries[i].again && (n === 0 || Date.now() - t0 < 7000); n++) { // (a few tries within ~7 s: the script has 20 s, and the site answers badly to a flood)
      var r = await http(tries[i].url, { headers: HDH, timeout: 12000 });
      console.log('[hdhub] page ' + tries[i].url + ' try ' + (n + 1) + ' -> ' + r.status + (r.error ? ' ' + String(r.error).slice(0, 80) : '') + ' len=' + (r.text || '').length + (/just a moment/i.test(r.text || '') ? ' (Cloudflare challenge)' : ''));
      if (r.status === 200 && r.text.length > 2000) return { url: tries[i].url, text: r.text };
      if (r.status !== 0) break; // the site answered (an error page, a challenge): asking again will not change it
    }
  }
  return null;
}

function qualityIn(t) { var m = /\b(480|720|1080|2160)p\b|\b4k\b/i.exec(t || ''); return m ? m[0] : ''; }
function isHostLink(h) { return /hubdrive|hubcloud|greenmotors\.|vidara\.|vidaraa\.|hubstream\.|hubcdn\.|hdstream4u\./i.test(h); }
function isWatchText(t) { return /^\s*(watch|player)/i.test(t || ''); } // the "WATCH" and "PLAYER-2" rows: player pages, not downloads
function ownText(el) { return unent(el.kids.filter(function (k) { return typeof k === 'string'; }).join('')) || el.text(); }

// The links of a post. A film: every download-host link whose text names a quality. A show: after a "Single Episode"
// heading the links come per episode and quality, in page order (the episode number is the nth link of that quality
// unless an "Episode N" heading says otherwise); "bonus" rows get numbers of their own so they are not mistaken for
// extra qualities of an episode. Returns { eps: {n: ['label|url']}, movie: ['label|url'] } (the Kotlin provider's load()).
function collectLinks(html, base) {
  var doc = parseHtml(html);
  var c = doc.selectFirst('.page-body') || doc.selectFirst('.entry-content');
  if (!c) return { eps: {}, movie: [] };
  var raw = (doc.selectFirst('h1') || { text: function () { return ''; } }).text();
  var series = SERIES_RE.test(raw);
  function collect(startInSingle) {
    var eps = {}, counters = {}, inSingle = startInSingle, cur = 0, q = '', bonus = 0;
    c.select('h2,h3,h4,h5,a[href]').forEach(function (el) {
      if (el.tag !== 'a') {
        var t = ownText(el).replace(/\s+/g, ' ').replace(/^ +| +$/g, '');
        if (el.tag === 'h2' || el.tag === 'h3') {
          if (/single episode|episode.*(links|index)/i.test(t)) { inSingle = true; cur = 0; counters = {}; }
          else if (/storyline|review|description/i.test(t)) inSingle = false;
        }
        if (/\bbonus\b/i.test(t)) cur = 100 + (++bonus);
        else { var e = /^(?:episodes?\s*[:.-]?\s*|E)(\d+)\b/i.exec(t); if (e) cur = +e[1]; }
        var ql = qualityIn(t);
        if (ql) q = ql;
        return;
      }
      var href = el.attr('href'), isWatch = isWatchText(el.text());
      if (!inSingle || !(isHostLink(href) || isWatch)) return;
      var qual = qualityIn(el.text()) || q;
      var ep = cur > 0 ? cur : (counters[qual] = (counters[qual] || 0) + 1);
      var kind = isWatch || /vidara\.|vidaraa\.|hubstream\.|hdstream4u\./.test(href) ? 'Watch' : (qual || 'Unknown');
      (eps[ep] = eps[ep] || []).push(kind + '|' + absUrl(base + '/', href));
    });
    return eps;
  }
  var eps = collect(false);
  if (!Object.keys(eps).length && series) eps = collect(true);
  var movie = [];
  if (!series) {
    c.select('a[href]').forEach(function (a) {
      var href = a.attr('href'), text = a.text().replace(/\s+/g, ' ').replace(/^ +| +$/g, '').replace(/\|/g, '/');
      if (isHostLink(href) && qualityIn(text)) movie.push(text + '|' + absUrl(base + '/', href));
      else if (isHostLink(href) && isWatchText(text)) movie.push('Watch|' + absUrl(base + '/', href));
    });
  }
  return { eps: eps, movie: movie, series: series };
}

// Dean Edwards' p.a.c.k.e.r: eval(function(p,a,c,k,e,d){...}('payload',base,count,'words'.split('|'))): the text it would run
function unpack(src) {
  var m = /eval\(function\(p,a,c,k,e,(?:r|d)\)\{[\s\S]*?\}\('([\s\S]*?)',(\d+),(\d+),'([\s\S]*?)'\.split\('\|'\)/.exec(src);
  if (!m) return null;
  var p = m[1].replace(/\\'/g, "'").replace(/\\\\/g, '\\'), a = +m[2], c = +m[3], k = m[4].split('|');
  function e(n) { return (n < a ? '' : e(parseInt(n / a, 10))) + ((n = n % a) > 35 ? String.fromCharCode(n + 29) : n.toString(36)); }
  while (c--) if (k[c]) p = p.replace(new RegExp('\\b' + e(c) + '\\b', 'g'), k[c]);
  return p;
}

// One link to direct streams (resolveHop in the Kotlin provider): follows hubdrive / greenmotors / hblinks / hubstream
// to the host that serves the file.
async function resolveHop(link, label, out, depth) {
  if (depth > 3) return;
  var m;
  if (/vidara\.|vidaraa\./.test(link)) {
    var code = link.split('?')[0].replace(/\/+$/, '').split('/').pop();
    if (!code) return;
    var r = await http('https://vidara.to/api/stream', { method: 'POST', headers: { Referer: 'https://vidara.to/e/' + code, 'Content-Type': 'application/json', 'User-Agent': UA }, body: { filecode: code, device: 'web' }, timeout: 12000 });
    var j; try { j = JSON.parse(r.text); } catch (e) { return; }
    if (j && /^https?:/.test(j.streaming_url || '')) out.push({ url: j.streaming_url, type: 'hls', quality: qualityOf(label) || 'Auto', server: 'HDHub4u Vidara', label: label.slice(0, 60), headers: { Referer: 'https://vidara.to/' } });
  } else if (/playdrive\./.test(link)) {
    return; // needs a WebView to unseal its stream
  } else if (/hdstream4u\./.test(link)) {
    // "PLAYER-2": a VidHide-style player page; its packed script has var links={"hls2": ".../master.m3u8?...", ...}
    var pg = await http(link, { headers: { 'User-Agent': UA, Referer: HOSTS[0] + '/' }, timeout: 12000 });
    var code2 = unpack(pg.text || '');
    var lm = code2 && /var links\s*=\s*(\{[^}]*\})/.exec(code2);
    if (!lm) return;
    var links; try { links = JSON.parse(lm[1]); } catch (e) { return; }
    var hls = links.hls4 || links.hls2 || links.hls3;
    var origin = /^(https?:\/\/[^\/]+)/.exec(link)[1];
    if (/^https?:/.test(hls || '')) out.push({ url: hls, type: 'hls', quality: 'Auto', server: 'HDHub4u Player 2', label: 'Hindi + English', headers: { Referer: origin + '/' } });
  } else if (/hubstream\./.test(link)) {
    var id = link.indexOf('#') >= 0 ? link.slice(link.indexOf('#') + 1) : '';
    if (!id) return;
    var rs = await http('https://hubstream.art/api/public/resolve?file1=' + encodeURIComponent(id), { headers: HDH, timeout: 12000 });
    try { var u = JSON.parse(rs.text).url; if (/^https?:/.test(u || '')) await resolveHop(u, label, out, depth + 1); } catch (e) {}
  } else if (/hubcdn\./.test(link)) {
    // "Instant": a redirect page with var reurl = "...?r=<b64(https://hubcdn.club/dl/?link=<file>)>"
    var page = await getText(link, HDH);
    m = /var reurl\s*=\s*"([^"]+)"/.exec(page);
    var rr = m && m[1].split('?r=')[1];
    if (!rr) return;
    try {
      var dec = atob(decodeURIComponent(rr.split('&')[0]).replace(/-/g, '+').replace(/_/g, '/'));
      var fin = (/link=(.+)$/.exec(dec) || [])[1];
      if (fin && /^https?:/.test(fin) && !isZip(fin)) out.push({ url: fin, type: 'mp4', quality: qualityOf(label), server: 'HDHub4u HubCDN', label: label.slice(0, 60) });
    } catch (e) {}
  } else if (/hubcloud/.test(link)) {
    await hubcloud(link, label, out, 'HDHub4u');
  } else if (/hubdrive/.test(link)) {
    var hd = parseHtml(await getText(link, HDH)).selectFirst('a.btn-success1[href]');
    if (hd) await resolveHop(hd.attr('href'), label, out, depth + 1);
  } else if (/greenmotors\./.test(link)) {
    var real = await greenmotors(link);
    if (real) await resolveHop(real, label, out, depth + 1);
  } else if (/hblinks/.test(link)) {
    var d2 = parseHtml(await getText(link, HDH));
    var rows = d2.select('h3, h4, h5').filter(function (r) { return r.select('a[href*=hubcloud], a[href*=hubcdn]').length; });
    if (!rows.length) {
      var first = d2.select('a[href]').map(function (a) { return a.attr('href'); }).filter(function (h) { return /hubcloud/.test(h); })[0];
      if (first) await resolveHop(first, label, out, depth + 1);
    } else {
      await Promise.all(rows.map(function (r) {
        var ql = r.text().split('–')[0].split('Drive')[0].replace(/^ +| +$/g, '') || label;
        return Promise.all(r.select('a[href*=hubcloud], a[href*=hubcdn]').map(function (a) { return resolveHop(a.attr('href'), ql, out, depth + 1); }));
      }));
    }
  }
}

async function getStreams(query) {
  // The site's own lookup and domains.json (the repository's list) start together; either may stall for seconds. When the lookup
  // answers, that is the domain and domains.json is not waited for; only without an answer does domains.json decide.
  var fromFile = siteDomains('hdhub', HOSTS).catch(function () { return HOSTS; });
  var live = await discoverDomain().catch(function () { return null; });
  if (live) HOSTS = [live].concat(HOSTS.filter(function (h) { return h !== live; }));
  else HOSTS = await fromFile;
  console.log('[hdhub] domains: ' + HOSTS.join(' ') + (live ? ' (current: ' + live + ')' : ' (lookup gave none)'));
  var post = await pickPost(query);
  console.log('[hdhub] post: ' + (post ? post.title.slice(0, 60) + ' ' + post.link : 'none found'));
  if (!post) return [];
  var page = await fetchPost(post.link);
  if (!page) return []; // the site could not be reached
  var base = /^(https?:\/\/[^\/]+)/.exec(page.url)[1];
  var found = collectLinks(page.text, base);
  var links = query.isTv ? (found.eps[query.episode || 1] || []) : found.movie;
  console.log('[hdhub] page read: ' + links.length + ' download links' + (query.isTv ? ' for episode ' + (query.episode || 1) + ' of ' + Object.keys(found.eps).length : ''));
  // player pages first (HLS starts at once), then mid qualities (720p/1080p), small first; the rest after
  links = links.slice().sort(function (a, b) {
    var wa = /^Watch\|/.test(a) ? 0 : 1, wb = /^Watch\|/.test(b) ? 0 : 1;
    if (wa !== wb) return wa - wb;
    var qa = parseInt(qualityIn(a), 10) || 0, qb = parseInt(qualityIn(b), 10) || 0;
    var ra = qa >= 720 && qa <= 1080 ? 0 : 1, rb = qb >= 720 && qb <= 1080 ? 0 : 1;
    return ra - rb || sizeMB(a) - sizeMB(b);
  }).slice(0, MAX);
  var out = [];
  await Promise.all(links.map(function (l) {
    var i = l.indexOf('|');
    return resolveHop(l.slice(i + 1), l.slice(0, i), out, 0).catch(function () {});
  }));
  // A PixelDrain file that does not exist (the sites' pages carry a dead one) would cost one of the links tried first: each file is
  // checked once, and when there is no time to check, PixelDrain links are left out (they rank last anyway).
  out = dedupe(out);
  var checked = {};
  out.forEach(function (st) {
    var pd = /pixeldrain\.com\/api\/file\/([A-Za-z0-9]+)/.exec(st.url);
    if (pd && !checked[pd[1]]) checked[pd[1]] = DEADLINE - Date.now() > 2500
      ? http('https://pixeldrain.com/api/file/' + pd[1] + '/info', { timeout: 3000 }).then(function (r) { return r.status === 200; })
      : Promise.resolve(false);
  });
  var ids = Object.keys(checked), verdicts = await Promise.all(ids.map(function (id) { return checked[id]; })), okId = {};
  ids.forEach(function (id, i) { okId[id] = verdicts[i]; });
  out = out.filter(function (st) { var pd = /pixeldrain\.com\/api\/file\/([A-Za-z0-9]+)/.exec(st.url); return !pd || okId[pd[1]]; });
  // HLS first, then the direct files, PixelDrain last
  function rank(st) { return st.type === 'hls' ? 0 : /pixeldrain/.test(st.url) ? 2 : 1; }
  out = out.map(function (st, i) { return { st: st, i: i }; }).sort(function (a, b) { return rank(a.st) - rank(b.st) || a.i - b.i; }).map(function (x) { return x.st; });
  console.log('[hdhub] streams: ' + out.length);
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
