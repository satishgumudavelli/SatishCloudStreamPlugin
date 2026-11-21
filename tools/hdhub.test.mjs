// node --test tools/hdhub.test.mjs
// vega-hdhub4u.js against a made-up site: the real one is not reachable from where this was written, so this checks
// the plugin's own logic (domain discovery, post choice, page reading, the link hops), not the live site's markup.
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const J = (x) => JSON.parse(JSON.stringify(x));
const b64 = (s) => Buffer.from(s).toString('base64');
const rot13 = (s) => s.replace(/[a-zA-Z]/g, (c) => { const b = c <= 'Z' ? 65 : 97; return String.fromCharCode(((c.charCodeAt(0) - b + 13) % 26) + b); });
// greenmotors short link: the page inlines s('o','<b64(b64(rot13(b64(json))))>',180) and json.o is b64 of the target
const greenPage = (target) => `<script>s('o','${b64(b64(rot13(b64(JSON.stringify({ o: b64(target) })))))}',180);</script>`;

function load(routes) {
  const calls = [];
  const http = async (url, opts = {}) => {
    calls.push({ url, opts });
    for (const [re, fn] of routes) {
      if (re.test(url)) { const r = fn(url, opts); return { status: 200, url, headers: {}, text: '', ...r }; }
    }
    return { status: 0, error: 'no route', url, headers: {}, text: '' };
  };
  const ctx = vm.createContext({ http, btoa: (s) => Buffer.from(s, 'binary').toString('base64'), atob: (s) => Buffer.from(s, 'base64').toString('binary'), console, JSON, Promise, RegExp, Math, Date, encodeURIComponent, decodeURIComponent });
  vm.runInContext(fs.readFileSync(new URL('./helpers.js', import.meta.url), 'utf8'), ctx);
  vm.runInContext(fs.readFileSync(new URL('../plugins/vega-hdhub4u.js', import.meta.url), 'utf8'), ctx);
  ctx.__calls = calls;
  return ctx;
}

const LANDING = `<html><body><a href="https://hdhub4u.ms/" class="btn">View Full Site</a><a href="https://www.facebook.com/x">fb</a></body></html>`;

test('discoverDomain: the landing page\'s link, followed to where it ends', async () => {
  let c = load([[/hdhub4u\.fr\/$/, () => ({ text: LANDING })], [/hdhub4u\.ms/, () => ({ url: 'https://new99.hdhub4u.cl/', text: 'x' })]]);
  assert.equal(await c.discoverDomain(), 'https://new99.hdhub4u.cl');
  // the link ends back on the landing page (site not served to this visitor): nothing
  c = load([[/hdhub4u\.fr\/$/, () => ({ text: LANDING })], [/hdhub4u\.ms/, () => ({ url: 'https://hdhub4u.fr/', text: 'x' })]]);
  assert.equal(await c.discoverDomain(), null);
  c = load([[/hdhub4u\.fr/, () => ({ status: 503 })]]);
  assert.equal(await c.discoverDomain(), null);
});

const MOVIE_PAGE = `<html><body><h1>Oppenheimer (2023) BluRay [Hindi 5.1 &amp; English] 1080p 720p &amp; 480p</h1><div class="page-body">
<h2>Storyline</h2><p>Plot with <a href="https://en.wikipedia.org/wiki/Oppenheimer">a link in the plot</a></p>
<h2>DOWNLOAD LINKS</h2>
<h3><a href="https://greenmotors.club/s/aaa">1080p [3.4GB] Hubdrive</a></h3>
<h3><a href="https://hubcloud.foo/drive/bbb">720p [1.2GB] HubCloud</a></h3>
<h3><a href="https://example.com/ad">480p ad</a></h3>
</div></body></html>`;

test('collectLinks, a film: the download hosts whose text names a quality', () => {
  const c = load([]);
  const r = c.collectLinks(MOVIE_PAGE, 'https://x.test');
  assert.equal(r.series, false);
  assert.deepEqual(J(r.movie), ['1080p [3.4GB] Hubdrive|https://greenmotors.club/s/aaa', '720p [1.2GB] HubCloud|https://hubcloud.foo/drive/bbb']);
});

