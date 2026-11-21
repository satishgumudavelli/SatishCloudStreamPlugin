// node --test vysetv/tools/helpers.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const ctx = vm.createContext({});
vm.runInContext(fs.readFileSync(new URL('./helpers.js', import.meta.url), 'utf8'), ctx);
const H = ctx;
const J = (x) => JSON.parse(JSON.stringify(x)); // (arrays from the vm have another prototype)
const movie = (o = {}) => ({ type: 'movie', isTv: false, title: 'Dune: Part Two', originalTitle: 'Dune: Part Two', titles: ['Dune: Part Two', 'Dune 2'], year: 2024, ...o });
const tv = (o = {}) => ({ type: 'tv', isTv: true, title: 'Breaking Bad', originalTitle: 'Breaking Bad', titles: ['Breaking Bad'], year: 2008, season: 2, episode: 3, ...o });

test('normTitle', () => {
  assert.equal(H.normTitle('  Amélie!  '), 'amelie');
  assert.equal(H.normTitle('Fast & Furious: 9'), 'fast and furious 9');
  assert.equal(H.normTitle(null), '');
});

test('searchQueries: title, plain, aliases, season variants', () => {
  const q = H.searchQueries(movie());
  assert.deepEqual(J(q).slice(0, 3), ['Dune: Part Two', 'dune part two', 'Dune 2']);
  const t = H.searchQueries(tv());
  assert.deepEqual(J(t), ['Breaking Bad', 'Breaking Bad Season 2', 'Breaking Bad S2']);
  assert.deepEqual(J(H.searchQueries(tv({ season: 1 }))), ['Breaking Bad']);
  assert.deepEqual(J(H.searchQueries({ title: '', titles: [] })), []);
});

test('parseTitle', () => {
  assert.deepEqual({ ...H.parseTitle('Oppenheimer (2023) BluRay 1080p Dual Audio') }, { title: 'Oppenheimer', year: 2023, season: null });
  assert.deepEqual({ ...H.parseTitle('Breaking Bad Season 2 Complete 720p') }, { title: 'Breaking Bad', year: null, season: 2 });
  assert.deepEqual({ ...H.parseTitle('The Matrix 1999 Hindi') }, { title: 'The Matrix', year: 1999, season: null });
  assert.equal(H.parseTitle('Wonder Woman 1984 (2020) 1080p').title, 'Wonder Woman 1984');
  assert.equal(H.parseTitle('Wonder Woman 1984 (2020) 1080p').year, 2020);
  assert.equal(H.parseTitle('Se7en').title, 'Se7en');
  assert.equal(H.parseTitle('Dune Part Two 4K UHD').title, 'Dune Part Two');
});

test('titleScore', () => {
  const q = movie();
  assert.equal(H.titleScore('Dune: Part Two', q), 100);
  assert.equal(H.titleScore('dune part two', q), 100);
  assert.equal(H.titleScore('Dune 2', q), 100); // an alias (rankMatches trusts it less)
  assert.equal(H.titleScore('Dune', q), 40); // the site drops the subtitle
  assert.equal(H.titleScore('Dune Part Two Extended', q), 60);
  assert.equal(H.titleScore('Dune Messiah', q), 0);
  assert.equal(H.titleScore('The Matrix', { title: 'Matrix', titles: [] }), 100); // leading "The"
  assert.equal(H.titleScore('', q), 0);
});

test('rankMatches: films need the year within 1; shows ignore it', () => {
  const items = [
    { t: 'Dune (1984) 1080p' }, { t: 'Dune: Part Two (2024) 4K' }, { t: 'Dune Part Two (2023) CAM' }, { t: 'Dune: Part Two' },
  ];
  const r = H.rankMatches(items, movie(), (i) => i.t);
  assert.equal(r[0].t, 'Dune: Part Two (2024) 4K'); // exact year scores highest
  assert.ok(!r.some((i) => i.t.includes('1984'))); // wrong year: out
  assert.ok(r.length >= 3);
  const shows = [{ t: 'Breaking Bad Season 1' }, { t: 'Breaking Bad Season 2' }, { t: 'Better Call Saul Season 2' }];
  const rs = H.rankMatches(shows, tv(), (i) => i.t);
  assert.equal(rs[0].t, 'Breaking Bad Season 2'); // the right season first
  assert.equal(rs.length, 2);
  assert.equal(H.bestMatch([{ t: 'Nothing' }], tv(), (i) => i.t), null);
});

