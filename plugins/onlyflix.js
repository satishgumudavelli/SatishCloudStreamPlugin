// VyseTV plugin ported from the CloudStream provider "OnlyflixProvider" (onlyflix.to).
// Chain: guess the title page on onlyflix.to -> admin-ajax "mcp_get_available_players" ->
// the "Nontongo" player (sv2.nontongo.stream/soap/...) -> its new-art.php page -> direct m3u8 relays.
// Skipped (as unresolvable without a browser): vidapi.xyz, vidfast.vc, CDNM, Balance, and the
// Peachify/Videasy mirrors (their APIs are dead or need the Android-only crypto path).

let DOMAINS = ['https://onlyflix.to'];
const UA = 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36';

const slugify = (s) => String(s || '').toLowerCase().replace(/&/g, ' and ').replace(/['’.]/g, '')
  .replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '');
const get = (url, headers) => http(url, { headers: Object.assign({ 'User-Agent': UA }, headers || {}), timeout: 12000 });
const attr = (html, name) => (new RegExp(name + '="([^"]*)"').exec(html) || [])[1] || '';

// Open the title page by guessing its slug (WordPress redirects /movies/slug/ to the real url),
// then confirm title (+ year) on the page. onlyflix has no working search endpoint.
async function findPage(base, q) {
  const names = titleList(q).slice(0, 4);
  const kind = q.isTv ? 'series' : 'movies';
  for (const name of names) {
    const slugs = [slugify(name)];
    if (q.year) slugs.push(slugify(name) + '-' + q.year);
    for (const slug of slugs) {
      const r = await get(base + '/' + kind + '/' + slug + '/');
      if (r.status !== 200) continue;
      const title = (/<h1 class="mb-4">([^<]*)/.exec(r.text) || [])[1];
      const yr = (/Release Year<\/span>\s*<span[^>]*>(?:<a[^>]*>)?\s*(\d{4})/.exec(r.text) || [])[1];
      if (!title || !titleScore(title.replace(/&#8217;|&#039;/g, "'").replace(/&amp;/g, '&').trim(), q)) continue;
      if (!q.isTv && q.year && yr && Math.abs(+yr - +q.year) > 1) continue;
      return r;
    }
  }
  return null;
}

// For a series page, pick the episode permalink out of the embedded allSeasonsData JSON.
async function episodePage(page, q) {
  const m = /allSeasonsData\s*=\s*(\{.*\});\s*$/m.exec(page.text);
  if (!m) return null;
  let all;
  try { all = JSON.parse(m[1]); } catch (e) { return null; }
  const ep = (all[q.season || 1] || {})[q.episode || 1];
  if (!ep || !ep.permalink) return null;
  return get(ep.permalink);
}

// Ask the site's own AJAX for the embed-server list of a movie/episode page.
async function players(base, page) {
  const nonce = attr(page.text, 'data-player-nonce');
  const postId = attr(page.text, 'data-player-post-id');
  if (!nonce || !postId) return [];
  const type = attr(page.text, 'data-player-content-type') || 'movie';
  const ajax = attr(page.text, 'data-player-ajax-url') || base + '/wp-admin/admin-ajax.php';
  const r = await http(ajax, {
    method: 'POST',
    headers: { 'User-Agent': UA, Referer: base + '/', 'Content-Type': 'application/x-www-form-urlencoded' },
    body: 'action=mcp_get_available_players&nonce=' + nonce + '&post_id=' + postId + '&type=' + type,
    timeout: 12000,
  });
  try { return JSON.parse(r.text).data.players || []; } catch (e) { return []; }
}

// Nontongo: the soap page names an ArtPlayer page (cfg.servers[].movie_url/tv_url) whose
// `const sources = [...]` lists relay playlists (plain m3u8 URLs, no headers needed).
async function nontongo(embedUrl, quality, q) {
  const soap = await get(embedUrl, { Referer: 'https://onlyflix.to/' });
  const cfg = /const cfg = (\{.*\});/.exec(soap.text);
  if (!cfg) return [];
  let servers;
  try { servers = JSON.parse(cfg[1]).servers || []; } catch (e) { return []; }
  const out = [];
  for (const s of servers) {
    const u = (q.isTv ? s.tv_url : s.movie_url);
    if (!u) continue;
    const page = await get(u, { Referer: 'https://sv2.nontongo.stream/' });
    const src = /const sources\s*=\s*(\[.*\]);/.exec(page.text);
    if (!src) continue;
    let list;
    try { list = JSON.parse(src[1]); } catch (e) { continue; }
    for (const it of list) {
      if (!it.player_url || it.type === 'iframe') continue; // iframe entries are not direct streams
      out.push({
        url: it.player_url, type: 'hls', server: 'Nontongo', label: it.label,
        quality: /4K/i.test(it.label) ? '2160p' : /1080|HD/i.test(it.label + ' ' + quality) ? '1080p' : '720p',
      });
    }
  }
  return out;
}

async function getStreams(q) {
  DOMAINS = await siteDomains('onlyflix', DOMAINS); // domains.json first (the repository's), then this list
  for (const base of DOMAINS) {
    let page = await findPage(base, q);
    if (!page) continue; // not on this domain
    if (q.isTv) page = await episodePage(page, q);
    if (!page) return [];
    const out = [];
    for (const p of await players(base, page)) {
      if (/nontongo/i.test(p.name || '')) out.push(...await nontongo(p.url, p.quality, q));
    }
    return out;
  }
  return [];
}
