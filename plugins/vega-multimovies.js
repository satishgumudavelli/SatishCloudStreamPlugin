// VyseTV plugin ported from the CloudStream provider "MultiMovies (Vega)" (MultiMovies.kt).
// multimovies.* is a TMDB-id aggregator whose "vidrift" server is embed.vidrift.net: its /api/boot endpoint lists the
// Orion HLS masters (one per audio track). Keyed by tmdbId, so no site search is needed.

var EMBED = 'https://embed.vidrift.net';
var PARENT = 'https://multimovies.garden';
var UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36';

async function getStreams(query) {
  if (!query.tmdbId) return [];
  var path = query.isTv
    ? 'tv/' + query.tmdbId + '/' + (query.season || 1) + '/' + (query.episode || 1)
    : 'movie/' + query.tmdbId;
  var r = await http(EMBED + '/api/boot/' + path, {
    headers: { 'x-embed-parent': PARENT, Referer: PARENT + '/' }, timeout: 15000 });
  var meta;
  try { meta = JSON.parse(r.text).meta; } catch (e) { return []; }
  var out = [];
  ((meta && meta.orionStreams) || []).forEach(function (s) {
    if (s.type !== 'hls' || !s.url) return;
    var label = String(s.name || '').split(' · ')[1] || '';
    var top = 0;
    (s.rungs || []).forEach(function (g) { top = Math.max(top, g.height || 0); });
    out.push({ url: EMBED + s.url, type: 'hls', quality: top ? top + 'p' : '', server: 'MultiMovies Vidrift',
      audio: label || undefined, headers: { Referer: EMBED + '/', 'User-Agent': UA } });
  });
  return out;
}