const SERIES_PAGE = `<html><body><h1>Some Show (2024) Season 1 WEB-DL 720p</h1><div class="page-body">
<h2>Storyline</h2><p>x</p>
<h3>Single Episode Links</h3>
<h4>Episode 1</h4><p><a href="https://greenmotors.club/e/1a">720p [300MB] Hubdrive</a> <a href="https://greenmotors.club/e/1b">1080p [800MB] Hubdrive</a></p>
<h4>Episode 2</h4><p><a href="https://greenmotors.club/e/2a">720p</a> <a href="https://vidara.to/e/abc123">WATCH</a></p>
<h4>BONUS EP 1</h4><p><a href="https://greenmotors.club/e/bonus">720p</a></p>
<h4>Episode 3</h4><p><a href="https://greenmotors.club/e/3a">720p</a></p>
</div></body></html>`;

test('collectLinks, a show: links per episode, a watch embed, bonus rows kept apart', () => {
  const c = load([]);
  const r = c.collectLinks(SERIES_PAGE, 'https://x.test');
  assert.equal(r.series, true);
  assert.deepEqual(Object.keys(J(r.eps)), ['1', '2', '3', '101']);
  assert.deepEqual(J(r.eps[1]), ['720p|https://greenmotors.club/e/1a', '1080p|https://greenmotors.club/e/1b']);
  assert.deepEqual(J(r.eps[2]), ['720p|https://greenmotors.club/e/2a', 'Watch|https://vidara.to/e/abc123']);
  assert.deepEqual(J(r.eps[101]), ['720p|https://greenmotors.club/e/bonus']); // not a quality of episode 2
  // a show's page with no "Single Episode" heading: the whole body is the list
  const flat = c.collectLinks(`<h1>Show Season 1</h1><div class="page-body"><h4>EPiSODE 1</h4><a href="https://greenmotors.club/f1">WATCH</a><h4>EPiSODE 2</h4><a href="https://greenmotors.club/f2">WATCH</a></div>`, 'https://x.test');
  assert.deepEqual(Object.keys(J(flat.eps)), ['1', '2']);
});

const hit = (title, link, imdb) => ({ document: { post_title: title, permalink: link, imdb_id: imdb } });
const index = (hits) => [/search\.pingora\.fyi/, (u) => ({ text: JSON.stringify({ hits }) })];

test('pickPost: the IMDb id first; a show\'s post for the season asked; a film never a series post', async () => {
  const hits = [
    hit('Some Show (2024) Season 2 WEB-DL', 'https://old.test/s2/', 'tt7'),
    hit('Some Show (2024) Season 1 WEB-DL', 'https://old.test/s1/', 'tt7'),
    hit('Oppenheimer (2023) BluRay 1080p', 'https://old.test/opp/', 'tt15398776'),
    hit('Oppenheimer Web Series Season 1', 'https://old.test/opp-series/', 'tt15398776'),
  ];
  const c = load([index(hits)]);
  const film = await c.pickPost({ isTv: false, title: 'Oppenheimer', titles: ['Oppenheimer'], year: 2023, imdbId: 'tt15398776' });
  assert.equal(film.link, 'https://old.test/opp/');
  const show = await c.pickPost({ isTv: true, season: 1, episode: 1, title: 'Some Show', titles: ['Some Show'], imdbId: 'tt7' });
  assert.equal(show.link, 'https://old.test/s1/');
  // no IMDb id known: by title, still the right season
  const byTitle = await c.pickPost({ isTv: true, season: 2, episode: 1, title: 'Some Show', titles: ['Some Show'] });
  assert.equal(byTitle.link, 'https://old.test/s2/');
  assert.equal(await c.pickPost({ isTv: false, title: 'Unknown Film', titles: ['Unknown Film'], year: 2020, imdbId: 'tt0000000' }), null);
});

