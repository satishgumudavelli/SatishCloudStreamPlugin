// VyseTV plugin: CinemaOS
// Ported from the CloudStream provider CinemaOsProvider (CinemaOsExtractor.kt, invokeCinemaos).
// The site's current API is /api/relay/scrape?scraper=CODE (the old providerv5 path is gone): the
// request is signed with a double HMAC-SHA256 and a per-page `scrapeToken` (sent as `_st`); the reply
// is AES-256-GCM encrypted with a PBKDF2 key, so all crypto is implemented in pure JS below.
// Known limit: PBKDF2 (100k rounds) is slow in the plugin engine (a few seconds per reply), so replies
// are decrypted in arrival order until a time budget runs out.

const DOMAINS = ['cinemaos.in', 'cinemaos.tech', 'cinemaos.me', 'cinemaos.live', 'cinemaos-v3.vercel.app'];
const SCRAPERS = ['mb2', 'vn', 's3', 'q4']; // codes that return playable data (z2 = Rive: slow, mostly 403 proxies, dropped)
const PRIMARY_KEY = 'a7f3b9c2e8d4f1a6b5c9e2d7f4a8b3c6e1d9f7a4b2c8e5d3f9a6b4c1e7d2f8a5';
const SECONDARY_KEY = 'd3f8a5b2c9e6d1f7a4b8c5e2d9f3a6b1c7e4d8f2a9b5c3e7d4f1a8b6c2e9d5f3';
const ENC_KEY_HEX = 'a1b2c3d4e4f6477658455678901477567890abcdef1234567890abcdef123456';
const START_BUDGET_MS = 10000; // do not start a decrypt after this much time has passed

// ---- pure-JS crypto: SHA-256, HMAC, PBKDF2 (fast path), AES-256-CTR (GCM payload) ----
const K256 = new Int32Array(64);
(function () { // first 32 bits of the fractional parts of cube roots of the first 64 primes
  let n = 2, i = 0;
  while (i < 64) {
    let p = true;
    for (let d = 2; d * d <= n; d++) if (n % d === 0) { p = false; break; }
    if (p) { K256[i++] = Math.floor((Math.cbrt(n) % 1) * 4294967296) | 0; }
    n++;
  }
})();
const W = new Int32Array(64);
const IV0 = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19].map((x) => x | 0);

