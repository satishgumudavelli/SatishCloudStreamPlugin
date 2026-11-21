// VyseTV plugin ported from the CloudStream provider "GokuHD" (gokuhd.nl, Hindi-dubbed anime).
// Chain: filter the newest pages of /series/ and /movies/ by title (site search sits behind a Cloudflare
// challenge) -> post -> nexdrive page(s) -> fastdl.beer embed -> direct googleusercontent mp4.
// Skipped (honest): vcloud/vegadrive/filebee (filebee API is dead)/1fichier/megaup/vikingfile/gofile.
// Limit: only titles on the newest 6 pages of each list are findable.

const BASE = 'https://gokuhd.nl'; // ponytail: add rotated domains from domains.json here
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';
const get = (url) => http(url, { headers: { 'User-Agent': UA, Referer: BASE + '/' }, timeout: 12000 });

const ent = (s) => String(s).replace(/&#8211;|&#8212;/g, '-').replace(/&#8217;|&#039;|&#8216;/g, "'").replace(/&#038;|&amp;/g, '&').replace(/&#\d+;/g, '');
const norm = (s) => ent(s).toLowerCase().replace(/&/g, 'and').replace(/[^a-z0-9]+/g, '');
const clean = (raw) => ent(raw).replace(/\s*(\(|\[|\||WEB-DL|Blu-?Ray|Hindi Dubbed|Dual Audio).*$/i, '').trim();
const strip = (h) => ent(h.replace(/<[^>]*>/g, ' ')).replace(/\s+/g, ' ').trim();

async function listPosts(kind) {
  const pages = await Promise.all([1, 2, 3, 4, 5, 6].map((p) => get(BASE + '/' + kind + '/' + (p > 1 ? 'page/' + p + '/' : ''))));
  const out = [], re = /<h2 class="entry-title"[^>]*>\s*<a href="([^"]+)"[^>]*>([^<]*)/g;
  for (const r of pages) { let m; while ((m = re.exec(r.text))) out.push({ url: m[1], raw: ent(m[2]) }); }
  return out;
}

// Does a series post title ("Season 1 - 18", "Season 1 & 2", "Season 3") cover season n?
function coversSeason(raw, n) {
  const m = /seasons?\s*0?(\d+)(?:\s*(?:-|&|and|to)\s*0?(\d+))?/i.exec(raw);
  if (!m) return n === 1;
  const a = +m[1], b = m[2] ? +m[2] : a;
  return n >= Math.min(a, b) && n <= Math.max(a, b);
}

const fastdl = async (url) => {
  const r = await get(url);
  const reurl = (/var reurl\s*=\s*"([^"]+)"/.exec(r.text) || [])[1];
  if (!reurl) return null;
  const link = (/[?&]link=(.+)$/.exec(reurl) || [])[1];
  let u = reurl;
  if (link) { try { u = decodeURIComponent(link); } catch (e) { u = link; } }
  return /^https?:/.test(u) && !/\.zip(\?|$)/i.test(u) ? u : null;
};
const fastdlLinks = (html) => (html.match(/https?:\/\/fastdl\.[a-z]+\/embed\.php\?download=[\w-]+/g) || []);

const qual = (h) => { const m = /(2160|1080|720|480)p?/i.exec(h) || /(4k)/i.exec(h); return m ? (/4k/i.test(m[1]) ? '2160p' : m[1] + 'p') : 'auto'; };

async function getStreams(query) {
  const isTv = !!query.isTv, season = query.season || 1, episode = query.episode || 1;
  const names = [query.title, query.originalTitle].filter(Boolean).map(norm);
  const posts = (await listPosts(isTv ? 'series' : 'movies')).filter((p) => {
    const c = clean(p.raw), cn = norm(c), head = norm(c.split(':')[0]);
    if (!names.some((n) => n === cn || n === head)) return false;
    if (isTv) return coversSeason(p.raw, season);
    const y = (/\((\d{4})\)/.exec(p.raw) || [])[1];
    return !(query.year && y && Math.abs(+y - +query.year) > 1);
  });
  if (!posts.length) return [];
  const html = (await get(posts[0].url)).text;
  // quality heading (h4) -> its nexdrive buttons
  const groups = [];
  for (const chunk of html.split('<h4').slice(1)) {
    const head = strip(chunk.split('</h4>')[0]);
    const links = (chunk.split('</h4>').slice(1).join('').match(/https?:\/\/nexdrive\.[a-z]+\/[\w-]+\//g) || []);
    if (links.length) groups.push({ head, links });
  }
  const anySeasonHead = groups.some((g) => /season/i.test(g.head));
  const wanted = groups.filter((g) => !isTv || !anySeasonHead || new RegExp('season\\s*0?' + season + '\\b', 'i').test(g.head))
    .filter((g) => !/batch|zip/i.test(g.head)).slice(0, 4);
  const out = [], seen = {};
  const push = async (g, link) => {
    const u = await fastdl(link);
    if (!u || seen[u]) return;
    seen[u] = 1;
    out.push({ url: u, type: 'mp4', quality: qual(g.head), server: 'GokuHD', label: 'GokuHD ' + g.head.slice(0, 60), audio: 'Hindi' });
  };
  await Promise.all(wanted.map(async (g) => {
    for (const nd of g.links.slice(0, 2)) {
      const page = (await get(nd)).text;
      if (!isTv) { for (const l of fastdlLinks(page)) await push(g, l); continue; }
      // episodes: "Episode: N" headings (absolute numbering); take the n-th of this season group
      const eps = page.split('<h4').slice(1).map((c) => ({ n: +((/episodes?[:\s-]*(\d+)/i.exec(strip(c.split('</h4>')[0])) || [])[1]), links: fastdlLinks(c) }))
        .filter((e) => e.n && e.links.length).sort((a, b) => a.n - b.n);
      const hit = eps.find((e) => e.n === episode && !anySeasonHead && season === 1) || eps[episode - 1];
      if (hit) for (const l of hit.links) await push(g, l);
    }
  }));
  return out;
}
