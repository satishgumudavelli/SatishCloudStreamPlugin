// VyseTV plugin ported from the CloudStream provider "Topmovies (Vega)" (VegaProvider/Topmovies.kt, site moviesleech).
// Chain: /search/<title>/ -> post -> leechpro.blog quality pages -> thenaukriadda shortlink bypass -> driveseed -> direct mkv.
let DOMAINS = ['https://moviesleech.help', 'https://moviesleech.media']; // root redirects to the live domain
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

const seasonRange = (t) => { const m = /season[s]?\s*\{?\s*0*(\d+)(?:\s*[-&to]+\s*0*(\d+))?/i.exec(t) || /\bS0*(\d{1,2})\b/.exec(t); return m ? [+m[1], +(m[2] || m[1])] : null; };

async function searchItems(base, s) {
  const r = await get(base + '/search/' + encodeURIComponent(s).replace(/%20/g, '+') + '/');
  const items = [], re = /<h2 class="title[^>]*>\s*<a href="([^"]+)"[^>]*>([\s\S]*?)<\/a>/gi; let m;
  while ((m = re.exec(r.text))) items.push({ href: m[1], title: strip(m[2]).replace(/^Download\s+/i, '') });
  return items;
}
// a show's post covers some seasons ("Season 1-3"): is the one asked for in it?
function coversSeason(it, q) { const rg = seasonRange(it.title); it.single = rg && rg[0] === rg[1]; return !!rg && q.season >= rg[0] && q.season <= rg[1]; }
async function findPost(base, q) {
  const seen = {}, t0 = Date.now(), qs = searchQueries(q);
  // the site's search for the IMDb id; the post's page must name it (sure, so it goes after the first two strings)
  const byImdb = async () => {
    const hit = await searchByImdb(q, (s) => searchItems(base, s), async (it) => { const p = await get(it.href); return p.status === 200 ? p.text : ''; }, (it) => !q.isTv || coversSeason(it, q));
    return hit ? { href: hit.item.href, single: hit.item.single, page: { status: 200, text: hit.page } } : null;
  };
  let imdbTried = false;
  for (let i = 0; i < qs.length; i++) {
    if (i === 2) { imdbTried = true; const r = await byImdb(); if (r) return r; }
    if (Date.now() - t0 > 12000) break; // the later strings are long shots: stay inside the time limit
    const items = await searchItems(base, qs[i]);
    let c = rankMatches(items, q, (it) => { const p = parseTitle(it.title); return { title: p.title, year: p.year }; });
    if (q.isTv) c = c.filter((it) => coversSeason(it, q));
    for (const it of c.slice(0, 3)) {
      if (seen[it.href]) continue; seen[it.href] = 1;
      const page = await get(it.href); if (page.status !== 200) continue;
      if (imdbMatches(page.text, q) === false) continue; // the page names another film's IMDb id
      return { href: it.href, single: it.single, page };
    }
  }
  return imdbTried ? null : byImdb();
}

// (heading, leechpro url) pairs in page order; zip/batch buttons are skipped.
function blocks(html) {
  const out = []; let heading = '';
  const re = /<(h[34])\b[^>]*>([\s\S]*?)<\/\1>|<a\b([^>]*href="[^"]*leechpro\.[a-z]+\/archives[^"]*"[^>]*)>/gi; let m;
  while ((m = re.exec(html))) {
    if (m[3]) { const u = unesc(attrOf(m[3], 'href')); if (heading && !/zip|batch/i.test(m[3]) && !out.some((o) => o.url === u)) out.push({ heading, url: u }); }
    else { const t = strip(m[2]); if (t) heading = t; }
  }
  return out;
}

async function getStreams(q) {
  if (typeof siteDomains === 'function') DOMAINS = await siteDomains('Topmovies', DOMAINS); // domains.json first (the repository's), then this list
  const base = await liveBase(); if (!base) return [];
  const post = await findPost(base, q); if (!post) return [];
  const page = post.page;
  const bl = blocks(page.text).slice(0, 4);
  const per = await Promise.all(bl.map(async (b) => {
    const mp = await get(b.url);
    const btns = anchors(mp.text, /thenaukriadda/);
    const picks = q.isTv ? btns.filter((a) => new RegExp('^Episode\\s*0*' + (q.episode || 1) + '\\b', 'i').test(a.text)).slice(0, 1) : btns.filter((a) => /drive/i.test(a.text)).slice(0, 1);
    const r = await Promise.all(picks.map((a) => driveSeed(a.href, b.heading).catch(() => [])));
    return [].concat(...r);
  }));
  return dedupe([].concat(...per));
}