// One SHA-256 compression of block w[0..15] into state s (in place). Rounds unrolled by 8 (no variable shuffling).
function compress(s, w) {
  let i = 0;
  for (; i < 16; i++) W[i] = w[i];
  for (; i < 64; i++) {
    const a = W[i - 15], b = W[i - 2];
    W[i] = (((a >>> 7 | a << 25) ^ (a >>> 18 | a << 14) ^ (a >>> 3)) + W[i - 16] + ((b >>> 17 | b << 15) ^ (b >>> 19 | b << 13) ^ (b >>> 10)) + W[i - 7]) | 0;
  }
  let a = s[0], b = s[1], c = s[2], d = s[3], e = s[4], f = s[5], g = s[6], h = s[7], t;
  for (i = 0; i < 64; i += 8) {
    t = h + (((e >>> 6 | e << 26) ^ (e >>> 11 | e << 21) ^ (e >>> 25 | e << 7)) + ((e & f) ^ (~e & g)) + K256[i + 0] + W[i + 0]) | 0;
    d = (d + t) | 0;
    h = (t + (((a >>> 2 | a << 30) ^ (a >>> 13 | a << 19) ^ (a >>> 22 | a << 10)) + ((a & b) ^ (a & c) ^ (b & c)))) | 0;
    t = g + (((d >>> 6 | d << 26) ^ (d >>> 11 | d << 21) ^ (d >>> 25 | d << 7)) + ((d & e) ^ (~d & f)) + K256[i + 1] + W[i + 1]) | 0;
    c = (c + t) | 0;
    g = (t + (((h >>> 2 | h << 30) ^ (h >>> 13 | h << 19) ^ (h >>> 22 | h << 10)) + ((h & a) ^ (h & b) ^ (a & b)))) | 0;
    t = f + (((c >>> 6 | c << 26) ^ (c >>> 11 | c << 21) ^ (c >>> 25 | c << 7)) + ((c & d) ^ (~c & e)) + K256[i + 2] + W[i + 2]) | 0;
    b = (b + t) | 0;
    f = (t + (((g >>> 2 | g << 30) ^ (g >>> 13 | g << 19) ^ (g >>> 22 | g << 10)) + ((g & h) ^ (g & a) ^ (h & a)))) | 0;
    t = e + (((b >>> 6 | b << 26) ^ (b >>> 11 | b << 21) ^ (b >>> 25 | b << 7)) + ((b & c) ^ (~b & d)) + K256[i + 3] + W[i + 3]) | 0;
    a = (a + t) | 0;
    e = (t + (((f >>> 2 | f << 30) ^ (f >>> 13 | f << 19) ^ (f >>> 22 | f << 10)) + ((f & g) ^ (f & h) ^ (g & h)))) | 0;
    t = d + (((a >>> 6 | a << 26) ^ (a >>> 11 | a << 21) ^ (a >>> 25 | a << 7)) + ((a & b) ^ (~a & c)) + K256[i + 4] + W[i + 4]) | 0;
    h = (h + t) | 0;
    d = (t + (((e >>> 2 | e << 30) ^ (e >>> 13 | e << 19) ^ (e >>> 22 | e << 10)) + ((e & f) ^ (e & g) ^ (f & g)))) | 0;
    t = c + (((h >>> 6 | h << 26) ^ (h >>> 11 | h << 21) ^ (h >>> 25 | h << 7)) + ((h & a) ^ (~h & b)) + K256[i + 5] + W[i + 5]) | 0;
    g = (g + t) | 0;
    c = (t + (((d >>> 2 | d << 30) ^ (d >>> 13 | d << 19) ^ (d >>> 22 | d << 10)) + ((d & e) ^ (d & f) ^ (e & f)))) | 0;
    t = b + (((g >>> 6 | g << 26) ^ (g >>> 11 | g << 21) ^ (g >>> 25 | g << 7)) + ((g & h) ^ (~g & a)) + K256[i + 6] + W[i + 6]) | 0;
    f = (f + t) | 0;
    b = (t + (((c >>> 2 | c << 30) ^ (c >>> 13 | c << 19) ^ (c >>> 22 | c << 10)) + ((c & d) ^ (c & e) ^ (d & e)))) | 0;
    t = a + (((f >>> 6 | f << 26) ^ (f >>> 11 | f << 21) ^ (f >>> 25 | f << 7)) + ((f & g) ^ (~f & h)) + K256[i + 7] + W[i + 7]) | 0;
    e = (e + t) | 0;
    a = (t + (((b >>> 2 | b << 30) ^ (b >>> 13 | b << 19) ^ (b >>> 22 | b << 10)) + ((b & c) ^ (b & d) ^ (c & d)))) | 0;
  }
  s[0] = (s[0] + a) | 0; s[1] = (s[1] + b) | 0; s[2] = (s[2] + c) | 0; s[3] = (s[3] + d) | 0;
  s[4] = (s[4] + e) | 0; s[5] = (s[5] + f) | 0; s[6] = (s[6] + g) | 0; s[7] = (s[7] + h) | 0;
}

