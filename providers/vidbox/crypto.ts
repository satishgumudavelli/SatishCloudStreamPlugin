// The small amount of cryptography the embed servers need, in plain TypeScript: a provider cannot import libraries, and the
// sandbox has no crypto of its own. Nothing here is used to protect anything: it only reads what the servers send.

// ── bytes, base64, text ──

const B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

const bytesToBase64 = (b: Uint8Array): string => {
  let out = "";
  for (let i = 0; i < b.length; i += 3) {
    const n = (b[i] << 16) | ((b[i + 1] || 0) << 8) | (b[i + 2] || 0);
    out += B64[(n >> 18) & 63] + B64[(n >> 12) & 63] + (i + 1 < b.length ? B64[(n >> 6) & 63] : "=") + (i + 2 < b.length ? B64[n & 63] : "=");
  }
  return out;
};

export const base64ToBytes = (s: string): Uint8Array => {
  const clean = s.replace(/[^A-Za-z0-9+/]/g, "");
  const out: number[] = [];
  let acc = 0;
  let bits = 0;
  for (const c of clean) {
    acc = (acc << 6) | B64.indexOf(c);
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      out.push((acc >> bits) & 255);
    }
  }
  return Uint8Array.from(out);
};

export const base64UrlToBytes = (s: string): Uint8Array => base64ToBytes(s.replace(/-/g, "+").replace(/_/g, "/"));

export const utf8Encode = (s: string): Uint8Array => {
  const bin = unescape(encodeURIComponent(s));
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
};

export const utf8Decode = (b: Uint8Array): string => {
  let bin = "";
  for (let i = 0; i < b.length; i++) bin += String.fromCharCode(b[i]);
  try {
    return decodeURIComponent(escape(bin));
  } catch (_) {
    return bin;
  }
};

