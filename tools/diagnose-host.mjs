#!/usr/bin/env node
// Why can't I reach this site? Checks one host stage by stage and says where it stops:
//   node tools/diagnose-host.mjs new15.hdhub4u.cl [control.host.example]
// 1. DNS: this machine's answer and DNS-over-HTTPS (Cloudflare)'s, to spot a poisoned or empty answer
// 2. TCP: can a connection be opened to each address (IPv4 and IPv6 separately)
// 3. TLS: the handshake with the real name, with TLS 1.2 only, with TLS 1.3 only, with no name at all, and with
//    another name on the SAME address (the control)
// 5. how steady it is: 12 handshakes each for the host and the control (resets can be random, not a block)
// 4. HTTP: the same request the plugin sends (its cookie, referer and user agent) over the plugin's own TLS
// It only reads results: nothing here tries to get around a block.
import dns from 'node:dns/promises';
import net from 'node:net';
import tls from 'node:tls';

const host = process.argv[2];
const control = process.argv[3] || 'example.com';
if (!host) { console.error('usage: diagnose-host.mjs host [control-host]'); process.exit(2); }
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36';
const HDH = { Cookie: 'xla=s4t', Referer: 'https://google.com', 'User-Agent': UA }; // vega-hdhub4u.js's headers
const line = (ok, label, detail = '') => console.log(`${ok === null ? ' · ' : ok ? ' ✓ ' : ' ✗ '} ${label.padEnd(46)} ${detail}`);
const errOf = (e) => (e && (e.code || e.message)) || String(e);

console.log(`\n== ${host}`);
// 1. DNS
let v4 = [], v6 = [];
try { v4 = await dns.resolve4(host); } catch (e) { line(false, 'DNS A (system resolver)', errOf(e)); }
try { v6 = await dns.resolve6(host); } catch (e) { line(null, 'DNS AAAA (system resolver)', errOf(e)); }
if (v4.length) line(true, 'DNS A (system resolver)', v4.join(' '));
if (v6.length) line(null, 'DNS AAAA (system resolver)', v6.join(' '));
let doh = [];
try {
  const r = await fetch(`https://cloudflare-dns.com/dns-query?name=${host}&type=A`, { headers: { accept: 'application/dns-json' }, signal: AbortSignal.timeout(8000) });
  doh = ((await r.json()).Answer || []).filter((a) => a.type === 1).map((a) => a.data);
  line(doh.length > 0, 'DNS A (DNS-over-HTTPS, Cloudflare)', doh.join(' ') || 'no answer');
  if (doh.length && v4.length && !doh.some((a) => v4.includes(a))) line(false, 'system and DoH answers differ', 'the local DNS may be altering answers');
} catch (e) { line(null, 'DNS-over-HTTPS', errOf(e)); }
const ip = v4[0] || doh[0];
if (!ip) { console.log('\n   no IPv4 address: stopped here (DNS).'); process.exit(1); }

// 2. TCP
const tcp = (addr, family) => new Promise((resolve) => {
  const t0 = Date.now();
  const s = net.connect({ host: addr, port: 443, family, timeout: 6000 }, () => { s.destroy(); resolve({ ok: true, ms: Date.now() - t0 }); });
  s.on('timeout', () => { s.destroy(); resolve({ ok: false, why: 'timeout (nothing answers)' }); });
  s.on('error', (e) => resolve({ ok: false, why: errOf(e), ms: Date.now() - t0 }));
});
for (const a of v4.slice(0, 2)) { const r = await tcp(a, 4); line(r.ok, `TCP ${a}:443`, r.ok ? `${r.ms} ms` : `${r.why} after ${r.ms ?? 6000} ms`); }
if (v6[0]) { const r = await tcp(v6[0], 6); line(r.ok, `TCP [${v6[0]}]:443 (IPv6)`, r.ok ? `${r.ms} ms` : `${r.why}${/ENETUNREACH|EADDRNOTAVAIL/.test(r.why) ? ' (this machine has no IPv6 route: not the site)' : ''}`); }