// SHA-256 of a byte array, continuing from a state that already absorbed `prefix` bytes.
function sha256(bytes, state, prefix) {
  const s = (state || IV0).slice(), n = bytes.length, total = n + 8 + 1, blocks = Math.ceil(total / 64);
  const buf = new Uint8Array(blocks * 64);
  buf.set(bytes); buf[n] = 0x80;
  const bits = (n + (prefix || 0)) * 8;
  buf[buf.length - 4] = bits >>> 24; buf[buf.length - 3] = bits >>> 16; buf[buf.length - 2] = bits >>> 8; buf[buf.length - 1] = bits;
  const w = new Int32Array(16);
  for (let o = 0; o < buf.length; o += 64) {
    for (let i = 0; i < 16; i++) w[i] = buf[o + i * 4] << 24 | buf[o + i * 4 + 1] << 16 | buf[o + i * 4 + 2] << 8 | buf[o + i * 4 + 3];
    compress(s, w);
  }
  return s;
}
const words2bytes = (s) => { const o = new Uint8Array(32); for (let i = 0; i < 8; i++) { o[i * 4] = s[i] >>> 24; o[i * 4 + 1] = s[i] >>> 16; o[i * 4 + 2] = s[i] >>> 8; o[i * 4 + 3] = s[i]; } return o; };
const utf8 = (str) => { const u = unescape(encodeURIComponent(str)); const o = new Uint8Array(u.length); for (let i = 0; i < u.length; i++) o[i] = u.charCodeAt(i); return o; };
const hex2bytes = (h) => { const o = new Uint8Array(h.length / 2); for (let i = 0; i < o.length; i++) o[i] = parseInt(h.substr(i * 2, 2), 16); return o; };
const bytes2hex = (b) => { let s = ''; for (let i = 0; i < b.length; i++) s += (b[i] < 16 ? '0' : '') + b[i].toString(16); return s; };

// HMAC pad states for a key (<= 64 bytes).
function hmacStates(key) {
  const ip = new Uint8Array(64).fill(0x36), op = new Uint8Array(64).fill(0x5c);
  for (let i = 0; i < key.length; i++) { ip[i] ^= key[i]; op[i] ^= key[i]; }
  const st = (p) => { const s = IV0.slice(), w = new Int32Array(16); for (let i = 0; i < 16; i++) w[i] = p[i * 4] << 24 | p[i * 4 + 1] << 16 | p[i * 4 + 2] << 8 | p[i * 4 + 3]; compress(s, w); return s; };
  return { i: st(ip), o: st(op) };
}
const hmac = (key, msg) => { const h = hmacStates(key); return words2bytes(sha256(words2bytes(sha256(msg, h.i, 64)), h.o, 64)); };
const hmacHex = (key, msg) => bytes2hex(hmac(utf8(key), utf8(msg)));

// PBKDF2-HMAC-SHA256, one 32-byte block. Each iteration = 2 compressions on precomputed pad states.
function pbkdf2(pass, salt, iters) {
  const h = hmacStates(pass);
  const msg = new Uint8Array(salt.length + 4); msg.set(salt); msg[msg.length - 1] = 1;
  let u = sha256(msg, h.i, 64); // inner hash of salt||1
  u = sha256(words2bytes(u), h.o, 64);
  const t = u.slice(), blk = new Int32Array(16), s = new Int32Array(8);
  for (let n = 1; n < iters; n++) {
    blk.fill(0); for (let i = 0; i < 8; i++) blk[i] = u[i]; blk[8] = 0x80000000 | 0; blk[15] = 768;
    for (let i = 0; i < 8; i++) s[i] = h.i[i]; compress(s, blk);
    blk.fill(0); for (let i = 0; i < 8; i++) blk[i] = s[i]; blk[8] = 0x80000000 | 0; blk[15] = 768;
    for (let i = 0; i < 8; i++) s[i] = h.o[i]; compress(s, blk);
    for (let i = 0; i < 8; i++) { u[i] = s[i]; t[i] ^= s[i]; }
  }
  return words2bytes(t);
}