export const hexToBytes = (hex: string): Uint8Array => {
  const out = new Uint8Array(hex.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(hex.substr(i * 2, 2), 16);
  return out;
};

// ── AES (encrypting blocks only: GCM and counter mode run the cipher forwards) ──

const SBOX = new Uint8Array(256);
(() => {
  const rotl8 = (x: number, s: number) => ((x << s) | (x >> (8 - s))) & 255;
  let p = 1, q = 1;
  do {
    p = (p ^ ((p << 1) & 255) ^ (p & 0x80 ? 0x1b : 0)) & 255; // p * 3
    q ^= q << 1; q ^= q << 2; q ^= q << 4; q &= 255; // q / 3
    if (q & 0x80) q ^= 0x09;
    const x = q ^ rotl8(q, 1) ^ rotl8(q, 2) ^ rotl8(q, 3) ^ rotl8(q, 4);
    SBOX[p] = (x ^ 0x63) & 255;
  } while (p !== 1);
  SBOX[0] = 0x63;
})();

const xtime = (a: number) => ((a << 1) ^ (a & 0x80 ? 0x1b : 0)) & 255;

const expandKey = (key: Uint8Array): { w: Uint8Array; rounds: number } => {
  const nk = key.length / 4;
  const rounds = nk + 6;
  const w = new Uint8Array(16 * (rounds + 1));
  w.set(key);
  let rcon = 1;
  for (let i = nk; i < 4 * (rounds + 1); i++) {
    let t = [w[(i - 1) * 4], w[(i - 1) * 4 + 1], w[(i - 1) * 4 + 2], w[(i - 1) * 4 + 3]];
    if (i % nk === 0) {
      t = [SBOX[t[1]] ^ rcon, SBOX[t[2]], SBOX[t[3]], SBOX[t[0]]];
      rcon = xtime(rcon);
    } else if (nk > 6 && i % nk === 4) {
      t = t.map((x) => SBOX[x]);
    }
    for (let j = 0; j < 4; j++) w[i * 4 + j] = w[(i - nk) * 4 + j] ^ t[j];
  }
  return { w, rounds };
};

const addKey = (s: Uint8Array, w: Uint8Array, round: number) => {
  for (let i = 0; i < 16; i++) s[i] ^= w[round * 16 + i];
};

const encryptBlock = (k: { w: Uint8Array; rounds: number }, block: Uint8Array): Uint8Array => {
  const s = Uint8Array.from(block);
  addKey(s, k.w, 0);
  for (let r = 1; r <= k.rounds; r++) {
    for (let i = 0; i < 16; i++) s[i] = SBOX[s[i]];
    const t = Uint8Array.from(s);
    for (let c = 0; c < 4; c++) for (let row = 0; row < 4; row++) s[row + 4 * c] = t[row + 4 * ((c + row) % 4)];
    if (r !== k.rounds) {
      for (let c = 0; c < 4; c++) {
        const a0 = s[4 * c], a1 = s[4 * c + 1], a2 = s[4 * c + 2], a3 = s[4 * c + 3];
        s[4 * c] = xtime(a0) ^ (xtime(a1) ^ a1) ^ a2 ^ a3;
        s[4 * c + 1] = a0 ^ xtime(a1) ^ (xtime(a2) ^ a2) ^ a3;
        s[4 * c + 2] = a0 ^ a1 ^ xtime(a2) ^ (xtime(a3) ^ a3);
        s[4 * c + 3] = (xtime(a0) ^ a0) ^ a1 ^ a2 ^ xtime(a3);
      }
    }
    addKey(s, k.w, r);
  }
  return s;
};

/**
 * The plaintext of AES-GCM (a 12-byte nonce): the same keystream as counter mode, first counter 2. The 16-byte tag is NOT checked
 * (this only reads what a server sent; a wrong key shows as text that is not a URL).
 */
export const aesGcmDecryptNoTag = (key: Uint8Array, nonce: Uint8Array, ciphertext: Uint8Array): Uint8Array => {
  const k = expandKey(key);
  const counter = new Uint8Array(16);
  counter.set(nonce);
  counter[15] = 2;
  const out = new Uint8Array(ciphertext.length);
  for (let i = 0; i < ciphertext.length; i += 16) {
    const ks = encryptBlock(k, counter);
    for (let j = 0; j < 16 && i + j < ciphertext.length; j++) out[i + j] = ciphertext[i + j] ^ ks[j];
    for (let p = 15; p >= 12; p--) {
      counter[p] = (counter[p] + 1) & 255;
      if (counter[p] !== 0) break;
    }
  }
  return out;
};

// ── the "mvm1" keystream cipher of the speedracelight / movy servers ──
// A seeded generator (fnv1a, a murmur3-style avalanche, golden-ratio mixing) makes a keystream that is XORed with the base64url body;
// the first four bytes of the result must read "mvm1".

const MS = 2654435769;
const avalanche = (x: number): number => {
  x = (x ^ (x >>> 16)) >>> 0;
  x = Math.imul(x, 2246822507) >>> 0;
  x = (x ^ (x >>> 13)) >>> 0;
  x = Math.imul(x, 3266489909) >>> 0;
  return (x ^ (x >>> 16)) >>> 0;
};
const rotl = (l: number, o: number): number => {
  const s = o & 31;
  return s === 0 ? l >>> 0 : ((l << s) | (l >>> (32 - s))) >>> 0;
};
const fnv1a = (s: string): number => {
  let o = 2166136261;
  for (let i = 0; i < s.length; i++) o = Math.imul((o ^ s.charCodeAt(i)) >>> 0, 16777619) >>> 0;
  return avalanche(o);
};

export const mvmDecode = (token: string, seed: string, mediaId: number): string => {
  const cipher = base64UrlToBytes(token);
  const table = new Array<number>(61).fill(0);
  const visited = new Array<boolean>(61).fill(false);
  let i = avalanche((fnv1a(seed) ^ avalanche(((mediaId >>> 0) ^ MS) >>> 0)) >>> 0);
  for (let r = 0; r < 8; r++) {
    const n = i % 61;
    i = rotl((i + MS) >>> 0, 7 + (r & 7));
    table[n] = (i ^ avalanche(i)) >>> 0;
    visited[n] = true;
    i = avalanche((i + n) >>> 0);
  }
  let acc = avalanche((i ^ 2779096485) >>> 0);
  const nextWord = (counter: number): number => {
    const r = acc % 61;
    const has = visited[r];
    const u = has ? table[r] : 0;
    const mask = has ? 0xffffffff : 0;
    const d = Math.imul(MS, counter + 1) >>> 0;
    const xorUD = (u ^ d) >>> 0;
    let g = ((acc ^ xorUD) | (acc & xorUD & mask)) >>> 0;
    g = (rotl((g + acc) >>> 0, r & 31) ^ rotl(acc, (r * 7) & 31)) >>> 0;
    const next = avalanche((g + MS) >>> 0);
    table[r] = next;
    visited[r] = true;
    acc = next;
    return next;
  };
  const out = new Uint8Array(cipher.length);
  let counter = 0;
  for (let idx = 0; idx < cipher.length; ) {
    const word = nextWord(counter++);
    for (let b = 0; b < 4 && idx < cipher.length; b++, idx++) out[idx] = cipher[idx] ^ ((word >>> (8 * b)) & 255);
  }
  if (out[0] !== 109 || out[1] !== 118 || out[2] !== 109 || out[3] !== 49) throw new Error("mvm1 decrypt failed: bad seed or tampered payload");
  return utf8Decode(out.subarray(4));
};

// ── a base64 with a shuffled alphabet (vidnest): a lookup, not encryption ──

const VIDNEST_ALPHABET = "RB0fpH8ZEyVLkv7c2i6MAJ5u3IKFDxlS1NTsnGaqmXYdUrtzjwObCgQP94hoeW+/=";
const STANDARD_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=";

export const vidnestDecode = (data: string): string => {
  let t = "";
  for (const c of data) {
    const at = VIDNEST_ALPHABET.indexOf(c);
    t += at >= 0 ? STANDARD_ALPHABET[at] : c;
  }
  return utf8Decode(base64ToBytes(t));
};

// ── CryptoJS-style passphrase AES (Nxsha): "Salted__" + salt[8] + AES-256-CBC, key and iv from MD5 chaining (OpenSSL EVP_BytesToKey) ──

const MD5_S = [7, 12, 17, 22, 5, 9, 14, 20, 4, 11, 16, 23, 6, 10, 15, 21];
const MD5_K = Array.from({ length: 64 }, (_, i) => Math.floor(Math.abs(Math.sin(i + 1)) * 4294967296) >>> 0);

const md5 = (msg: Uint8Array): Uint8Array => {
  const len = msg.length;
  const padded = new Uint8Array(((len + 8) >> 6 << 6) + 64);
  padded.set(msg);
  padded[len] = 0x80;
  const dv = new DataView(padded.buffer);
  dv.setUint32(padded.length - 8, (len << 3) >>> 0, true);
  dv.setUint32(padded.length - 4, Math.floor(len / 536870912), true);
  let a0 = 0x67452301, b0 = 0xefcdab89 | 0, c0 = 0x98badcfe | 0, d0 = 0x10325476;
  for (let off = 0; off < padded.length; off += 64) {
    let a = a0, b = b0, c = c0, d = d0;
    for (let i = 0; i < 64; i++) {
      let f: number, g: number;
      if (i < 16) { f = (b & c) | (~b & d); g = i; }
      else if (i < 32) { f = (d & b) | (~d & c); g = (5 * i + 1) & 15; }
      else if (i < 48) { f = b ^ c ^ d; g = (3 * i + 5) & 15; }
      else { f = c ^ (b | ~d); g = (7 * i) & 15; }
      const t = (a + f + MD5_K[i] + dv.getUint32(off + g * 4, true)) | 0;
      const s = MD5_S[(i >> 4) * 4 + (i & 3)];
      a = d; d = c; c = b;
      b = (b + ((t << s) | (t >>> (32 - s)))) | 0;
    }
    a0 = (a0 + a) | 0; b0 = (b0 + b) | 0; c0 = (c0 + c) | 0; d0 = (d0 + d) | 0;
  }
  const out = new Uint8Array(16);
  const o = new DataView(out.buffer);
  [a0, b0, c0, d0].forEach((v, i) => o.setUint32(i * 4, v >>> 0, true));
  return out;
};

const concat = (...p: Uint8Array[]): Uint8Array => {
  const out = new Uint8Array(p.reduce((n, x) => n + x.length, 0));
  let at = 0;
  for (const x of p) { out.set(x, at); at += x.length; }
  return out;
};

const evpKeyIv = (pass: string, salt: Uint8Array): { key: Uint8Array; iv: Uint8Array } => {
  let d: Uint8Array = new Uint8Array(0);
  let prev: Uint8Array = new Uint8Array(0);
  const pw = utf8Encode(pass);
  while (d.length < 48) {
    prev = md5(concat(prev, pw, salt));
    d = concat(d, prev);
  }
  return { key: d.subarray(0, 32), iv: d.subarray(32, 48) };
};

const gmul = (a: number, b: number): number => {
  let p = 0;
  for (; b; b >>= 1) { if (b & 1) p ^= a; a = xtime(a); }
  return p;
};

const decryptBlock = (k: { w: Uint8Array; rounds: number }, block: Uint8Array): Uint8Array => {
  const inv = new Uint8Array(256);
  for (let i = 0; i < 256; i++) inv[SBOX[i]] = i;
  const s = Uint8Array.from(block);
  addKey(s, k.w, k.rounds);
  for (let r = k.rounds - 1; r >= 0; r--) {
    const t = Uint8Array.from(s);
    for (let c = 0; c < 4; c++) for (let row = 0; row < 4; row++) s[row + 4 * ((c + row) % 4)] = t[row + 4 * c];
    for (let i = 0; i < 16; i++) s[i] = inv[s[i]];
    addKey(s, k.w, r);
    if (r !== 0) {
      for (let c = 0; c < 4; c++) {
        const a0 = s[4 * c], a1 = s[4 * c + 1], a2 = s[4 * c + 2], a3 = s[4 * c + 3];
        s[4 * c] = gmul(a0, 14) ^ gmul(a1, 11) ^ gmul(a2, 13) ^ gmul(a3, 9);
        s[4 * c + 1] = gmul(a0, 9) ^ gmul(a1, 14) ^ gmul(a2, 11) ^ gmul(a3, 13);
        s[4 * c + 2] = gmul(a0, 13) ^ gmul(a1, 9) ^ gmul(a2, 14) ^ gmul(a3, 11);
        s[4 * c + 3] = gmul(a0, 11) ^ gmul(a1, 13) ^ gmul(a2, 9) ^ gmul(a3, 14);
      }
    }
  }
  return s;
};

const toUrlSafe = (b: Uint8Array) => bytesToBase64(b).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");

/** CryptoJS.AES.encrypt(text, pass), written url-safe (+ → -, / → _, no "="). [salt] is 8 bytes. */
export const cryptoJsEncrypt = (text: string, pass: string, salt: Uint8Array): string => {
  const { key, iv } = evpKeyIv(pass, salt);
  const k = expandKey(key);
  const data = utf8Encode(text);
  const pad = 16 - (data.length % 16);
  const plain = concat(data, new Uint8Array(pad).fill(pad));
  const out = new Uint8Array(plain.length);
  let prev: Uint8Array = iv;
  for (let i = 0; i < plain.length; i += 16) {
    const blk = plain.subarray(i, i + 16).map((x, j) => x ^ prev[j]);
    prev = encryptBlock(k, blk);
    out.set(prev, i);
  }
  return toUrlSafe(concat(utf8Encode("Salted__"), salt, out));
};

/** The text of a CryptoJS.AES.encrypt result (url-safe or plain base64). */
export const cryptoJsDecrypt = (token: string, pass: string): string => {
  const raw = base64UrlToBytes(token);
  const { key, iv } = evpKeyIv(pass, raw.subarray(8, 16));
  const k = expandKey(key);
  const body = raw.subarray(16);
  const out = new Uint8Array(body.length);
  let prev: Uint8Array = iv;
  for (let i = 0; i + 16 <= body.length; i += 16) {
    const blk = body.subarray(i, i + 16);
    out.set(decryptBlock(k, blk).map((x, j) => x ^ prev[j]), i);
    prev = blk;
  }
  return utf8Decode(out.subarray(0, out.length - out[out.length - 1]));
};
