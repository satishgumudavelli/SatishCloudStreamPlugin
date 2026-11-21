// Ported from CloudStream provider: FrameMovieProvider (framemovie.online).
// The site itself is a TMDB-driven SPA; playback comes from third-party embed backends keyed by
// TMDB id, so no site search is needed. Hosts ported: VidRock (AES-256-GCM tokens) and Vidlink
// (token via enc-dec.app). Peachify (none.eat-peach.sbs) and Videasy (api.speedracelight.com)
// were dead (523 / 404) when ported, so they are skipped.

// ---- minimal AES-256 (encrypt block only) used to do GCM decryption via CTR mode ----
var SBOX = (function () {
  var s = new Array(256), p = 1, q = 1, x;
  do {
    p = p ^ ((p << 1) & 255) ^ (p & 128 ? 0x1b : 0);
    q ^= q << 1; q ^= q << 2; q ^= q << 4; q &= 255; if (q & 128) q ^= 0x09;
    x = q ^ ((q << 1) | (q >> 7)) ^ ((q << 2) | (q >> 6)) ^ ((q << 3) | (q >> 5)) ^ ((q << 4) | (q >> 4));
    s[p] = (x ^ 99) & 255;
  } while (p !== 1);
  s[0] = 99;
  return s;
})();

function xt(a) { return ((a << 1) ^ (a & 128 ? 0x1b : 0)) & 255; }

function expandKey(key) { // key: byte array(32) -> 15 round keys of 16 bytes
  var w = key.slice(), rc = 1, i, t;
  for (i = 32; i < 240; i += 4) {
    t = w.slice(i - 4, i);
    if (i % 32 === 0) { t = [SBOX[t[1]] ^ rc, SBOX[t[2]], SBOX[t[3]], SBOX[t[0]]]; rc = xt(rc); }
    else if (i % 32 === 16) t = t.map(function (b) { return SBOX[b]; });
    for (var j = 0; j < 4; j++) w[i + j] = w[i - 32 + j] ^ t[j];
  }
  return w;
}

function encryptBlock(w, inp) {
  var s = inp.slice(), r, i, a;
  for (i = 0; i < 16; i++) s[i] ^= w[i];
  for (r = 1; r <= 14; r++) {
    var t = new Array(16);
    for (i = 0; i < 16; i++) t[i] = SBOX[s[i]];
    // ShiftRows
    s = [t[0], t[5], t[10], t[15], t[4], t[9], t[14], t[3], t[8], t[13], t[2], t[7], t[12], t[1], t[6], t[11]];
    if (r < 14) { // MixColumns
      for (i = 0; i < 16; i += 4) {
        var a0 = s[i], a1 = s[i + 1], a2 = s[i + 2], a3 = s[i + 3];
        s[i] = xt(a0) ^ xt(a1) ^ a1 ^ a2 ^ a3;
        s[i + 1] = a0 ^ xt(a1) ^ xt(a2) ^ a2 ^ a3;
        s[i + 2] = a0 ^ a1 ^ xt(a2) ^ xt(a3) ^ a3;
        s[i + 3] = xt(a0) ^ a0 ^ a1 ^ a2 ^ xt(a3);
      }
    }
    for (i = 0; i < 16; i++) s[i] ^= w[r * 16 + i];
  }
  return s;
}

// GCM decrypt (12-byte nonce; auth tag is not verified, we only need the plaintext).
function gcmDecrypt(keyHex, nonce, ct) {
  var key = [], i;
  for (i = 0; i < keyHex.length; i += 2) key.push(parseInt(keyHex.substr(i, 2), 16));
  var w = expandKey(key), ctr = nonce.concat([0, 0, 0, 2]), out = '';
  for (var off = 0; off < ct.length; off += 16) {
    var ks = encryptBlock(w, ctr);
    for (i = 0; i < 16 && off + i < ct.length; i++) out += String.fromCharCode(ct[off + i] ^ ks[i]);
    for (i = 15; i >= 12; i--) { ctr[i] = (ctr[i] + 1) & 255; if (ctr[i]) break; }
  }
  return out;
}

function b64urlBytes(s) {
  s = s.replace(/-/g, '+').replace(/_/g, '/');
  while (s.length % 4) s += '=';
  var bin = atob(s), a = [];
  for (var i = 0; i < bin.length; i++) a.push(bin.charCodeAt(i));
  return a;
}

// ---- VidRock: /api/{movie|tv}; each source url is base64url(nonce12 + ciphertext + tag16) ----
var VIDROCK = 'https://vidrock.net';
var VIDROCK_KEY = '7f3e9c2a8b5d1f4e6a9c3b7d2e5f8a1c4b6d9e2f5a8c1b4d7e9f2a5c8b1d4e7f';

async function vidrock(q) {
  var path = q.isTv ? 'tv/' + q.tmdbId + '/' + q.season + '/' + q.episode : 'movie/' + q.tmdbId;
  var r = await http(VIDROCK + '/api/' + path);
  var j; try { j = JSON.parse(r.text); } catch (e) { return []; }
  var out = [];
  Object.keys(j).forEach(function (k) {
    var o = j[k];
    if (!o || !o.url) return;
    var raw = b64urlBytes(o.url), url;
    try { url = gcmDecrypt(VIDROCK_KEY, raw.slice(0, 12), raw.slice(12, raw.length - 16)); } catch (e) { return; }
    if (!/^https?:\/\//.test(url)) return;
    out.push({
      url: url, type: /\.m3u8/i.test(url) ? 'hls' : 'mp4', quality: 'HD',
      server: 'Vidrock-' + k, label: 'Vidrock [' + k + ']', audio: o.language || undefined,
      headers: { Referer: VIDROCK + '/' }
    });
  });
  return out;
}

// ---- Vidlink: tmdbId is encrypted by enc-dec.app, then /api/b/... returns playlist or file map ----
var VIDLINK = 'https://vidlink.pro';

async function vidlink(q) {
  var e = await http('https://enc-dec.app/api/enc-vidlink?text=' + q.tmdbId);
  var enc; try { enc = JSON.parse(e.text).result; } catch (x) { return []; }
  if (!enc) return [];
  var api = VIDLINK + '/api/b/' + (q.isTv ? 'tv/' + enc + '/' + q.season + '/' + q.episode : 'movie/' + enc);
  var h = { Referer: VIDLINK + '/', Origin: VIDLINK };
  var r = await http(api, { headers: h });
  var st; try { st = JSON.parse(r.text).stream; } catch (x) { return []; }
  if (!st) return [];
  if (st.playlist) {
    var ref = VIDLINK + '/', m = /[?&]headers=([^&]+)/.exec(st.playlist);
    if (m) { try { ref = JSON.parse(decodeURIComponent(m[1])).referer || ref; } catch (x) {} }
    return [{ url: st.playlist.split('?')[0], type: 'hls', quality: 'HD', server: 'Vidlink',
              headers: { Referer: ref, Origin: VIDLINK } }];
  }
  return Object.keys(st.qualities || {}).filter(function (k) { return st.qualities[k] && st.qualities[k].url; })
    .map(function (k) {
      return { url: st.qualities[k].url, type: 'mp4', quality: k + 'p', server: 'Vidlink', headers: { 'User-Agent': 'ExoPlayer' } }; // hakunaymatata CDN rejects browser UAs (428) and any Referer (429)
    });
}

async function getStreams(query) {
  if (!query.tmdbId) return [];
  var res = await Promise.all([vidrock(query), vidlink(query)].map(function (p) { return p.catch(function () { return []; }); }));
  return res[0].concat(res[1]);
}