test('aliases only count with a fitting year, and never outrank the real title', () => {
  const q = movie({ title: 'Oppenheimer', originalTitle: 'Oppenheimer', titles: ['Oppenheimer', 'Gadget'], year: 2023 });
  // a film that happens to be called like an alias: no year on the listing, or the wrong one: not a match
  assert.equal(H.bestMatch([{ t: 'Gadget' }], q, (i) => i.t), null);
  assert.equal(H.bestMatch([{ t: 'Gadget (1999)' }], q, (i) => i.t), null);
  // a listing with no year at all (some sites show none): only a distinctive alias counts
  const q2 = tv({ title: 'Naruto: Shippuden', originalTitle: 'Naruto: Shippuden', titles: ['Naruto: Shippuden', 'Naruto Shippuuden', 'Gadget'], year: 2007 });
  assert.equal(H.bestMatch([{ t: 'Naruto Shippuuden' }], q2, (i) => i.t).t, 'Naruto Shippuuden');
  assert.equal(H.bestMatch([{ t: 'Gadget' }], q2, (i) => i.t), null);
  // with a fitting year it is accepted (the site lists the film under its other name)
  assert.equal(H.bestMatch([{ t: 'Gadget (2023)' }], q, (i) => i.t).t, 'Gadget (2023)');
  // and the real title still beats the alias
  const r = H.rankMatches([{ t: 'Gadget (2023)' }, { t: 'Oppenheimer (2023)' }], q, (i) => i.t);
  assert.equal(r[0].t, 'Oppenheimer (2023)');
});

test('get() may return an object', () => {
  const r = H.bestMatch([{ n: 'Dune: Part Two', y: '2024' }], movie(), (i) => ({ title: i.n, year: i.y }));
  assert.equal(r.n, 'Dune: Part Two');
  assert.equal(H.bestMatch([{ n: 'Dune: Part Two', y: '2010' }], movie(), (i) => ({ title: i.n, year: i.y })), null);
});

test('searchBest tries each query until one matches', async () => {
  const asked = [];
  const search = async (text) => { asked.push(text); return text === 'Dune 2' ? [{ t: 'Dune 2 (2024)' }] : []; };
  const m = await H.searchBest(movie(), search, (i) => i.t);
  assert.equal(m.t, 'Dune 2 (2024)');
  assert.deepEqual(J(asked), ['Dune: Part Two', 'dune part two', 'Dune 2']);
  assert.equal(await H.searchBest(movie(), async () => { throw new Error('x'); }, (i) => i.t), null);
});

test('imdbMatches', () => {
  const q = { imdbId: 'tt15398776' };
  assert.equal(H.imdbMatches('see https://www.imdb.com/title/tt15398776/ here', q), true);
  assert.equal(H.imdbMatches('https://www.imdb.com/title/tt0133093/', q), false);
  assert.equal(H.imdbMatches('no id here', q), null);
  assert.equal(H.imdbMatches('tt15398776', {}), null);
});

// ───── the HTML helper ─────
const PAGE = `<!doctype html><html><head><title>T &amp; Co</title><meta property="og:title" content="Dune &quot;Two&quot;">
<style>.a > b { color: red }</style></head><body>
<div id="main" class="card big"><h1 class="entry-title">Heading</h1>
<p>One <b>bold</b> &amp; <i>it</i>
<p>Two<br>lines</p>
<ul class="list"><li>first<li class="x">second<li>third</ul>
<a href="https://a.test/1" class="btn">A1</a> <a href="/rel" class=btn rel="nofollow noopener">A2</a> <a href='https://a.test/file.pdf'>PDF</a>
<img src="p.jpg" alt="x"><input disabled value=v>
<script>var s = "</div><a href='fake'>";</script>
<!-- <a href="comment">no</a> -->
<table><tr><td>c1<td>c2<tr><td>c3</table>
</div><div class="card"><span data-x="a>b">sp</span></div></body></html>`;

