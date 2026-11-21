// VyseTV plugin ported from the CloudStream PixelflixProvider.
// pixelflix.cc ids are TMDB ids, so no site search is needed: the Kotlin provider calls the
// embed backend embed.reelsdownload.online (native extract APIs) with tmdb/imdb ids.
// Not ported: the vidbolt.xyz scrapers (FastVa, Quasar, ... Ninetta) - that API now answers 403/404.

const BASE = 'https://embed.reelsdownload.online/api';
const UA = 'Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36';

// Endpoints and their query shapes, as in PixelflixExtractor.kt.
function endpoints(q) {
  const tv = q.type === 'tv' || q.isTv;
  const common = 'type=' + (tv ? 'tv' : 'movie') +
    (tv ? '&season=' + (q.season || 1) + '&episode=' + (q.episode || 1) : '');
  const ty = '&title=' + encodeURIComponent(q.title || '') + (q.year ? '&year=' + q.year : '');
  const ids = '&tmdb_id=' + q.tmdbId + (q.imdbId ? '&imdb_id=' + q.imdbId : '');
  return [
    ['Redflix', 'redflix-extract?' + common + '&tmdb_id=' + q.tmdbId],
    ['Cinemaos', 'cinemaos-extract?' + common + ids + ty],
    ['Moviebox', 'moviebox?' + common + ids + ty],
  ];
}

// Responses are either NDJSON ({"source":{..}} per line) or one JSON ({"sources":[..]}).
function parseSources(text) {
  const out = [];
  const lines = text.split('\n');
  for (const line of lines) {
    let j;
    try { j = JSON.parse(line); } catch (e) { continue; }
    if (j.source) out.push(j.source);
    if (j.sources) for (const s of j.sources) out.push(s);
  }
  return out;
}

async function getStreams(query) {
  if (!query.tmdbId) return [];
  const results = await Promise.all(endpoints(query).map(async ([name, path]) => {
    const r = await http(BASE + '/' + path, { timeout: 15000, headers: { 'User-Agent': UA } });
    if (r.status !== 200) return [];
    return parseSources(r.text)
      .filter((s) => s.url && s.verified !== false) // unverified mirrors are slow/dead
      .map((s) => {
        const url = s.url.charAt(0) === '/' ? 'https://embed.reelsdownload.online' + s.url : s.url;
        const t = String(s.type || '').toLowerCase();
        const label = s.label || '';
        const lang = /-\s*(.+)$/.exec(label);
        return {
          url,
          type: t === 'dash' || t === 'mpd' ? 'dash' : t === 'hls' || t === 'm3u8' ? 'hls' : 'mp4',
          quality: s.quality || '',
          server: 'Pixelflix ' + (s.scraperName || name),
          label,
          audio: lang ? lang[1] : undefined,
          headers: s.headers || undefined,
        };
      });
  }));
  const seen = {};
  return [].concat(...results).filter((s) => !seen[s.url] && (seen[s.url] = 1));
}