test('resolveHop: greenmotors -> hubstream -> vidara, down to an HLS stream', async () => {
  const c = load([
    [/greenmotors\.club\/s\/aaa/, () => ({ text: greenPage('https://hubstream.art/#FILE123') })],
    [/hubstream\.art\/api\/public\/resolve\?file1=FILE123/, () => ({ text: JSON.stringify({ url: 'https://vidara.to/e/zzz999' }) })],
    [/vidara\.to\/api\/stream/, (u, o) => ({ text: JSON.stringify({ streaming_url: 'https://cdn.test/zzz999/master.m3u8', _got: o.body }) })],
  ]);
  const out = [];
  await c.resolveHop('https://greenmotors.club/s/aaa', '1080p', out, 0);
  assert.equal(out.length, 1);
  assert.equal(out[0].url, 'https://cdn.test/zzz999/master.m3u8');
  assert.equal(out[0].type, 'hls');
  const post = c.__calls.find((x) => /vidara\.to\/api\/stream/.test(x.url));
  assert.equal(post.opts.method, 'POST');
  assert.deepEqual(J(post.opts.body), { filecode: 'zzz999', device: 'web' });
});

test('resolveHop: hubdrive page -> its button; hubcdn "Instant" link; playdrive is skipped; depth is limited', async () => {
  const final = 'https://r2.test/film.mkv';
  const reurl = 'https://hubcdn.club/x?r=' + encodeURIComponent(b64('https://hubcdn.club/dl/?link=' + final));
  const c = load([
    [/hubdrive\.space\/file\/1/, () => ({ text: '<a class="btn btn-success1" href="https://hubcdn.club/dl/42">Instant</a>' })],
    [/hubcdn\.club\/dl\/42/, () => ({ text: `<script>var reurl = "${reurl}";</script>` })],
  ]);
  const out = [];
  await c.resolveHop('https://hubdrive.space/file/1', '720p', out, 0);
  assert.deepEqual(J(out.map((o) => o.url)), [final]);
  const none = [];
  await c.resolveHop('https://playdrive.vip/e/1/s-01/ep-01', 'x', none, 0);
  await c.resolveHop('https://greenmotors.club/s/aaa', 'x', none, 4); // too deep
  assert.equal(none.length, 0);
});

test('getStreams: the whole chain on a made-up site, and the post is fetched from the discovered domain', async () => {
  const c = load([
    [/hdhub4u\.fr\/$/, () => ({ text: LANDING })],
    [/hdhub4u\.ms/, () => ({ url: 'https://live.hdhub4u.cl/', text: 'x' })],
    [/raw\.githubusercontent\.com/, () => ({ status: 404 })],
    index([hit('Oppenheimer (2023) BluRay 1080p', 'https://dead.old.domain/oppenheimer-2023/', 'tt15398776')]),
    [/live\.hdhub4u\.cl\/oppenheimer-2023\//, () => ({ text: MOVIE_PAGE.replace('</body>', '<!-- ' + 'x'.repeat(2100) + ' --></body>') })],
    [/greenmotors\.club\/s\/aaa/, () => ({ text: greenPage('https://hubstream.art/#F1') })],
    [/hubstream\.art\/api/, () => ({ text: JSON.stringify({ url: 'https://vidara.to/e/q1' }) })],
    [/vidara\.to\/api\/stream/, () => ({ text: JSON.stringify({ streaming_url: 'https://cdn.test/q1/master.m3u8' }) })],
  ]);
  const r = await c.getStreams({ isTv: false, title: 'Oppenheimer', originalTitle: 'Oppenheimer', titles: ['Oppenheimer'], year: 2023, imdbId: 'tt15398776' });
  assert.deepEqual(J(r.map((s) => s.url)), ['https://cdn.test/q1/master.m3u8']);
  assert.ok(c.__calls.some((x) => x.url === 'https://live.hdhub4u.cl/oppenheimer-2023/')); // the permalink's dead domain was replaced
  assert.ok(!c.__calls.some((x) => /dead\.old\.domain/.test(x.url) && c.__calls.indexOf(x) < c.__calls.findIndex((y) => /live\.hdhub4u\.cl\/oppenheimer/.test(y.url)))); // and tried after, not before
  // the site is down: nothing, no error
  const down = load([index([hit('Oppenheimer (2023) BluRay 1080p', 'https://dead.old.domain/oppenheimer-2023/', 'tt15398776')])]);
  assert.deepEqual(J(await down.getStreams({ isTv: false, title: 'Oppenheimer', titles: ['Oppenheimer'], year: 2023, imdbId: 'tt15398776' })), []);
});
