// A VyseTV plugin: defines getStreams(query) and returns the streams it found.
//
// query = { tmdbId, imdbId, type: 'movie'|'tv', isTv, title, originalTitle, year, season, episode }
// returns [{ url, type?: 'hls'|'dash'|'mp4', quality?, server?, label?, audio?, headers? }]
//
// http(url, { method, headers, body, timeout }) -> Promise of { status, url, headers, text }
// (headers are lower-case; `text` is the body. http() never throws: on a network error status is 0.)

async function getStreams(query) {
  // A real plugin would search a site by query.title / query.year and read the page, e.g.:
  //   const page = await http('https://example.com/search?q=' + encodeURIComponent(query.title));
  //   const m = /data-id="(\d+)"/.exec(page.text);
  return [
    {
      url: 'https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_4x3/bipbop_4x3_variant.m3u8',
      type: 'hls',
      quality: '480p',
      server: 'Demo',
    },
  ];
}
