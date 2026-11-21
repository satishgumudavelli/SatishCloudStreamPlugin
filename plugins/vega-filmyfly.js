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