// 3. TLS handshakes against the same address
const hs = (opts) => new Promise((resolve) => {
  const t0 = Date.now();
  const s = tls.connect({ host: ip, port: 443, timeout: 7000, rejectUnauthorized: false, ...opts }, () => {
    const info = { ok: true, ms: Date.now() - t0, proto: s.getProtocol(), cn: (s.getPeerCertificate().subject || {}).CN };
    s.destroy(); resolve(info);
  });
  s.on('timeout', () => { s.destroy(); resolve({ ok: false, why: 'timeout' }); });
  s.on('error', (e) => resolve({ ok: false, why: errOf(e), ms: Date.now() - t0 }));
});
console.log(`\n   TLS handshakes to ${ip}:`);
const tests = [
  [`name = ${host}`, { servername: host }],
  [`name = ${host}, TLS 1.2 only`, { servername: host, maxVersion: 'TLSv1.2' }],
  [`name = ${host}, TLS 1.3 only`, { servername: host, minVersion: 'TLSv1.3' }],
  [`name = ${host}, ALPN http/1.1`, { servername: host, ALPNProtocols: ['http/1.1'] }],
  ['no name at all', { servername: '' }],
  [`control: name = ${control}`, { servername: control }],
  [`control: name = ${host.replace(/^[^.]+\./, '')}`, { servername: host.replace(/^[^.]+\./, '') }],
];
const results = {};
for (const [label, o] of tests) {
  const r = await hs(o); results[label] = r;
  line(r.ok, label, r.ok ? `ok ${r.ms} ms, ${r.proto}, cert for ${r.cn}` : `${r.why} after ${r.ms ?? 7000} ms`);
}

// 4. the plugin's own request
console.log('\n   HTTP, with the plugin\'s headers (cookie xla=s4t, referer google, desktop Chrome user agent):');
try {
  const r = await fetch(`https://${host}/`, { headers: HDH, redirect: 'manual', signal: AbortSignal.timeout(12000) });
  const t = await r.text();
  line(r.status < 400, `GET https://${host}/`, `${r.status} ${r.headers.get('location') || ''} server=${r.headers.get('server') || '?'} cf-ray=${r.headers.get('cf-ray') || '-'} len=${t.length}${/just a moment/i.test(t) ? ' (Cloudflare challenge page)' : ''}`);
} catch (e) { line(false, `GET https://${host}/`, errOf(e.cause || e)); }

// 5. how steady is it: the same handshake several times, for the host and for the control
const rate = async (name, n = 12) => { let ok = 0, seq = ''; for (let i = 0; i < n; i++) { const r = await hs({ servername: name }); ok += r.ok ? 1 : 0; seq += r.ok ? '+' : '-'; await new Promise((x) => setTimeout(x, 300)); } return { ok, n, seq }; };
console.log('\n   steadiness (12 handshakes each, 300 ms apart):');
const rh = await rate(host), rc = await rate(control);
line(rh.ok > 0, host, `${rh.ok}/${rh.n}  ${rh.seq}`);
line(rc.ok > 0, `control ${control}`, `${rc.ok}/${rc.n}  ${rc.seq}`);

console.log('\n== verdict');
const real = results[`name = ${host}`];
if (rh.ok === 0 && rc.ok > rc.n / 2)
  console.log(`   Every handshake with "${host}" was reset while "${control}" mostly works: right now something on the path drops that name.`
    + ' It is not a timeout, not the headers (it fails before any request) and not DNS. It can come and go: try again later or from another network.');
else if (rh.ok > 0 && rh.ok < rh.n)
  console.log(`   Unsteady: ${rh.ok} of ${rh.n} handshakes with "${host}" got through (control: ${rc.ok} of ${rc.n}). Resets are random here, so a client that retries a few times gets through; one that tries once usually does not.`);
else if (real && real.ok) console.log('   The handshake works: if requests still fail, look at the HTTP line above (status, challenge page).');
else console.log('   Inconclusive: see the lines above.');
