// VyseTV plugin ported from the CloudStream provider "UhdMovies (Vega)" (VegaProvider/UhdMovies.kt).
// Chain: /search/<title>/ -> post -> thenaukriadda shortlink buttons -> bypass -> driveseed -> direct mkv.
let DOMAINS = ['https://uhdmovies.ceo', 'https://uhdmovies.beauty']; // root redirects to the live domain
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';
const get = async (url, headers, timeout) => { const o = { headers: Object.assign({ 'User-Agent': UA }, headers || {}), timeout: timeout || 15000 }; const r = await http(url, o); return r.status === 0 || r.status >= 500 ? await http(url, o) : r; }; // one retry: these hosts drop connections
const post = (url, headers, body) => http(url, { method: 'POST', headers: Object.assign({ 'User-Agent': UA, 'Content-Type': 'application/x-www-form-urlencoded' }, headers || {}), body, timeout: 15000 });
const strip = (s) => String(s || '').replace(/<[^>]*>/g, ' ').replace(/&amp;/g, '&').replace(/&#8211;|&#8212;/g, '-').replace(/&#?\w+;/g, ' ').replace(/\s+/g, ' ').trim();
const attrOf = (tag, n) => (new RegExp('\\b' + n + '\\s*=\\s*(?:"([^"]*)"|\'([^\']*)\'|([^\\s>]+))', 'i').exec(tag) || []).slice(1).find((x) => x != null) || '';
const unesc = (s) => String(s || '').replace(/&amp;/g, '&');
const origin = (u) => (/^(https?:\/\/[^\/?#]+)/.exec(u) || [])[1] || '';
const absUrl = (u, base) => /^https?:/i.test(u) ? u : /^\/\//.test(u) ? 'https:' + u : origin(base) + (u[0] === '/' ? '' : '/') + u;
const qualityOf = (s) => { s = String(s); const m = /(\d{3,4})p/i.exec(s); if (m) return m[1] + 'p'; return /4k|uhd|2160/i.test(s) ? '2160p' : ''; };
const typeOf = (u) => /\.m3u8/i.test(u) ? 'hls' : /\.mpd/i.test(u) ? 'dash' : 'mp4';
const anchors = (html, hrefRe) => { const out = []; const re = /<a\b([^>]*)>([\s\S]*?)(?=<\/a>|<a\b|$)/gi; let m; while ((m = re.exec(html))) { const h = unesc(attrOf(m[1], 'href')); if (h && (!hrefRe || hrefRe.test(h))) out.push({ href: h, text: strip(m[2]), tag: m[1] }); } return out; };
const dedupe = (arr) => { const seen = {}; return arr.filter((x) => (seen[x.url] ? false : (seen[x.url] = 1))); };

// Cookie jar from the set-cookie header(s); http() may join several with ", ".
const eatCookies = (jar, r) => { const sc = r.headers && r.headers['set-cookie']; if (!sc) return; String(sc).split(/,(?=\s*[^;,=\s]+=)/).forEach((c) => { const kv = c.split(';')[0].trim(); const i = kv.indexOf('='); if (i > 0) jar[kv.slice(0, i)] = kv.slice(i + 1); }); };
const cookieStr = (jar) => Object.keys(jar).map((k) => k + '=' + jar[k]).join('; ');

// thenaukriadda shortlink bypass: 3 form POSTs + lp_go -> destination url (null when the chain breaks).
async function bypass(u) {
  const jsRe = /location\.replace\(["']([^"']+)["']\)/;
  const jar = {};
  const r1 = await get(u); eatCookies(jar, r1);
  const lp = /name="_lp_http"[^>]*value="([^"]*)"|value="([^"]*)"[^>]*name="_lp_http"/.exec(r1.text);
  if (!lp) { const m = jsRe.exec(r1.text); return m ? m[1].replace(/\\/g, '') : (r1.status === 200 ? u : null); }
  const a1 = unesc(attrOf((/<form[^>]*id="lp-land"[^>]*>/.exec(r1.text) || [''])[0], 'action')) || u;
  const r2 = await post(a1, { Referer: u, Cookie: cookieStr(jar) }, '_lp_http=' + encodeURIComponent(lp[1] || lp[2])); eatCookies(jar, r2);
  const fm = /<form[^>]*id="lp-s1-form"[^>]*>([\s\S]*?)<\/form>/.exec(r2.text); if (!fm) return null;
  const a2 = unesc(attrOf(fm[0], 'action')) || a1;
  const fields = []; (fm[1].match(/<input\b[^>]*>/gi) || []).forEach((t) => { const n = attrOf(t, 'name'); if (n) fields.push(encodeURIComponent(n) + '=' + encodeURIComponent(unesc(attrOf(t, 'value')))); });
  const r3 = await post(a2, { Referer: a1, Cookie: cookieStr(jar) }, fields.join('&')); eatCookies(jar, r3);
  const m = /sc\(["']([^"']+)["'],\s*["']([^"']+)["']/.exec(r3.text); if (!m) return null;
  jar[m[1]] = m[2];
  const r4 = await get(origin(a2) + '/?lp_go=' + m[1], { Referer: a2, Cookie: cookieStr(jar) });
  const j = jsRe.exec(r4.text); return j ? j[1].replace(/\\/g, '') : null;
}

// driveseed/driveleech file page -> direct "Cloud Download" mkv/mp4 buttons.
async function driveSeed(shortUrl, label) {
  const target = await bypass(shortUrl); if (!target) return [];
  if (!/driveseed|driveleech/.test(target)) return await resolveMirror(target, label);
  let file = target;
  if (/\/r\?/.test(target)) {
    const r = await get(target, { Referer: 'https://en.thenaukriadda.in/' });
    const m = /location\.replace\(["']([^"']+)["']\)/.exec(r.text); if (!m) return [];
    file = absUrl(m[1].replace(/\\/g, ''), target);
  }
  const page = await get(file, { Referer: target });
  const out = [];
  const inst = [];
  anchors(page.text).forEach((a) => {
    if (/^https?:/.test(a.href) && /\.(mkv|mp4)$/i.test(a.href.split('?')[0])) out.push({ url: a.href, type: 'mp4', quality: qualityOf(label), server: 'DriveSeed', label: strip(label).slice(0, 90) });
    else if (/video-gen\.xyz|video-seed/.test(a.href) && /instant/i.test(a.text)) inst.push(a.href);
  });
  // "Instant Download" pages redirect to video-seed.dev/?url=<direct googleusercontent file>
  for (const u of inst.slice(0, 1)) {
    const r = await get(u, { Referer: file, Range: 'bytes=0-0' }, 15000);
    const m = /[?&]url=(https?[^&]+)/.exec(r.url);
    if (m) { const d = decodeURIComponent(m[1]); if (!isZip(d)) out.push({ url: d, type: 'mp4', quality: qualityOf(label) || qualityOf(page.text), server: 'DriveSeed Instant', label: strip(label).slice(0, 90) }); }
  }
  return out;
}
const atob2 = (s) => { try { return atob(atob(s)); } catch (e) { return ''; } };
const ext = (u) => /\.(mkv|mp4|avi|mov|m3u8|mpd)$/i.test(String(u).split('?')[0]);
const isZip = (u) => { let d = u; try { d = decodeURIComponent(u); } catch (e) {} return /\.zip/i.test(d); };
const link = (url, server, label, quality, extra) => Object.assign({ url, type: typeOf(url), quality: quality || qualityOf(label), server, label: strip(label).slice(0, 90) }, extra || {});
// The final url a redirect chain ends at, without downloading the body (Range 0-0).
async function finalUrl(u, headers) { const r = await get(u, Object.assign({ Range: 'bytes=0-0' }, headers || {}), 20000); return r.status >= 200 && r.status < 400 ? r.url : ''; }

async function hubCloud(href, label) {
  const out = [];
  const hosts = [origin(href), 'https://hubcloud.pw', 'https://hubcloud.ist', 'https://hubcloud.foo', 'https://hubcloud.lol'].filter((h, i, a) => h && a.indexOf(h) === i);
  let drive = null;
  for (const h of hosts) {
    const r = await get(href.replace(origin(href), h), null, 12000);
    if (r.status === 200 && /id="download"|hubcloud\.php|atob\(/.test(r.text)) { drive = r; break; }
  }
  if (!drive) return out;
  let gen = '';
  const dl = /<a\b[^>]*id="download"[^>]*>/i.exec(drive.text);
  if (dl) { const h = unesc(attrOf(dl[0], 'href')); if (h && h !== '#') gen = h; }
  if (!gen) { const m = /href="(https?:[^"]*hubcloud\.php[^"]*)"/.exec(drive.text); if (m) gen = unesc(m[1]); }
  if (!gen) { const m = /atob\(atob\(['"]([^'"]+)['"]\)\)/.exec(drive.text); if (m) gen = atob2(m[1]); }
  if (!gen) return out;
  const page = await get(gen, { Referer: drive.url }, 15000);
  const header = strip((/<div class="card-header"[^>]*>([\s\S]*?)<\/div>/.exec(page.text) || [])[1]);
  const size = strip((/<i id="size"[^>]*>([\s\S]*?)<\/i>/.exec(page.text) || [])[1]);
  const q = qualityOf(header) || qualityOf(label);
  const lab = (header || label) + (size ? ' [' + size + ']' : '');
  for (const a of anchors(page.text)) {
    const btn = /class="[^"]*\bbtn\b/.test(a.tag);
    if (!btn || !/^https?:/.test(a.href) || !a.text || /telegram|watch online|android app/i.test(a.text)) continue;
    let u = a.href;
    const id = attrOf(a.tag, 'id');
    if (id) { const m = new RegExp('getElementById\\("' + id + '"\\)\\.href\\s*=\\s*["\']([^"\']+)["\']').exec(page.text); if (m) u = m[1]; }
    if (isZip(u) || /r2\.cloudflarestorage\.com|fuckingfast\./.test(u)) continue;
    if (/10Gbps/i.test(a.text)) {
      const f = await finalUrl(u); const L = /[?&]link=(.+)$/.exec(f);
      const v = L ? decodeURIComponent(L[1]) : f;
      if (v && !isZip(v) && ext(v)) out.push(link(v, 'HubCloud 10Gbps', lab, q));
      continue;
    }
    if (/pixeldrain/i.test(u) && /\/u\//.test(u)) { out.push(link('https://pixeldrain.com/api/file/' + u.split('/u/')[1].split(/[?#]/)[0] + '?download', 'Pixeldrain', lab, q)); continue; }
    if (ext(u) || /pixeldrain\.|workers\.dev|googleusercontent|r2\.dev|\.mkv|\.mp4/i.test(u)) out.push(link(u, 'HubCloud ' + a.text.replace(/^Download\s*/i, '').slice(0, 20), lab, q));
  }
  return out;
}

async function gdflix(href, label) {
  const r = await get(href, null, 15000); if (r.status !== 200) return [];
  const name = (/Name\s*:\s*([^<]*)/.exec(r.text) || [])[1] || label;
  const out = [];
  const q = qualityOf(name) || qualityOf(label);
  for (const a of anchors(r.text)) {
    if (/FAST CLOUD|ZIP/i.test(a.text) || /\.zip/i.test(a.href)) continue;
    const full = /^https?:/.test(a.href) ? a.href : absUrl(a.href, href);
    if (/Instant DL/i.test(a.text)) { const rr = await http(full, { headers: { 'User-Agent': UA, Range: 'bytes=0-0' }, timeout: 15000 }); const u = rr.url; if (u && u !== full && ext(u.split('?')[0]) !== false && !/gdflix/.test(origin(u))) out.push(link(u, 'GDFlix Instant', name, q)); }
    else if (/Direct DL|Direct Server/i.test(a.text)) out.push(link(full, 'GDFlix Direct', name, q));
    else if (/Cloud Download/i.test(a.text)) out.push(link(decodeURIComponent(full.split('url=')[1] || full), 'GDFlix Cloud', name, q));
    else if (/pixeldra/i.test(full)) out.push(link('https://pixeldrain.com/api/file/' + full.split('/').pop().split('?')[0] + '?download', 'Pixeldrain', name, q));
  }
  return out;
}

async function filepress(href, label) {
  const o = origin(href), id = href.replace(/[?#].*$/, '').replace(/\/+$/, '').split('/').pop();
  const call = async (path, v) => { const r = await http(o + path, { method: 'POST', headers: { 'User-Agent': UA, 'Content-Type': 'application/json', Referer: o }, body: JSON.stringify({ id: v, method: 'indexDownlaod', captchaValue: null }), timeout: 15000 }); try { return JSON.parse(r.text); } catch (e) { return null; } };
  const t = await call('/api/file/downlaod/', id); if (!t || !t.status || !t.data) return [];
  const j = await call('/api/file/downlaod2/', t.data); if (!j) return [];
  const d = (j.data && j.data.data) || j.data; const u = Array.isArray(d) ? d[0] : '';
  return u && !isZip(u) ? [link(u, 'Filepress', label)] : [];
}

async function resolveMirror(href, label) {
  const l = href.toLowerCase();
  try {
    if (/hubcloud|vcloud/.test(l)) return await hubCloud(href, label);
    if (/hubcdn\./.test(l)) { const m = /[?&]link=(.+)$/.exec(href); if (!m) return []; const u = decodeURIComponent(m[1]); return isZip(u) ? [] : [link(u, 'HubCDN', label)]; }
    if (/gdflix|gdlink/.test(l)) return await gdflix(href, label);
    if (/fastdl\./.test(l)) { const r = await get(href); const re = /var reurl\s*=\s*"([^"]+)"/.exec(r.text); if (!re) return []; const m = /[?&]link=(.+)$/.exec(re[1]); const u = m ? decodeURIComponent(m[1]) : re[1]; return isZip(u) ? [] : [link(u, 'FastDL', label)]; }
    if (/filepress|filebee/.test(l)) return await filepress(href, label);
    if (/pixeldrain/.test(l) && /\/u\//.test(l)) return [link('https://pixeldrain.com/api/file/' + href.split('/u/')[1].split(/[?#]/)[0] + '?download', 'Pixeldrain', label)];
    if (ext(href)) return [link(href, 'Mirror', label)];
  } catch (e) { console.log('mirror error ' + e); }
  return [];
}

async function liveBase() {
  for (const d of DOMAINS) { const r = await get(d + '/', null, 12000); if (r.status === 200 && r.text.length > 5000) return origin(r.url); }
  return '';
}

const seasonRange = (t) => { const m = /season[s]?\s*\{?\s*0*(\d+)(?:\s*[-&to]+\s*0*(\d+))?/i.exec(t); return m ? [+m[1], +(m[2] || m[1])] : null; };

async function findPost(base, q) {
  const seen = {};
  for (const s of searchQueries(q)) {
    const r = await get(base + '/search/' + encodeURIComponent(s).replace(/%20/g, '+') + '/');
    const items = [], re = /class="entry-image"[\s\S]{0,200}?<a href="([^"]+)" title="([^"]*)"/gi; let m;
    while ((m = re.exec(r.text))) { if (seen[m[1]]) continue; seen[m[1]] = 1; items.push({ href: m[1], title: strip(m[2]).replace(/^Download\s+/i, '') }); }
    let c = rankMatches(items, q, (i) => { const p = parseTitle(i.title); return { title: p.title, year: p.year }; });
    if (q.isTv) c = c.filter((i) => { const rg = seasonRange(i.title); return rg && q.season >= rg[0] && q.season <= rg[1]; });
    if (c[0]) return c[0];
  }
  return null;
}

// (heading, shortlink, button text) in page order: <p>file/size text</p><p><a class=maxbutton href=thenaukriadda>.
function buttons(html) {
  const body = (html.split(/class="entry-content/)[1] || html).split(/We Do not host/)[0];
  const out = []; let heading = '';
  const re = /<(p|h[345])\b[^>]*>([\s\S]*?)<\/\1>/gi; let m;
  while ((m = re.exec(body))) {
    const btns = anchors(m[2], /thenaukriadda/).filter((a) => /maxbutton/.test(a.tag));
    const t = strip(m[2].replace(/<a\b[\s\S]*?<\/a>/gi, ' '));
    if (t.length > 15) heading = t; // a heading paragraph may carry its own (zip/pack) button
    btns.forEach((a) => out.push({ heading, href: a.href, text: a.text }));
  }
  return out;
}

async function getStreams(q) {
  DOMAINS = await siteDomains('UhdMovies', DOMAINS); // domains.json first (the repository's), then this list
  const base = await liveBase(); if (!base) return [];
  const post = await findPost(base, q); if (!post) return [];
  const page = await get(post.href); if (page.status !== 200) return [];
  let bl = buttons(page.text).filter((b) => !/zip|pack/i.test(b.text) && !/\bzip\b|\bpack\b/i.test(b.heading));
  let picks = [];
  if (!q.isTv) picks = bl.filter((b) => !/^episode/i.test(b.text));
  else {
    // group by heading (one quality block per heading); take the episode'th button of each block that is for this season
    const groups = [];
    bl.forEach((b) => { const g = groups[groups.length - 1]; if (g && g.heading === b.heading) g.items.push(b); else groups.push({ heading: b.heading, items: [b] }); });
    groups.forEach((g) => {
      const s = (/\bS(\d{1,2})\b/.exec(g.heading) || /season\s*0*(\d+)/i.exec(g.heading) || [])[1];
      if (s && +s !== +q.season) return;
      const eps = g.items.filter((b) => /^episode/i.test(b.text));
      const e = eps.find((b) => new RegExp('^episode\\s*0*' + q.episode + '\\b', 'i').test(b.text)) || eps[(q.episode || 1) - 1];
      if (e) picks.push(e);
    });
  }
  picks = picks.slice(0, 4);
  const per = await Promise.all(picks.map((b) => driveSeed(b.href, b.heading).catch(() => [])));
  return dedupe([].concat(...per));
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
