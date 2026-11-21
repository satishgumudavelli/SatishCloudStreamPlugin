#!/usr/bin/env node
// Runs a VyseTV plugin the way the app does, so it can be tested from a terminal:
//   node vysetv/tools/run-plugin.mjs vysetv/plugins/foo.js movie "The Matrix" 1999 603 tt0133093
//   node vysetv/tools/run-plugin.mjs vysetv/plugins/foo.js tv "Breaking Bad" 2008 1396 tt0903747 1 1
// Every stream it returns is then probed (an HLS playlist and its first piece, or a file's first bytes).
import fs from 'node:fs';
import vm from 'node:vm';
import net from 'node:net';
import dns from 'node:dns/promises';

const [file, type = 'movie', title = '', year = '', tmdbId = '0', imdbId = '', season = '', episode = ''] = process.argv.slice(2);
if (!file) { console.error('usage: run-plugin.mjs plugin.js movie|tv title year tmdbId imdbId [season episode]'); process.exit(2); }

const privateAddr = (ip) => /^(10\.|127\.|0\.|192\.168\.|169\.254\.|172\.(1[6-9]|2\d|3[01])\.|100\.(6[4-9]|[7-9]\d|1[01]\d|12[0-7])\.|::1$|f[cd])/i.test(ip);
async function http(url, o = {}) {
  let u = new URL(url);
  const headers = { 'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36', ...(o.headers || {}) };
  try {
    for (let hop = 0; hop < 6; hop++) {
      if (!/^https?:$/.test(u.protocol)) throw new Error('only http and https links');
      const ips = net.isIP(u.hostname) ? [u.hostname] : (await dns.lookup(u.hostname, { all: true })).map((a) => a.address);
      if (u.hostname === 'localhost' || ips.some(privateAddr)) throw new Error("this device's network is off limits");
      const body = hop === 0 && o.body != null ? (typeof o.body === 'string' ? o.body : JSON.stringify(o.body)) : undefined;
      const r = await fetch(u, { method: hop === 0 ? (o.method || 'GET') : 'GET', headers, body, redirect: 'manual', signal: AbortSignal.timeout(Math.min(o.timeout || 15000, 20000)) });
      const loc = r.headers.get('location');
      if (r.status >= 300 && r.status < 400 && loc) { u = new URL(loc, u); continue; }
      const h = {}; r.headers.forEach((v, k) => { h[k] = v; });
      return { status: r.status, url: String(u), headers: h, text: await r.text() };
    }
    throw new Error('too many redirects');
  } catch (e) { return { status: 0, error: String(e), text: '', headers: {}, url: String(url) }; }
}

const sandbox = { http, console, setTimeout, clearTimeout, JSON, Promise, RegExp, Math, Date, encodeURIComponent, decodeURIComponent, btoa, atob };
vm.createContext(sandbox);
// the same helper functions the app gives every plugin (assets/plugins/helpers.js in VyseTV)
vm.runInContext(fs.readFileSync(new URL('./helpers.js', import.meta.url), 'utf8'), sandbox, { filename: 'helpers.js' });
vm.runInContext(fs.readFileSync(file, 'utf8') + '\n;this.getStreams = getStreams;', sandbox, { filename: file });
// aliases: TITLES="Name one|Name two" (the app passes TMDB's alternative titles here)
const titles = [...new Set([title, ...(process.env.TITLES || '').split('|').filter(Boolean)])];
const query = { tmdbId: +tmdbId, imdbId: imdbId || null, type, isTv: type === 'tv', title, originalTitle: title, titles, isAnime: false, year: year ? +year : null, season: season ? +season : null, episode: episode ? +episode : null };
const t0 = Date.now();
const streams = await sandbox.getStreams(query);
console.log(`${streams.length} stream(s) in ${((Date.now() - t0) / 1000).toFixed(1)}s`);

async function probe(s) {
  const h = { 'User-Agent': 'Mozilla/5.0', ...(s.headers || {}) };
  const first = (t) => t.split('\n').map((l) => l.trim()).find((l) => l && !l.startsWith('#'));
  try {
    if (/m3u8/i.test(s.type || '') || (!s.type && /\.m3u8/.test(s.url)) || s.type === 'hls') {
      let base = s.url, r = await fetch(base, { headers: h }), t = await r.text();
      if (!t.includes('#EXTM3U')) return 'FAIL not a playlist (' + r.status + ')';
      if (t.includes('#EXT-X-STREAM-INF')) { base = new URL(first(t), base).href; r = await fetch(base, { headers: h }); t = await r.text(); if (!t.includes('#EXTM3U')) return 'FAIL variant (' + r.status + ')'; }
      const piece = new URL(first(t), base).href;
      const p = await fetch(piece, { headers: { ...h, Range: 'bytes=0-1023' } });
      return p.status === 200 || p.status === 206 ? 'OK hls' : 'FAIL first piece ' + p.status;
    }
    const r = await fetch(s.url, { headers: { ...h, Range: 'bytes=0-1023' } });
    return r.status === 200 || r.status === 206 ? 'OK file ' + (r.headers.get('content-type') || '') : 'FAIL ' + r.status;
  } catch (e) { return 'FAIL ' + e.message; }
}
let ok = 0;
for (const s of streams) { const p = await probe(s); if (p.startsWith('OK')) ok++; console.log(`[${p}] ${s.server || ''} ${s.quality || ''} ${s.type || ''} ${s.url.slice(0, 140)}`); }
console.log(`${ok}/${streams.length} stream(s) play`);
process.exit(streams.length && ok ? 0 : 1);
