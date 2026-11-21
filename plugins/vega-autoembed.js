// VyseTV plugin ported from the CloudStream provider "AutoEmbed" (movy.sx / api.wecollege.net).
// Chain: GET /seed?mediaId=<tmdb> -> GET /<server>/sources (payload XOR'd with a seed-derived keystream, "mvm1" magic) -> m3u8/mp4.
// Skipped: cinejoy and vidstuck embeds (not ported in the Kotlin either).

const API = 'https://api.wecollege.net';
const ORIGIN = 'https://www.movy.sx';
const UA = 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36';
const HDR = { 'User-Agent': UA, Origin: ORIGIN, Referer: ORIGIN + '/' };
const SERVERS = { miami: 'Miami', boise: 'Boise', atlanta: 'Atlanta', austin: 'Austin', delhi: 'Delhi', phoenix: 'Phoenix', dallas: 'Dallas', vegas: 'Vegas' };
const K = -0x61c88647;

const scr = (x) => {
  x ^= x >>> 16; x = Math.imul(x, -0x7a143595);
  x ^= x >>> 13; x = Math.imul(x, -0x3d4d51cb);
  return x ^ (x >>> 16);
};
const rotl = (x, k) => (k &= 31) ? ((x << k) | (x >>> (32 - k))) : x; // NB: the Kotlin used rotateRight, which is wrong

function decode(cipher, seed, mediaId) {
  let b64 = cipher.trim().replace(/-/g, '+').replace(/_/g, '/');
  while (b64.length % 4) b64 += '=';
  const bin = atob(b64);
  const raw = new Array(bin.length);
  for (let i = 0; i < bin.length; i++) raw[i] = bin.charCodeAt(i);
  const s = new Array(61).fill(0), has = new Array(61).fill(false);
  let h = -0x7ee3623b;
  for (let i = 0; i < seed.length; i++) h = Math.imul(h ^ seed.charCodeAt(i), 0x1000193);
  let n = scr(scr(h) ^ scr(mediaId ^ K));
  for (let e = 0; e < 8; e++) {
    const a = (n >>> 0) % 61;
    n = rotl((n + K) | 0, 7 + (7 & e));
    s[a] = n ^ scr(n); has[a] = true;
    n = scr((n + a) | 0);
  }
  let acc = scr(-0x5a5a5a5b ^ n);
  const out = new Array(raw.length);
  let l = 0, i = 0;
  while (i < raw.length) {
    const d = (acc >>> 0) % 61;
    const r = has[d] ? s[d] : 0;
    const c = Math.imul(K, l + 1);
    let b = (acc ^ (r ^ c)) | (acc & (r ^ c) & (has[d] ? -1 : 0));
    b = rotl((b + acc) | 0, d & 31) ^ rotl(acc, (d * 7) & 31);
    acc = scr((b + K) | 0);
    s[d] = acc; has[d] = true; l++;
    for (let k = 0; k < 4 && i < raw.length; k++, i++) out[i] = (raw[i] ^ (acc >>> (8 * k))) & 255;
  }
  if (!(out.length > 4 && out[0] === 109 && out[1] === 118 && out[2] === 109 && out[3] === 49)) return null;
  let str = '';
  for (let j = 4; j < out.length; j++) str += '%' + (out[j] < 16 ? '0' : '') + out[j].toString(16);
  try { return decodeURIComponent(str); } catch (e) { return null; }
}

const qualOf = (q) => { const m = /(\d{3,4})/.exec(q || ''); return m ? m[1] + 'p' : (/4k/i.test(q || '') ? '2160p' : 'auto'); };

async function getStreams(query) {
  const tmdb = query.tmdbId;
  if (!tmdb || tmdb === '0') return [];
  const sr = await http(API + '/seed?mediaId=' + tmdb, { headers: HDR, timeout: 12000 });
  let seed;
  try { seed = JSON.parse(sr.text).seed; } catch (e) { return []; }
  if (!seed) return [];
  const t = encodeURIComponent(encodeURIComponent(query.title || ''));
  const q = 'title=' + t + '&mediaType=' + (query.isTv ? 'tv' : 'movie') + '&year=' + (query.year || '') + '&tmdbId=' + tmdb +
    '&enc=2&seed=' + seed + '&imdbId=' + (query.imdbId || '') + (query.isTv ? '&seasonId=' + (query.season || 1) + '&episodeId=' + (query.episode || 1) : '');
  const out = [];
  await Promise.all(Object.keys(SERVERS).map(async (id) => {
    try {
      const r = await http(API + '/' + id + '/sources?' + q, { headers: HDR, timeout: 15000 });
      if (r.status !== 200) return;
      const txt = decode(r.text, seed, parseInt(tmdb, 10));
      if (!txt) return;
      const j = JSON.parse(txt);
      const list = j.playlist ? [[j.playlist, '']] : (j.sources || []).map((x) => [x.url, x.quality]);
      for (const [u, ql] of list) {
        if (!u) continue;
        out.push({ url: u, type: /\.m3u8/.test(u) ? 'hls' : 'mp4', quality: qualOf(ql), server: 'Movy ' + SERVERS[id],
          label: 'Movy - ' + SERVERS[id] + (ql ? ' (' + ql + ')' : ''), audio: id === 'delhi' ? 'Hindi' : undefined, headers: HDR });
      }
    } catch (e) { console.log(id + ' failed: ' + e); }
  }));
  return out;
}
