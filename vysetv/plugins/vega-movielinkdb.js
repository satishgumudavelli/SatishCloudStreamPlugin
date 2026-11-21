// VyseTV plugin ported from the CloudStream provider "MovieLinkDb (Vega)" (MovieLinkDb.kt).
// Chain: /search?q= -> detail page (download buttons hide their /file/ path in data-dl: base64 XOR an LCG keystream)
//   -> /file/<id> -> FSL entry button -> /file/<id> with the fsl.movielink.tv/d/... direct file link.
// Only the FSL host is resolved (gphotos/mp4/telegram/xcloud hosts are skipped).

var HOSTS = ['https://ex5wfv.movielinkbd.li', 'https://wvaedp.movielinkbd.at'];

function norm(s) {
  return String(s || '').toLowerCase().replace(/&amp;/g, '&').replace(/&/g, ' and ').replace(/[^a-z0-9]+/g, ' ').trim();
}

// The page's own decoder: base64, XOR with an LCG keystream seeded by data-ds, after data-dr warm-up rounds.
function dec(b, s, r) {
  var x = parseInt(s, 16) >>> 0, i;
  for (i = 0; i < r; i++) x = (Math.imul(x, 1103515245) + 12345) >>> 0;
  var raw = atob(b), o = '';
  for (i = 0; i < raw.length; i++) {
    x = (Math.imul(x, 1103515245) + 12345) >>> 0;
    o += String.fromCharCode(raw.charCodeAt(i) ^ ((x >>> 16) & 255));
  }
  return o;
}

function origin(u) { return (/^https?:\/\/[^\/]+/.exec(u) || [''])[0]; }

// [{label, path}] for every data-dl button in html
function buttons(html) {
  var out = [];
  html.replace(/<a\b[^>]*data-dl="([^"]+)"[^>]*>([\s\S]*?)<\/a>/g, function (m, b, inner) {
    var tag = m.slice(0, m.indexOf('>'));
    var ds = /data-ds="([^"]*)"/.exec(tag), dr = /data-dr="(\d*)"/.exec(tag);
    var label = (/\[([^\]]*)\]/.exec(inner.replace(/<[^>]+>/g, ' ')) || [])[1] || inner.replace(/<[^>]+>/g, ' ').trim();
    try { out.push({ label: label.replace(/\s+/g, ' ').replace(/&bull;|•/g, '-').trim(), path: dec(b, ds ? ds[1] : '', dr ? +dr[1] : 0) }); } catch (e) {}
  });
  return out;
}

async function get(base, path) {
  var hosts = base ? [base] : HOSTS;
  for (var i = 0; i < hosts.length; i++) {
    var r = await http(hosts[i] + path, { timeout: 15000 });
    if (r.status === 200 && r.text) return r;
  }
  return null;
}

async function resolveFile(base, path, label, out) {
  var r1 = await http(base + path, { timeout: 15000 });
  var e = /<a[^>]*href="(\/file\/[^"]+)"[^>]*data-host="fsl"/.exec(r1.text || '');
  if (!e) return;
  var r2 = await http(origin(r1.url) + e[1], { timeout: 15000 });
  var f = /<a[^>]*class="[^"]*fsl-download[^"]*"[^>]*href="([^"]+)"/.exec(r2.text || '') ||
    /<a[^>]*href="([^"]+)"[^>]*class="[^"]*fsl-download/.exec(r2.text || '');
  if (!f) return;
  var q = /(\d{3,4})p/i.exec(label) || (/4k/i.test(label) ? [0, '2160'] : null);
  out.push({ url: f[1].replace(/&amp;/g, '&'), type: 'mp4', quality: q ? q[1] + 'p' : '', server: 'MovieLinkDb FSL', label: label });
}

async function getStreams(query) {
  if (!query.title) return [];
  var s = await get('', '/search?q=' + encodeURIComponent(query.title));
  if (!s) return [];
  var base = origin(s.url);
  var want = norm(query.title), year = String(query.year || ''), isTv = !!query.isTv;
  var cards = s.text.split('<div class="movie-card').slice(1).map(function (c) {
    var href = /href="(\/(?:movie|series)\/[^"]+)"/.exec(c), alt = /alt="([^"]*)"/.exec(c);
    return href && alt ? { path: href[1], title: alt[1] } : null;
  }).filter(Boolean);
  // "The Matrix (1999) [ORG Hindi]" / "Breaking Bad (2008) S01 Completed" -> name before the year / season tag
  function parse(t) {
    var m = /^(.*?)\s*(?:[(\[]((?:19|20)\d\d)[)\]]|\bS\d{1,2}\b|-\s*S\d)/i.exec(t);
    return { name: norm(m ? m[1] : t), year: m && m[2] || '', season: +((/\bS(\d{1,2})\b/i.exec(t) || [])[1] || 0) };
  }
  var hits = cards.filter(function (c) {
    var p = parse(c.title);
    if (p.name !== want || (/\/series\//.test(c.path)) !== isTv) return false;
    return isTv || !year || !p.year || p.year === year;
  });
  var out = [];
  if (!isTv) {
    if (!hits[0]) return [];
    var d = await get(base, hits[0].path);
    if (!d) return [];
    var bs = buttons(d.text);
    await Promise.all(bs.map(function (b) { return resolveFile(base, b.path, b.label, out); }));
    return out;
  }
  // TV: pick the post for the season (S01-S03 packs are not matched), then the single-episode card.
  var post = hits.filter(function (c) { return parse(c.title).season === (query.season || 1); })[0];
  if (!post) return [];
  var dd = await get(base, post.path);
  if (!dd) return [];
  var cardsHtml = dd.text.split(/<div[^>]*class="[^"]*ep-card/).slice(1);
  for (var i = 0; i < cardsHtml.length; i++) {
    var c = cardsHtml[i];
    var en = /data-episode-number="(\d+)"/.exec(c), rs = /data-range-start="(\d*)"/.exec(c), re = /data-range-end="(\d*)"/.exec(c);
    if (!en || +en[1] !== (query.episode || 1) || (rs && re && rs[1] !== re[1])) continue;
    var bs2 = buttons(c);
    await Promise.all(bs2.map(function (b) { return resolveFile(base, b.path, b.label, out); }));
    break;
  }
  return out;
}
