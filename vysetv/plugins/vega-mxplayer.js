// VyseTV plugin ported from the CloudStream provider "MxPlayer" (mxplayer.in, free catalogue).
// Chain: api.mxplayer.in search (movies carry their HLS in the card; shows -> collection -> season tabs -> episodes).
// Only non-DRM HLS is used. The catalogue is mostly Indian/free content: many titles are simply absent.

const API = 'https://api.mxplayer.in/v1/web';
const CDN = 'https://d3sgzbosmwirao.cloudfront.net/';
const SITE = 'https://www.mxplayer.in';
const UA = 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36';
const HDR = { 'User-Agent': UA, Referer: SITE + '/', Origin: SITE, 'Content-Type': 'application/json' };
const UID = 'a1b2c3d4-' + Math.floor(Math.random() * 1e8).toString(16) + '-4e5f-8a9b-0c1d2e3f4a5b';
const END = '&device-density=2&userid=' + UID + '&platform=com.mxplay.desktop&content-languages=hi,en&kids-mode-enabled=false';

const norm = (s) => String(s || '').toLowerCase().replace(/\s*\((hindi|tamil|telugu|english)[^)]*\)\s*$/, '').replace(/&/g, 'and').replace(/[^a-z0-9]+/g, '');
const fix = (u) => (/^http/.test(u) ? u : CDN + u);
const val = (u) => (u && u !== 'null' ? u : null);

function hls(it) {
  const s = it && it.stream;
  if (!s || s.drmProtect) return null;
  const u = val(s.mxplay && s.mxplay.hls && s.mxplay.hls.high) || val(s.hls && s.hls.high) || val(s.thirdParty && s.thirdParty.hlsUrl);
  return u ? fix(u) : null;
}
const getJ = async (url, o) => { const r = await http(url, Object.assign({ headers: HDR, timeout: 12000 }, o || {})); try { return JSON.parse(r.text); } catch (e) { return {}; } };

async function getStreams(query) {
  const names = [query.title, query.originalTitle].filter(Boolean);
  const want = query.isTv ? 'tvshow' : 'movie';
  for (const name of names) {
    const j = await getJ(API + '/search/resultv2?query=' + encodeURIComponent(name) + END, { method: 'POST', body: '{}' });
    const items = [].concat.apply([], (j.sections || []).map((s) => s.items || []));
    const it = items.find((i) => i.type === want && norm(i.title) === norm(name) &&
      (!query.year || !i.releaseDate || Math.abs(parseInt(String(i.releaseDate).slice(0, 4), 10) - +query.year) <= 1));
    if (!it) continue;
    const mk = (u) => [{ url: u, type: 'hls', quality: 'auto', server: 'MX Player', label: 'MX Player', headers: { Referer: SITE + '/', 'User-Agent': UA } }];
    if (!query.isTv) { const u = hls(it); return u ? mk(u) : []; }
    const d = await getJ(API + '/detail/collection?type=tvshow&id=' + it.id + END);
    const tab = (d.tabs || []).find((t) => t.type === 'tvshowepisodes');
    for (const s of (tab && tab.containers) || []) {
      if ((s.sequence || 1) !== (query.season || 1)) continue;
      let next = '', guard = 0;
      while (next != null && guard++ < 20) {
        const e = await getJ(API + '/detail/tab/tvshowepisodes?type=season&' + next + '&id=' + s.id + '&sortOrder=0' + END);
        for (const ep of e.items || []) {
          if (ep.sequence === (query.episode || 1)) { const u = hls(ep); return u ? mk(u) : []; }
        }
        next = val(e.next);
      }
    }
    return [];
  }
  return [];
}