// AES-256 block encryption (for CTR keystream).
const SBOX = new Uint8Array(256);
(function () { // generate S-box
  let p = 1, q = 1;
  do {
    p = p ^ ((p << 1) & 255) ^ (p & 128 ? 0x1b : 0);
    q ^= q << 1; q ^= q << 2; q ^= q << 4; q &= 255; if (q & 128) q ^= 0x09;
    const x = q ^ (q << 1 | q >> 7) ^ (q << 2 | q >> 6) ^ (q << 3 | q >> 5) ^ (q << 4 | q >> 4);
    SBOX[p] = (x ^ 0x63) & 255;
  } while (p !== 1);
  SBOX[0] = 0x63;
})();
const xt = (x) => ((x << 1) ^ (x & 128 ? 0x1b : 0)) & 255;
function aesExpand(key) { // 32-byte key -> 15 round keys (240 bytes)
  const w = new Uint8Array(240); w.set(key);
  let rc = 1;
  for (let i = 32; i < 240; i += 4) {
    let t = [w[i - 4], w[i - 3], w[i - 2], w[i - 1]];
    if (i % 32 === 0) { t = [SBOX[t[1]] ^ rc, SBOX[t[2]], SBOX[t[3]], SBOX[t[0]]]; rc = xt(rc); }
    else if (i % 32 === 16) t = t.map((x) => SBOX[x]);
    for (let j = 0; j < 4; j++) w[i + j] = w[i - 32 + j] ^ t[j];
  }
  return w;
}
function aesBlock(rk, inp) {
  const s = new Uint8Array(16);
  for (let i = 0; i < 16; i++) s[i] = inp[i] ^ rk[i];
  const t = new Uint8Array(16);
  for (let r = 1; r <= 14; r++) {
    for (let i = 0; i < 16; i++) t[i] = SBOX[s[i]];
    // shift rows
    const u = new Uint8Array(16);
    for (let c = 0; c < 4; c++) for (let rw = 0; rw < 4; rw++) u[c * 4 + rw] = t[((c + rw) % 4) * 4 + rw];
    if (r < 14) {
      for (let c = 0; c < 4; c++) {
        const a0 = u[c * 4], a1 = u[c * 4 + 1], a2 = u[c * 4 + 2], a3 = u[c * 4 + 3];
        const x = a0 ^ a1 ^ a2 ^ a3;
        s[c * 4] = a0 ^ x ^ xt(a0 ^ a1); s[c * 4 + 1] = a1 ^ x ^ xt(a1 ^ a2);
        s[c * 4 + 2] = a2 ^ x ^ xt(a2 ^ a3); s[c * 4 + 3] = a3 ^ x ^ xt(a3 ^ a0);
      }
    } else s.set(u);
    for (let i = 0; i < 16; i++) s[i] ^= rk[r * 16 + i];
  }
  return s;
}
// GF(2^128) multiply (GCM bit order), 16-byte arrays.
function gmul(x, y) {
  const z = new Uint8Array(16), v = y.slice();
  for (let i = 0; i < 128; i++) {
    if (x[i >> 3] & (0x80 >> (i & 7))) for (let j = 0; j < 16; j++) z[j] ^= v[j];
    const lsb = v[15] & 1;
    for (let j = 15; j > 0; j--) v[j] = (v[j] >> 1) | ((v[j - 1] & 1) << 7);
    v[0] >>= 1;
    if (lsb) v[0] ^= 0xe1;
  }
  return z;
}
// AES-256-GCM decrypt (any IV length; 16 bytes here, so J0 = GHASH(IV)).
// ponytail: the auth tag is not verified (JSON.parse is the sanity check).
function gcmDecrypt(key, iv, ct) {
  const rk = aesExpand(key), out = new Uint8Array(ct.length);
  let ctr = new Uint8Array(16);
  if (iv.length === 12) { ctr.set(iv); ctr[15] = 1; }
  else { // J0 = GHASH_H(IV padded || 0^64 || bitlen)
    const h = aesBlock(rk, new Uint8Array(16)), blk = new Uint8Array(16), len = new Uint8Array(16);
    const pad = new Uint8Array(Math.ceil(iv.length / 16) * 16); pad.set(iv);
    len[14] = (iv.length * 8) >> 8; len[15] = (iv.length * 8) & 255;
    for (let o = 0; o < pad.length; o += 16) { for (let i = 0; i < 16; i++) blk[i] ^= pad[o + i]; blk.set(gmul(blk, h)); }
    for (let i = 0; i < 16; i++) blk[i] ^= len[i];
    ctr = gmul(blk, h);
  }
  for (let o = 0; o < ct.length; o += 16) {
    for (let i = 15; i >= 12; i--) { if (++ctr[i] < 256) break; ctr[i] = 0; }
    const ks = aesBlock(rk, ctr);
    for (let i = 0; i < 16 && o + i < ct.length; i++) out[o + i] = ct[o + i] ^ ks[i];
  }
  let s = ''; for (let i = 0; i < out.length; i++) s += String.fromCharCode(out[i]);
  return decodeURIComponent(escape(s));
}

