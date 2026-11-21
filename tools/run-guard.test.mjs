// node --test tools/run-guard.test.mjs
// tools/run-guard.js: the time budget and the retry every plugin carries at its end.
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const J = (x) => JSON.parse(JSON.stringify(x));
const GUARD = fs.readFileSync(new URL('./run-guard.js', import.meta.url), 'utf8');
const MARK = '// ---- run guard (snippet: tools/run-guard.js) ----';

// a plugin whose getStreams makes one request; `answers` is what the stub http returns, in order
function plugin(answers, { before = '', req = ['https://x.test/', {}] } = {}) {
  const calls = [];
  const http = async (url, opts) => { calls.push({ url, opts }); return answers[Math.min(calls.length - 1, answers.length - 1)]; };
  const ctx = vm.createContext({ http, Object, Date, Promise, String, RegExp });
  vm.runInContext(`${before}\nasync function getStreams(q) { return http(${JSON.stringify(req[0])}, ${JSON.stringify(req[1])}); }\n${GUARD}`, ctx);
  ctx.__calls = calls;
  return ctx;
}
const reset = { status: 0, error: 'ClientException with SocketException: Connection reset by peer (OS Error: Connection reset by peer, errno = 104)', text: '' };
const ok = { status: 200, text: 'fine' };

test('a reset connection is retried (twice at most) and the answer that gets through is returned', async () => {
  const c = plugin([reset, reset, ok]);
  const r = await c.getStreams({});
  assert.equal(r.status, 200);
  assert.equal(c.__calls.length, 3);
  const c2 = plugin([reset]);
  assert.equal((await c2.getStreams({})).status, 0);
  assert.equal(c2.__calls.length, 3); // gave up after the third try
});

test('not retried: an answer, a timeout, an unresolvable name, a POST, retry:false', async () => {
  for (const [answers, opts] of [
    [[{ status: 404, text: '' }], {}],
    [[{ status: 0, error: 'TimeoutException after 0:00:12.000000: Future not completed' }], {}],
    [[{ status: 0, error: 'SocketException: Failed host lookup: \'x.test\' (OS Error: Name or service not known)' }], {}],
    [[{ status: 0, error: 'getaddrinfo ENOTFOUND x.test' }], {}],
    [[reset], { method: 'POST' }],
    [[reset], { retry: false }],
  ]) {
    const c = plugin(answers, { req: ['https://x.test/', opts] });
    await c.getStreams({});
    assert.equal(c.__calls.length, 1, JSON.stringify(answers[0]).slice(0, 60) + ' ' + JSON.stringify(opts));
  }
});

test('the budget clips each request\'s timeout, and a plugin may set its own budget', async () => {
  const c = plugin([ok], { before: 'var BUDGET_MS = 5000;', req: ['https://x.test/', { timeout: 15000 }] });
  await c.getStreams({});
  assert.ok(c.__calls[0].opts.timeout <= 5000 && c.__calls[0].opts.timeout > 4000, String(c.__calls[0].opts.timeout));
  const d = plugin([ok]); // the default: 16 s; a request asking for less keeps its own
  await d.getStreams({});
  const e = plugin([ok], { req: ['https://x.test/', { timeout: 3000 }] });
  await e.getStreams({});
  assert.equal(e.__calls[0].opts.timeout, 3000);
});

test('once the budget is used up requests fail at once, without being sent', async () => {
  const c = plugin([ok]);
  vm.runInContext('BUDGET_MS = 1', c);
  await new Promise((r) => setTimeout(r, 20));
  // getStreams sets the deadline at its start: a request made later than the budget is refused
  vm.runInContext('DEADLINE = Date.now() - 5', c);
  const r = await vm.runInContext('http("https://x.test/", {})', c);
  assert.deepEqual(J(r).status, 0);
  assert.equal(r.error, 'out of time');
  assert.equal(c.__calls.length, 0);
});

test('no retry when less than 3 s of the budget is left', async () => {
  const c = plugin([reset]);
  vm.runInContext('BUDGET_MS = 2000', c);
  await c.getStreams({});
  assert.equal(c.__calls.length, 1);
});

test('every plugin carries the same copy of the guard, at its end', () => {
  const dir = new URL('../plugins/', import.meta.url);
  let n = 0;
  for (const f of fs.readdirSync(dir).filter((x) => x.endsWith('.js') && x !== 'demo.js')) {
    const s = fs.readFileSync(new URL(f, dir), 'utf8');
    const i = s.indexOf(MARK);
    assert.ok(i >= 0, `${f} has no run guard`);
    assert.equal(s.slice(i).trimEnd(), GUARD.trimEnd(), `${f}: its copy differs from tools/run-guard.js (paste the file again)`);
    n++;
  }
  assert.ok(n >= 19);
});
