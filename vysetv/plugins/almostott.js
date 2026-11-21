// VyseTV plugin ported from the CloudStream provider "AlmostOttProvider" (AlmostOttExtractor.kt).
// The site is keyed by TMDB id, so no search is needed: three JSON APIs are queried by tmdbId.
//   1. tgvidapi.lovable.app  -> master HLS links (multi-audio)
//   2. vidsync.pro           -> HLS proxy links
//   3. tgdl.lovable.app      -> download list (base64-wrapped file URLs; only direct hosts kept)

var SITE = 'https://almostott.aethoflix.world';
var TG = 'https://tgvid.lovable.app';
var PLAYER_H = { Origin: TG, Referer: TG + '/' };
var SITE_H = { Origin: SITE, Referer: SITE + '/' };

function qualityOf(s) {
  var m = /(\d{3,4})p/i.exec(s || '') || (/4k|uhd/i.test(s || '') ? [0, '2160'] : null);
  return m ? m[1] + 'p' : '';
}

async function getJson(url, headers) {
  var r = await http(url, { headers: headers, timeout: 15000 });
  if (r.status !== 200) return null;
  try { return JSON.parse(r.text); } catch (e) { return null; }
}

// Audio language(s) from a release name, Hindi first.
function audioOf(name) {
  var langs = ['Hindi', 'English', 'Tamil', 'Telugu', 'Malayalam', 'Kannada', 'Bengali', 'Punjabi',
    'Marathi', 'Japanese', 'Korean', 'Spanish'];
  var found = langs.filter(function (l) {
    return new RegExp('\\b' + l.slice(0, 3) + '[a-z]*\\b', 'i').test(name || '');
  });
  return found.join('+') || (/\bdual\b/i.test(name) ? 'Dual' : /\bmulti\b/i.test(name) ? 'Multi' : '');
}

// Presigned R2 URLs may be stale; drop them when X-Amz-Date + Expires has passed.
function expired(url) {
  var d = /[?&]X-Amz-Date=(\d{4})(\d\d)(\d\d)T(\d\d)(\d\d)(\d\d)Z/.exec(url);
  var t = /[?&]X-Amz-Expires=(\d+)/.exec(url);
  if (!d || !t) return false;
  var signed = Date.UTC(+d[1], +d[2] - 1, +d[3], +d[4], +d[5], +d[6]);
  return Date.now() > signed + t[1] * 1000;
}

async function tgApi(q, out) {
  var path = q.isTv ? '/api/tv/' + q.tmdbId + '/' + q.season + '/' + q.episode : '/api/m/' + q.tmdbId;
  var j = await getJson('https://tgvidapi.lovable.app' + path, PLAYER_H);
  ((j && j.links) || []).forEach(function (l) {
    if (l.type !== 'hls' || !/^https:/.test(l.url || '')) return;
    out.push({ url: l.url, type: 'hls', quality: qualityOf(l.quality) || l.quality || '',
      server: 'AlmostOTT ' + (l.server || 'TG'), audio: (l.audios || []).join('+') || undefined });
  });
}

async function vidsync(q, out) {
  var j = await getJson('https://vidsync.pro/api/stream?type=' + (q.isTv ? 'tv' : 'movie') + '&id=' + q.tmdbId +
    '&season=' + (q.season || 1) + '&episode=' + (q.episode || 1), PLAYER_H);
  var seen = {};
  ((j && j.sources) || []).forEach(function (s) {
    var p = s.url || '';
    if (p.indexOf('/api/') !== 0 || seen[p]) return;
    seen[p] = 1;
    var m = /[?&]data=([^&]+)/.exec(p), d;
    try { d = JSON.parse(decodeURIComponent(m[1])); } catch (e) { return; }
    if (d.streamType !== 'hls') return;
    out.push({ url: 'https://vidsync.pro' + p, type: 'hls', quality: qualityOf(d.quality) || d.quality || '',
      server: 'AlmostOTT vidsync ' + (s.server || ''), audio: d.audio ? String(d.audio).toUpperCase() : undefined });
  });
}

async function downloads(q, out) {
  var path = q.isTv ? '/tv/' + q.tmdbId + '/' + q.season + '/' + q.episode : '/movie/' + q.tmdbId;
  // v2 first, v1 only when v2 has nothing (as the site does).
  var j = await getJson('https://tgdl.lovable.app/api/v2' + path, SITE_H);
  var files = j && (j.downloads || (j.download_url ? [j] : []));
  if (!files || !files.length) {
    j = await getJson('https://tgdl.lovable.app/api' + path, SITE_H);
    files = (j && (j.downloads || (j.download_url ? [j] : []))) || [];
  }
  var seen = {};
  files.forEach(function (f) {
    var u = /[?&]u=([^&]+)/.exec(f.download_url || '');
    var real;
    try { real = u ? atob(u[1].replace(/-/g, '+').replace(/_/g, '/')) : f.download_url; } catch (e) { return; }
    if (!/^https:/.test(real) || seen[real]) return;
    seen[real] = 1;
    var host = (/^https:\/\/([^\/?]+)/.exec(real) || [])[1] || '';
    var hls = /\.m3u8(\?|$)/i.test(real);
    // Only hosts that serve the file directly; hubcloud/hubdrive etc. are interstitial pages.
    var direct = /(pixeldrain\.(dev|com)|\.r2\.dev)$/.test(host) || /\.r2\.cloudflarestorage\.com$/.test(host);
    if (!hls && !direct) return;
    if (/\.r2\.cloudflarestorage\.com$/.test(host) && expired(real)) return;
    out.push({ url: real, type: hls ? 'hls' : 'mp4', quality: qualityOf(f.quality) || qualityOf(f.name),
      server: 'AlmostOTT ' + ((f.server && f.server !== 'null') ? f.server : host),
      label: f.size && f.size !== 'null' ? f.size : undefined, audio: audioOf(f.name) || undefined });
  });
}

async function getStreams(query) {
  if (!query.tmdbId) return [];
  var q = { tmdbId: query.tmdbId, isTv: !!query.isTv, season: query.season || 1, episode: query.episode || 1 };
  var out = [];
  await Promise.all([tgApi(q, out), vidsync(q, out), downloads(q, out)]);
  return out;
}