test('parseHtml: tree, entities, raw text, comments, implied end tags', () => {
  const d = H.parseHtml(PAGE);
  assert.equal(d.selectFirst('title').text(), 'T & Co');
  assert.equal(d.selectFirst('meta[property=og:title]').attr('content'), 'Dune "Two"');
  assert.equal(d.selectFirst('h1').text(), 'Heading');
  assert.equal(d.select('li').length, 3); // <li> closes the one before it
  assert.equal(d.select('td').length, 3);
  assert.equal(d.select('a[href=comment]').length, 0); // inside a comment
  assert.equal(d.select('a[href=fake]').length, 0); // inside a script
  assert.match(d.selectFirst('script').data(), /<\/div><a href='fake'>/);
  assert.equal(d.selectFirst('script').text(), ''); // text() never includes script text
  assert.equal(d.selectFirst('span').attr('data-x'), 'a>b'); // '>' inside a quoted attribute
  assert.equal(d.selectFirst('input').hasAttr('disabled'), true);
  assert.equal(d.selectFirst('a.btn').attr('missing'), '');
  assert.equal(H.unescapeHtml('Tom &amp; Jerry &#8211; &#x41; &nope;'), 'Tom & Jerry – A &nope;');
});

test('select: the selectors the CloudStream providers use', () => {
  const d = H.parseHtml(PAGE), t = (css) => J(d.select(css).map((e) => e.text()));
  assert.deepEqual(t('a.btn'), ['A1', 'A2']);
  assert.deepEqual(t('a[href^=http]'), ['A1', 'PDF']);
  assert.deepEqual(t('a[href$=pdf]'), ['PDF']);
  assert.deepEqual(t('a[href*=rel]'), ['A2']);
  assert.deepEqual(t('a[rel~=nofollow]'), ['A2']);
  assert.deepEqual(t('ul > li'), ['first', 'second', 'third']);
  assert.deepEqual(t('ul.list li.x'), ['second']);
  assert.deepEqual(t('li:first-child'), ['first']);
  assert.deepEqual(t('li:last-child'), ['third']);
  assert.deepEqual(t('li:nth-child(2)'), ['second']);
  assert.deepEqual(t('li:nth-child(odd)'), ['first', 'third']);
  assert.deepEqual(t('li:not(.x)'), ['first', 'third']);
  assert.deepEqual(t('h1.entry-title, span'), ['Heading', 'sp']);
  assert.deepEqual(t('li:contains(SEC)'), ['second']); // case-insensitive, like Jsoup
  assert.deepEqual(t('#main > h1'), ['Heading']);
  assert.deepEqual(t('div.card.big'), [d.selectFirst('div.card.big').text()]);
  assert.equal(d.select('div.card').length, 2);
  assert.deepEqual(t('h1 + p'), ['One bold & it']);
  assert.deepEqual(t('h1 ~ a'), ['A1', 'A2', 'PDF']);
  assert.deepEqual(t('tr:first-child td'), ['c1', 'c2']);
  assert.throws(() => H.parseHtml('').select('[['));
  assert.equal(H.parseHtml('<p>x</p>').select('nothing').length, 0);
});

test('select is scoped to a node, and navigation works', () => {
  const d = H.parseHtml(PAGE), card = d.select('div.card')[1];
  assert.equal(card.select('span').length, 1);
  assert.equal(card.select('a').length, 0); // not the first card's links
  const li = d.select('li')[1];
  assert.equal(li.prev().text(), 'first');
  assert.equal(li.next().text(), 'third');
  assert.equal(li.parent.tag, 'ul');
  assert.equal(d.selectFirst('ul').children.length, 3);
  assert.match(d.selectFirst('ul').html(), /<li class="x">second<\/li>/);
});
