// VyseTV plugin ported from the CloudStream provider "ExtraMovies Sarl (Vega)" (ExtraMoviesSarl.kt, MobileJsrResolver.kt).
// Chain: /?s= search -> post -> mobilejsr.com/view/<id> buttons (one per quality) -> POST the "Unlock Links" form
//   (_csrf + session cookie) -> host links. Resolved here: HubCloud only (PixelServer -> pixeldrain API, direct buttons).
//   Skipped: gofile (needs a computed website token now), streamtape (unreachable when tested), voe, hubdrive, filepress,
//   1fichier, megaup, vikingfile, frdl, uploadflix (captcha/countdown pages).
// Movies only: series posts are batches (e.g. S01E37-40) with no per-episode links. Many posts' mobilejsr links 404 (expired
// on the site itself); those posts yield nothing.

var HOSTS = ['https://extramovies.sarl', 'https://extramovies.here'];
var UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36';

function origin(u) { return (/^https?:\/\/[^\/]+/.exec(u) || [''])[0]; }
function strip(h) { return h.replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim(); }
function qualityOf(s) {
  var m = /(\d{3,4})p/i.exec(s || '');
  return m ? m[1] + 'p' : (/4k|uhd/i.test(s || '') ? '2160p' : '');
}
function hostOf(u) { return (/^https?:\/\/(?:www\.)?([^\/?]+)/.exec(u) || [])[1] || ''; }

// ---- extractors -------------------------------------------------------------------------------------------------
async function pixel(href, label, out) {
  var id = /\/u\/([A-Za-z0-9]+)/.exec(href);
  if (id) out.push({ url: 'https://pixeldrain.com/api/file/' + id[1], type: 'mp4', quality: qualityOf(label), server: 'PixelDrain', label: label });
}

// hubcloud.*/video/<id> (or /drive/<id>) -> hubcloud.php generate page -> buttons
async function hubcloud(href, label, out) {
  var d = await http(href, { timeout: 15000 });
  var g = /href="(https?:[^"]*hubcloud\.php[^"]*)"/.exec(d.text || '') || /id="download"[^>]*href="(https?:[^"]+)"/.exec(d.text || '');
  if (!g) return;
  var f = await http(g[1].replace(/&amp;/g, '&'), { timeout: 15000 });
  var html = f.text || '';
  var header = strip((/class="card-header"[^>]*>([\s\S]*?)<\/div>/.exec(html) || [])[1] || '');
  var l2 = qualityOf(header) ? label + ' ' + qualityOf(header) : label;
  html.replace(/<a\b[^>]*class="[^"]*\bbtn\b[^"]*"[^>]*>/g, function (tag) {
    var h = (/href="([^"]+)"/.exec(tag) || [])[1];
    if (!h || !/^https?:/.test(h)) return;
    h = h.replace(/&amp;/g, '&');
    if (/pixeldrain/i.test(h) && /\/u\//.test(h)) pixel(h, l2, out);
    else if (/\.(mkv|mp4)(\?|$)/i.test(h) && !/r2\.cloudflarestorage|fuckingfast/.test(h))
      out.push({ url: h, type: 'mp4', quality: qualityOf(l2), server: 'HubCloud', label: l2 });
  });
}

// ---- site -------------------------------------------------------------------------------------------------------
async function unlock(view) {
  var r1 = await http(view, { headers: { 'User-Agent': UA }, timeout: 15000 });
  if (r1.status !== 200) return [];
  var csrf = /name="_csrf"[^>]*value="([^"]+)"/.exec(r1.text) || /value="([^"]+)"[^>]*name="_csrf"/.exec(r1.text);
  var html = r1.text;
  if (csrf) {
    var ck = ((r1.headers || {})['set-cookie'] || '').split(';')[0];
    var r2 = await http(view, { method: 'POST', timeout: 15000, body: '_csrf=' + encodeURIComponent(csrf[1]),
      headers: { 'User-Agent': UA, 'Content-Type': 'application/x-www-form-urlencoded', Referer: view, Cookie: ck } });
    html = r2.text || '';
  }
  var hrefs = [];
  html.replace(/<a[^>]*href="(https?:[^"]+)"/g, function (m, h) { h = h.replace(/&amp;/g, '&'); if (hrefs.indexOf(h) < 0) hrefs.push(h); });
  return hrefs;
}

async function getStreams(query) {
  if (query.isTv || !query.title) return [];
  // each search string in turn (the first is as fast as before); later ones only when nothing matched
  var posts = [], qs = searchQueries(query);
  for (var j = 0; j < qs.length && !posts.length; j++) {
    var s = null;
    for (var i = 0; i < HOSTS.length && !s; i++) {
      var r = await http(HOSTS[i] + '/?s=' + encodeURIComponent(qs[j]), { headers: { 'User-Agent': UA }, timeout: 15000 });
      if (r.status === 200 && r.text) s = r;
    }
    if (!s) continue;
    var cands = [];
    s.text.replace(/<a[^>]*href="(https?:[^"]+)"[^>]*>\s*<div[^>]*poster-card[\s\S]*?poster-title[^>]*>([^<]*)/g, function (m, href, t) {
      if (!/series|season|\bS\d\d/i.test(t)) cands.push({ href: href, title: t.replace(/&#8217;|&#039;/g, "'").replace(/&amp;/g, '&').replace(/&#\d+;/g, ' ') });
    });
    posts = rankMatches(cands, query, function (c) { return c.title; }).map(function (c) { return c.href; })
      .filter(function (h, n, a) { return a.indexOf(h) === n; });
  }
  var out = [];
  for (var k = 0; k < posts.length && !out.length; k++) {
    var d = await http(posts[k], { headers: { 'User-Agent': UA }, timeout: 15000 });
    var btns = [];
    (d.text || '').replace(/<a[^>]*href="([^"]*mobilejsr[^"]*)"[^>]*>([\s\S]*?)<\/a>/g, function (m, href, inner) {
      var label = strip(inner);
      if (!/screenshot|screenshoot/i.test(label)) btns.push({ href: href, label: label });
    });
    await Promise.all(btns.map(async function (b) {
      var hrefs = await unlock(b.href);
      await Promise.all(hrefs.map(async function (h) {
        var host = hostOf(h);
        try {
          if (/^hubcloud|^vcloud/.test(host)) await hubcloud(h, b.label, out);
        } catch (e) {}
      }));
    }));
  }
  return out;
}