const now = () => (typeof Date !== 'undefined' ? Date.now() : 0);
const qs = (o) => Object.keys(o).map((k) => k + '=' + encodeURIComponent(o[k])).join('&');

// Decrypt one {encrypted, cin, mao, salt, version} reply into an object (null on failure).
function decryptReply(d) {
  try {
    const key = d.salt && +d.version >= 1 ? pbkdf2(utf8(ENC_KEY_HEX), hex2bytes(d.salt), 100000) : hex2bytes(ENC_KEY_HEX);
    return JSON.parse(gcmDecrypt(key, hex2bytes(d.cin), hex2bytes(d.encrypted)));
  } catch (e) { return null; }
}

// Find a working domain and the page's scrapeToken.
async function openSite(type, id) {
  for (const d of DOMAINS) {
    const r = await http('https://' + d + '/watch/' + type + '/' + id, { timeout: 8000 });
    const m = r.status === 200 && /scrapeToken\\?":\\?"([^"\\]+)/.exec(r.text);
    if (m) return { base: 'https://' + d, token: m[1] };
  }
  return null;
}

function qualityOf(s, tag) {
  const q = tag || s.quality || '';
  if (/^\d+$/.test(q)) return q + 'p';
  return /^\d{3,4}p$/i.test(q) ? q : (/4k/i.test(q + s.server) ? '2160p' : (s.bitrate === 'FHD' ? '1080p' : ''));
}

async function getStreams(query) {
  if (!query.tmdbId || !query.imdbId) return [];
  const tv = query.type === 'tv';
  const site = await openSite(tv ? 'tv' : 'movie', query.tmdbId);
  if (!site) return [];
  const t0 = now();
  const parts = ['tmdbId:' + query.tmdbId, 'imdbId:' + query.imdbId];
  if (tv) parts.push('seasonId:' + (query.season || 1), 'episodeId:' + (query.episode || 1));
  const secret = hmacHex(SECONDARY_KEY, hmacHex(PRIMARY_KEY, parts.join('|')));
  const base = { type: tv ? 'tv' : 'movie', tmdbId: query.tmdbId, imdbId: query.imdbId, t: query.title || '', ry: query.year || '', secret: secret, _st: site.token };
  if (tv) { base.seasonId = query.season || 1; base.episodeId = query.episode || 1; }
  const out = [], seen = {};

  const add = (name, s, url, type, q) => {
    if (!url || seen[url]) return;
    seen[url] = 1;
    const t = type === 'hls' || /\.m3u8/i.test(url) ? 'hls' : type === 'dash' || /\.mpd/i.test(url) ? 'dash' : 'mp4';
    const st = { url: url, type: t, quality: qualityOf(s, q), server: 'CinemaOS', label: name + ' [' + (s.server || '') + ']' };
    if (s.headers) st.headers = s.headers;
    if (s.language) st.audio = s.language;
    out.push(st);
  };

  // all scrapers are fetched concurrently; each reply is decrypted as it arrives (CPU-serial)
  await Promise.all(SCRAPERS.map(async (sc) => {
    const r = await http(site.base + '/api/relay/scrape?' + qs(Object.assign({ scraper: sc }, base)), { timeout: 15000, headers: { Referer: site.base + '/watch/' + base.type + '/' + query.tmdbId } });
    let j; try { j = JSON.parse(r.text); } catch (e) { return; }
    if (!j || !j.data || now() - t0 > START_BUDGET_MS) return;
    const res = j.encrypted === false ? j.data : decryptReply(j.data);
    const srcs = res && res.sources;
    if (!srcs) return;
    for (const k of Object.keys(srcs)) {
      const s = srcs[k];
      if (s.url) add(res.name || sc, s, s.url, s.type);
      else for (const q of Object.keys(s.qualities || {})) add(res.name || sc, s, s.qualities[q].url, s.qualities[q].type || s.type, q);
    }
  }));
  return out;
}
