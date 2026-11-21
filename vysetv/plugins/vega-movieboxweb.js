// VyseTV plugin ported from the CloudStream provider "MovieBoxWeb" (officialmoviebox.com).
// Chain: /newWeb/searchResult HTML -> /moviesDetail/<path> (Nuxt devalue payload: subject, dubs, seasons)
// -> /wefeed-h5api-bff/subject/play (mp4/hls/dash streams, one subject per dub language).

const DOMAINS = ['https://officialmoviebox.com']; // ponytail: add rotated domains from domains.json
const UA = 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36';
const HDR = { 'User-Agent': UA, Accept: 'application/json', Cookie: 'token=', 'x-client-info': '{"timezone":"Asia/Colombo"}', 'x-source': '' };

const unent = (s) => String(s || '').replace(/&amp;/g, '&').replace(/&#0?39;|&apos;/g, "'");
const get = (url, headers) => http(url, { headers: Object.assign({}, HDR, headers || {}), timeout: 12000 });

// Nuxt 3 devalue payload: flat array, numbers are indices into it.
function hydrate(a, i, seen) {
  if (typeof i !== 'number' || i < 0) return null;
  if (i in seen) return seen[i];
  const v = a[i];
  if (Array.isArray(v)) {
    if (typeof v[0] === 'string') { const r = hydrate(a, v[1], seen); seen[i] = r; return r; }
    const l = []; seen[i] = l; for (const k of v) l.push(hydrate(a, k, seen)); return l;
  }
  if (v && typeof v === 'object') {
    const m = {}; seen[i] = m; for (const k in v) m[k] = hydrate(a, v[k], seen); return m;
  }
  return v;
}
function findDetail(x, depth) {
  if (!x || typeof x !== 'object' || depth > 12) return null;
  if (!Array.isArray(x) && x.subject && x.resource) return x;
  for (const k in x) { const r = findDetail(x[k], depth + 1); if (r) return r; }
  return null;
}
async function detail(base, path) {
  const r = await get(base + '/moviesDetail/' + path);
  const m = /id="__NUXT_DATA__"[^>]*>([\s\S]*?)<\/script>/.exec(r.text);
  if (!m) return null;
  try { return findDetail(hydrate(JSON.parse(m[1]), 0, {}), 0); } catch (e) { return null; }
}

async function search(base, title) {
  const r = await get(base + '/newWeb/searchResult?keyword=' + encodeURIComponent(title));
  const out = [], re = /<a href="\/moviesDetail\/([^"]+)"[^>]*class="[^"]*card[^"]*"[\s\S]*?<h2[^>]*?(?:title="([^"]*)")?[^>]*>([^<]*)<\/h2>/g;
  let m;
  while ((m = re.exec(r.text))) out.push({ path: m[1], title: unent(m[2] || m[3]).replace(/\s*\[.*?\]\s*/g, ' ').trim() });
  return out;
}

async function getStreams(query) {
  const names = [query.title, query.originalTitle].filter(Boolean);
  for (const base of DOMAINS) {
    let det = null;
    const tried = {};
    for (const text of searchQueries(query)) {
      // series come as one card per season ("Breaking Bad S2"): rankMatches puts the requested season first
      const cards = rankMatches(await search(base, text), query, (c) => c.title).filter((c) => !tried[c.path]);
      for (const c of cards.slice(0, 4)) {
        tried[c.path] = 1;
        const d = await detail(base, c.path);
        if (!d) continue;
        const s = d.subject, isSeries = +s.subjectType === 2;
        const yr = parseInt(String(s.releaseDate || '').slice(0, 4), 10);
        if (isSeries !== !!query.isTv) continue;
        if (query.year && yr && Math.abs(yr - +query.year) > 1) continue;
        det = d; break;
      }
      if (det) break;
    }
    if (!det) continue;
    const s = det.subject;
    const dubs = (s.dubs || []).map((d) => ({ id: d.subjectId, path: d.detailPath, lang: d.lanName || 'Original' }));
    if (!dubs.length) dubs.push({ id: s.subjectId, path: s.detailPath, lang: 'Original' });
    const se = query.isTv ? (query.season || 1) : 0, ep = query.isTv ? (query.episode || 1) : 0;
    const out = [];
    await Promise.all(dubs.map(async (d) => {
      const ref = base + '/movies/' + d.path + '?id=' + d.id + '&type=%2Fmovie%2Fdetail&detailSe=' + (se || '') + '&detailEp=' + (se ? ep : '') + '&lang=en';
      const r = await get(base + '/wefeed-h5api-bff/subject/play?subjectId=' + d.id + '&detailPath=' + d.path + (se ? '&se=' + se + '&ep=' + ep : ''), { Referer: ref });
      let data; try { data = JSON.parse(r.text).data; } catch (e) { return; }
      if (!data) return;
      for (const key of ['streams', 'hls', 'dash']) {
        for (const o of data[key] || []) {
          const u = o.url;
          if (!u || o.vipLocked) continue;
          const fmt = String(o.format || '').toUpperCase(), bare = u.split('?')[0];
          const type = fmt === 'HLS' || /\.m3u8$/.test(bare) ? 'hls' : fmt === 'DASH' || /\.mpd$/.test(bare) ? 'dash' : 'mp4';
          const rs = String(o.resolutions || '');
          const h = Math.max.apply(null, rs.split(',').map((x) => parseInt(x, 10) || 0));
          out.push({ url: u, type, quality: h ? h + 'p' : 'auto', server: 'MovieBox', label: ('MovieBox ' + d.lang + ' ' + rs).trim(),
            audio: d.lang === 'Original' ? undefined : d.lang, headers: { Referer: base + '/', 'User-Agent': UA } });
        }
      }
    }));
    if (out.length) return out;
  }
  return [];
}
