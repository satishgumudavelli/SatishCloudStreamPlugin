// node --test tools/domains.test.mjs
// siteDomains lives in each plugin (tools/site-domains.js is the master copy): the app knows nothing about it.
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const J = (x) => JSON.parse(JSON.stringify(x));
const SNIPPET = fs.readFileSync(new URL('./site-domains.js', import.meta.url), 'utf8');
const MARK = '// ---- domains.json (snippet: tools/site-domains.js) ----';

const run = (http) => { const c = vm.createContext({ http }); vm.runInContext(SNIPPET, c); return c; };

test('siteDomains: the file\'s domains first, then the plugin\'s own, no duplicates', async () => {
  const calls = [];
  const c = run(async (url) => { calls.push(url); return { status: 200, text: JSON.stringify({ domains: [
    { name: 'Moviesmod', domain: 'moviesmod.tw' }, { name: 'moviesmod', domain: 'https://moviesmod.fi/' },
    { name: 'Other', domain: 'other.example' }, { name: 'MOVIESMOD', domain: 'moviesmod.tw' } ] }) }; });
  assert.deepEqual(J(await c.siteDomains('Moviesmod', ['https://moviesmod.fi', 'https://mirror.example'])), ['https://moviesmod.tw', 'https://moviesmod.fi', 'https://mirror.example']);
  assert.deepEqual(J(await c.siteDomains('Other', [])), ['https://other.example']);
  assert.equal(calls.length, 1); // the file is read once per run
  assert.deepEqual(J(await c.siteDomains('Unknown', ['http://x.test/'])), ['http://x.test']);
});

test('siteDomains: an unreadable or broken file leaves the plugin\'s own list', async () => {
  for (const http of [async () => ({ status: 404, text: '' }), async () => ({ status: 200, text: 'not json' }), async () => { throw new Error('down'); }]) {
    assert.deepEqual(J(await run(http).siteDomains('X', ['https://own.example'])), ['https://own.example']);
  }
});

test('every plugin that uses siteDomains carries the same copy of the snippet', () => {
  const dir = new URL('../plugins/', import.meta.url);
  let using = 0;
  for (const f of fs.readdirSync(dir).filter((n) => n.endsWith('.js'))) {
    const s = fs.readFileSync(new URL(f, dir), 'utf8');
    if (!/siteDomains\(/.test(s)) continue;
    using++;
    const i = s.indexOf(MARK);
    assert.ok(i >= 0, `${f} calls siteDomains but has no snippet`);
    const j = s.indexOf('// ---- run guard', i);
    assert.equal(s.slice(i, j < 0 ? s.length : j).trimEnd(), SNIPPET.trimEnd(), `${f}: its copy differs from tools/site-domains.js (paste the file again)`);
  }
  assert.ok(using >= 12);
});

test('the app\'s helpers.js has nothing about domains', () => {
  assert.ok(!/siteDomains|DOMAINS_URL|domains\.json/.test(fs.readFileSync(new URL('./helpers.js', import.meta.url), 'utf8')));
});
