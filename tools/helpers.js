// VyseTV plugin helpers: global functions every plugin can call (the app loads this file before the plugin).
// Keep tools/helpers.js in the plugin repository identical: the test harness uses it.
//
//   searchQueries(query)            -> the strings to search a site for, best first
//   parseTitle(raw)                 -> { title, year, season } read out of a listing like "Dune (2021) 1080p S02"
//   titleScore(candidate, query)    -> 0..100, how well a listing's title matches the film or show
//   rankMatches(items, query, get)  -> matching items, best first (see below)
//   bestMatch(items, query, get)    -> the best matching item, or null
//   searchBest(query, search, get)  -> tries each search string until a match turns up; returns the item or null
//   searchByImdb(query, search, pageOf, accept) -> the last resort: search for the IMDb id, keep a result only if its page names that id
//   imdbMatches(text, query)        -> true / false / null: does a page name this film's IMDb id?
//
// `get(item)` returns a string (the listing's title) or { title, year?, season? }.

function normTitle(s) {
  s = String(s == null ? '' : s).toLowerCase();
  try { s = s.normalize('NFD').replace(/[\u0300-\u036f]/g, ''); } catch (e) {}
  return s.replace(/&/g, ' and ').replace(/[^a-z0-9]+/g, ' ').replace(/^ +| +$/g, '');
}

function _loose(s) { return s.replace(/\b(the|a|an)\b/g, ' ').replace(/ +/g, ''); }

function _stripArticle(s) { return s.replace(/^(the|a|an) /, ''); }

// Every name this title is known by (title, original title, TMDB's aliases), distinct, at most 6.
function titleList(query) {
  var out = [], seen = {};
  [query.title, query.originalTitle].concat(query.titles || []).forEach(function (t) {
    var n = normTitle(t), key = n.replace(/ /g, '');
    if (!key || seen[key] || out.length >= 6) return;
    seen[key] = 1;
    out.push(String(t));
  });
  return out;
}

// What to type into a site's search box, in the order to try: the title, the same without punctuation
// ("Dune: Part Two" -> "dune part two": many search boxes choke on colons), the original title and the
// first aliases; for a show past season 1 also "Title Season 2" and "Title S2".
function searchQueries(query) {
  var names = titleList(query), out = [], seen = {};
  function add(q) {
    q = String(q || '').replace(/^ +| +$/g, '');
    var k = q.toLowerCase();
    if (!q || seen[k] || out.length >= 10) return;
    seen[k] = 1;
    out.push(q);
  }
  if (names.length) {
    add(names[0]);
    var plain = normTitle(names[0]);
    if (plain !== names[0].toLowerCase()) add(plain);
  }
  names.slice(1, 4).forEach(function (n) { if (normTitle(n).replace(/ /g, '').length >= 4) add(n); });
  if (query.isTv && query.season > 1 && names.length) {
    add(names[0] + ' Season ' + query.season);
    add(names[0] + ' S' + query.season);
  }
  // last resorts (a request each, only reached when the strings above found nothing): the title with
  // its year (sites rank it better), and the title without its subtitle ("Dune: Part Two" -> "Dune")
  if (names.length) {
    if (!query.isTv && query.year) add(names[0] + ' ' + query.year);
    var head = names[0].split(/\s*[:\u2013\u2014]\s*|\s+-\s+/)[0];
    if (head !== names[0] && normTitle(head).replace(/ /g, '').length >= 4) add(head);
  }
  return out;
}

// "Oppenheimer (2023) BluRay 1080p Dual Audio" -> { title: 'Oppenheimer', year: 2023, season: null }
// "Breaking Bad Season 2 Complete"            -> { title: 'Breaking Bad', year: null, season: 2 }
function parseTitle(raw) {
  var s = String(raw == null ? '' : raw).replace(/\s+/g, ' ').replace(/^ +| +$/g, '');
  var year = null, season = null, m;
  var bracket = s.search(/[\(\[]/);
  var cuts = [];
  // the year: "(2023)" if there is one; otherwise the last bare 4-digit year ("Dune 2024", "Wonder Woman 1984 1080p")
  m = /[\(\[]((?:19|20)\d\d)[\)\]]/.exec(s);
  if (m) year = +m[1];
  else {
    var re = /(?:^|[ .])((?:19|20)\d\d)(?=$|[ .\-\)\]])/g, last = null, x;
    while ((x = re.exec(s))) last = x;
    if (last) { year = +last[1]; if (last.index > 0) cuts.push(last.index); }
  }
  m = /\bseasons? *0*(\d{1,2})\b/i.exec(s) || /\bS0*(\d{1,2})(?:E\d+)?\b/.exec(s);
  if (m) season = +m[1];
  if (bracket > 0) cuts.push(bracket);
  var tok = s.search(/\s-\s|\b(?:480|576|720|1080|2160|4k|uhd)p?\b|\bseasons?\s*\d|\bS\d{1,2}\b|\b(?:blu-?ray|web-?dl|webrip|hdrip|hdtv|dvdrip|brrip|x26[45]|hevc|dual audio|complete)\b/i);
  if (tok > 0) cuts.push(tok);
  var cut = cuts.length ? Math.min.apply(null, cuts) : -1;
  var title = (cut > 0 ? s.slice(0, cut) : s).replace(/[\s:\-\u2013\u2014,.]+$/g, '');
  return { title: title, year: year, season: season };
}

function _scoreAgainst(candidate, names) {
  var c = normTitle(candidate);
  if (!c) return 0;
  var cs = _stripArticle(c), calpha = c.replace(/ /g, ''), best = 0;
  names.forEach(function (t) {
    var q = normTitle(t);
    if (!q) return;
    var qs = _stripArticle(q);
    if (c === q || cs === qs || calpha === q.replace(/ /g, '')) best = Math.max(best, 100);
    else if (_loose(c) && _loose(c) === _loose(q)) best = Math.max(best, 90); // same once "the/a/an" are dropped anywhere
    else if (c.indexOf(q + ' ') === 0 || cs.indexOf(qs + ' ') === 0) best = Math.max(best, 60);
    else if (q.indexOf(c + ' ') === 0 && c.length >= 4) best = Math.max(best, 40);
  });
  return best;
}

// How well a listing's title is the one asked for, against every name the title is known by:
// 100 same name (ignoring case, punctuation, a leading "The"), 60 the listing continues the name
// ("Breaking Bad Season 2"), 40 the listing is the name's shorter form (the site leaves off a subtitle).
// 0 is no match. rankMatches() trusts the title and original title more than the aliases.
function titleScore(candidate, query) {
  return _scoreAgainst(candidate, titleList(query));
}

// A match through an alias (TMDB lists odd ones: working titles, other films' names). It counts when the
// listing's year fits (up to 70), or, for a listing that shows no year at all, when the alias is
// distinctive: two or more words, or 9+ letters (up to 50). Never as high as the real title.
function _aliasScore(title, query, yearFits, hasYear) {
  var best = 0;
  titleList(query).forEach(function (name) {
    var sc = _scoreAgainst(title, [name]);
    if (!sc) return;
    var words = normTitle(name).split(' ').length, letters = normTitle(name).replace(/ /g, '').length;
    if (yearFits) best = Math.max(best, Math.min(sc, 70));
    else if (!hasYear && (words >= 2 || letters >= 9)) best = Math.max(best, Math.min(sc, 50));
  });
  return best;
}

// Items that match the film or show, best first. A film's year must be within 1 of the listing's year
// when both are known; a show is never turned down for its year (an episode's year isn't the show's).
// A listing for the wrong season of a show ranks lower. `minScore` defaults to 40.
function rankMatches(items, query, get, minScore) {
  var out = [];
  var primary = [query.title, query.originalTitle].filter(Boolean);
  (items || []).forEach(function (item, i) {
    var g = get ? get(item) : item;
    var info = typeof g === 'string' ? parseTitle(g) : g || {};
    var title = info.title != null ? info.title : '';
    var year = info.year != null ? +info.year : null;
    var yearFits = !!(query.year && year && Math.abs(year - query.year) <= 1);
    var score = _scoreAgainst(title, primary);
    if (typeof g === 'string' && score === 0) score = _scoreAgainst(g, primary);
    if (!score) score = _aliasScore(title, query, yearFits, year != null);
    if (!score) return;
    if (!query.isTv && query.year && year && !yearFits) return;
    if (!query.isTv && yearFits && year === query.year) score += 20;
    if (query.isTv && query.season && info.season != null) score += info.season === query.season ? 15 : -15;
    if (score >= (minScore || 40)) out.push({ item: item, score: score, i: i });
  });
  out.sort(function (a, b) { return b.score - a.score || a.i - b.i; });
  return out.map(function (r) { return r.item; });
}

function bestMatch(items, query, get) {
  return rankMatches(items, query, get)[0] || null;
}

// Searches the site with each string from searchQueries() until a listing matches.
// `search(text)` returns the site's results as an array (an empty array when it has none).
async function searchBest(query, search, get) {
  var qs = searchQueries(query);
  for (var i = 0; i < qs.length; i++) {
    var items;
    try { items = await search(qs[i]); } catch (e) { items = []; }
    var m = bestMatch(items, query, get);
    if (m) return m;
  }
  return null;
}

// The last resort: ask the site's own search for the IMDb id (many sites keep it in the post, so this finds
// the film whatever its title is called). A result only counts when its PAGE names the same id: a site that
// ignores an unknown search term answers with recent posts. `search(text)` returns listings,
// `pageOf(item)` returns that listing's page text, `accept(item)` (optional) skips listings that can't be
// the one (the wrong season of a show). Returns { item, page } or null; tries at most 3 listings.
async function searchByImdb(query, search, pageOf, accept) {
  if (!query.imdbId) return null;
  var items;
  try { items = await search(query.imdbId); } catch (e) { return null; }
  items = (items || []).filter(function (it) { return !accept || accept(it); }).slice(0, 3);
  for (var i = 0; i < items.length; i++) {
    var page = '';
    try { page = await pageOf(items[i]); } catch (e) {}
    if (imdbMatches(page, query) === true) return { item: items[i], page: page };
  }
  return null;
}

// Does a page name this film's IMDb id? true: it does (a sure match). false: it names other ids but not
// this one (a different film: skip it). null: no id on the page, or none known: go by the title.
function imdbMatches(text, query) {
  if (!query.imdbId) return null;
  var found = String(text || '').match(/\btt\d{7,9}\b/g);
  if (!found || !found.length) return null;
  return found.indexOf(query.imdbId) >= 0;
}

// ───────────────────────── HTML (a small stand-in for BeautifulSoup / Jsoup) ─────────────────────────
//
//   var doc = parseHtml(html);            // a tree; nothing is fetched
//   doc.select('div.card a[href^=http]')  // all matches, in page order (an array of nodes)
//   doc.selectFirst('h1.entry-title')     // the first match, or null
//   node.attr('href')                     // the attribute ('' when missing); also node.hasAttr(name)
//   node.text()                           // the text inside, tags dropped, entities decoded, spaces collapsed
//   node.data()                           // the raw text inside (the script of a <script>)
//   node.html()                           // the markup inside
//   node.tag, node.parent, node.children (elements only), node.next() / node.prev() (sibling elements)
//   unescapeHtml('Tom &amp; Jerry')       // entities decoded
//
// Selectors: tag, *, .class, #id, [attr], [attr=v], [attr^=v], [attr$=v], [attr*=v], [attr~=v], compound
// (a.btn[href]), descendant (a b), child (a > b), next sibling (a + b), later sibling (a ~ b), lists (a, b),
// :contains(text), :first-child, :last-child, :nth-child(n | odd | even), :not(compound).
// Parsing a very large page in the app takes a moment: parse once and select from the tree.

var _VOID = { area: 1, base: 1, br: 1, col: 1, embed: 1, hr: 1, img: 1, input: 1, link: 1, meta: 1, param: 1, source: 1, track: 1, wbr: 1 };
var _RAW = { script: 1, style: 1, textarea: 1 };
// an opening tag that closes an unfinished one of these
var _CLOSES = { li: { li: 1 }, p: { p: 1 }, tr: { tr: 1, td: 1, th: 1 }, td: { td: 1, th: 1 }, th: { td: 1, th: 1 }, option: { option: 1 }, dt: { dt: 1, dd: 1 }, dd: { dt: 1, dd: 1 } };
var _ENT = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ', ndash: '–', mdash: '—', hellip: '…', rsquo: '’', lsquo: '‘', rdquo: '”', ldquo: '“', copy: '©', laquo: '«', raquo: '»', middot: '·', bull: '•', times: '×', eacute: 'é', egrave: 'è', aacute: 'á', ntilde: 'ñ', uuml: 'ü', ouml: 'ö', auml: 'ä' };

function unescapeHtml(s) {
  return String(s == null ? '' : s).replace(/&(#x[0-9a-f]+|#\d+|[a-z][a-z0-9]*);/gi, function (m, e) {
    if (e.charAt(0) === '#') {
      var n = e.charAt(1).toLowerCase() === 'x' ? parseInt(e.slice(2), 16) : parseInt(e.slice(1), 10);
      try { return String.fromCodePoint(n); } catch (x) { return m; }
    }
    var v = _ENT[e.toLowerCase()];
    return v === undefined ? m : v;
  });
}

function _Node(tag, attrs, parent) { this.tag = tag; this.attrs = attrs; this.parent = parent; this.kids = []; }
_Node.prototype.attr = function (n) { var v = this.attrs[String(n).toLowerCase()]; return v == null ? '' : v; };
_Node.prototype.hasAttr = function (n) { return this.attrs[String(n).toLowerCase()] != null; };
Object.defineProperty(_Node.prototype, 'children', { get: function () { return this.kids.filter(function (k) { return typeof k !== 'string'; }); } });
_Node.prototype._index = function () { return this.parent ? this.parent.children.indexOf(this) : 0; };
_Node.prototype.next = function () { if (!this.parent) return null; var c = this.parent.children; return c[c.indexOf(this) + 1] || null; };
_Node.prototype.prev = function () { if (!this.parent) return null; var c = this.parent.children; return c[c.indexOf(this) - 1] || null; };
_Node.prototype.data = function () {
  return this.kids.map(function (k) { return typeof k === 'string' ? k : k.data(); }).join('');
};
_Node.prototype.text = function () {
  var out = [];
  if (this.tag === 'script' || this.tag === 'style') return ''; // their text is data(), not text()
  (function walk(n) {
    n.kids.forEach(function (k) {
      if (typeof k === 'string') out.push(k);
      else if (k.tag !== 'script' && k.tag !== 'style') walk(k);
    });
  })(this);
  return unescapeHtml(out.join('')).replace(/[\s ]+/g, ' ').replace(/^ +| +$/g, '');
};
_Node.prototype.html = function () {
  return this.kids.map(function (k) { return typeof k === 'string' ? k : k.outerHtml(); }).join('');
};
_Node.prototype.outerHtml = function () {
  var a = Object.keys(this.attrs).map(function (k) { return ' ' + k + '="' + String(this.attrs[k]).replace(/"/g, '&quot;') + '"'; }, this).join('');
  return _VOID[this.tag] ? '<' + this.tag + a + '>' : '<' + this.tag + a + '>' + this.html() + '</' + this.tag + '>';
};
_Node.prototype.select = function (css) {
  var groups = _parseSelector(css), out = [], self = this;
  (function walk(n) {
    n.children.forEach(function (k) {
      for (var g = 0; g < groups.length; g++) {
        if (_matchChain(k, groups[g], groups[g].length - 1, self)) { out.push(k); break; }
      }
      walk(k);
    });
  })(this);
  return out;
};
_Node.prototype.selectFirst = function (css) { return this.select(css)[0] || null; };

function parseHtml(html) {
  html = String(html == null ? '' : html);
  var root = new _Node('#root', {}, null), cur = root, last = 0, m;
  var re = /<!--[\s\S]*?-->|<!\[CDATA\[[\s\S]*?\]\]>|<![^>]*>|<\?[^>]*>|<\/([a-zA-Z][^\s>\/]*)[^>]*>|<([a-zA-Z][^\s\/>]*)((?:"[^"]*"|'[^']*'|[^>"'])*)>/g;
  function text(to) {
    if (to > last) cur.kids.push(html.slice(last, to));
  }
  while ((m = re.exec(html))) {
    text(m.index);
    last = re.lastIndex;
    if (m[1]) { // a closing tag: close the nearest open one of that name
      var name = m[1].toLowerCase(), n = cur;
      while (n && n.tag !== name) n = n.parent;
      if (n && n.parent) cur = n.parent;
    } else if (m[2]) { // an opening tag
      var tag = m[2].toLowerCase(), attrText = m[3] || '', closes = _CLOSES[tag];
      while (closes && cur.parent && closes[cur.tag]) cur = cur.parent;
      var attrs = {}, am, are = /([^\s=\/"'>]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+)))?/g;
      while ((am = are.exec(attrText))) {
        var key = am[1].toLowerCase();
        if (attrs[key] == null) attrs[key] = unescapeHtml(am[2] != null ? am[2] : am[3] != null ? am[3] : am[4] != null ? am[4] : '');
      }
      var node = new _Node(tag, attrs, cur);
      cur.kids.push(node);
      if (_RAW[tag]) {
        var end = html.toLowerCase().indexOf('</' + tag, re.lastIndex);
        if (end < 0) end = html.length;
        if (end > re.lastIndex) node.kids.push(html.slice(re.lastIndex, end));
        var close = html.indexOf('>', end);
        re.lastIndex = last = close < 0 ? html.length : close + 1;
      } else if (!_VOID[tag] && !/\/\s*$/.test(attrText)) {
        cur = node;
      }
    }
  }
  text(html.length);
  return root;
}

// ── selectors ──

function _parseSelector(css) {
  var groups = [], s = String(css || '').replace(/^\s+|\s+$/g, ''), i = 0;
  function ident() { var m = /^[^\s.#\[\]:>+~,()=*^$|"']+/.exec(s.slice(i)); if (!m) return ''; i += m[0].length; return m[0]; }
  function compound() {
    var c = { tag: null, id: null, classes: [], attrs: [], pseudos: [] }, any = false;
    if (s.charAt(i) === '*') { i++; any = true; } else { var t = ident(); if (t) { c.tag = t.toLowerCase(); any = true; } }
    for (;;) {
      var ch = s.charAt(i);
      if (ch === '.') { i++; c.classes.push(ident()); any = true; }
      else if (ch === '#') { i++; c.id = ident(); any = true; }
      else if (ch === '[') {
        var end = s.indexOf(']', i), body = s.slice(i + 1, end), m = /^\s*([^\s~|^$*=\]]+)\s*(?:([~|^$*]?=)\s*(?:"([^"]*)"|'([^']*)'|([^\]]*?))\s*)?$/.exec(body);
        if (!m) throw new Error('bad selector: ' + css);
        c.attrs.push({ n: m[1].toLowerCase(), op: m[2] || '', v: m[3] != null ? m[3] : m[4] != null ? m[4] : m[5] != null ? m[5] : '' });
        i = end + 1; any = true;
      } else if (ch === ':') {
        i++;
        var name = ident().toLowerCase(), arg = null;
        if (s.charAt(i) === '(') {
          var depth = 1, j = i + 1;
          while (j < s.length && depth) { if (s.charAt(j) === '(') depth++; else if (s.charAt(j) === ')') depth--; j++; }
          arg = s.slice(i + 1, j - 1); i = j;
        }
        if (name === 'not') arg = _parseSelector(arg);
        else if (name === 'contains' || name === '-soup-contains') { name = 'contains'; arg = String(arg).replace(/^\s*["']?|["']?\s*$/g, '').toLowerCase(); }
        c.pseudos.push({ name: name, arg: arg }); any = true;
      } else break;
    }
    if (!any) throw new Error('bad selector: ' + css);
    return c;
  }
  while (i < s.length) {
    var chain = [], comb = ' ';
    for (;;) {
      var c = compound(); c.comb = comb; chain.push(c);
      var ws = /^\s*/.exec(s.slice(i))[0]; i += ws.length;
      var ch = s.charAt(i);
      if (ch === ',' || i >= s.length) break;
      if (ch === '>' || ch === '+' || ch === '~') { comb = ch; i++; i += /^\s*/.exec(s.slice(i))[0].length; }
      else comb = ' ';
    }
    groups.push(chain);
    if (s.charAt(i) === ',') { i++; i += /^\s*/.exec(s.slice(i))[0].length; }
  }
  return groups;
}

function _matchOne(n, c) {
  if (c.tag && n.tag !== c.tag) return false;
  if (c.id != null && n.attr('id') !== c.id) return false;
  if (c.classes.length) {
    var have = (' ' + n.attr('class').replace(/\s+/g, ' ') + ' ');
    for (var k = 0; k < c.classes.length; k++) if (have.indexOf(' ' + c.classes[k] + ' ') < 0) return false;
  }
  for (var a = 0; a < c.attrs.length; a++) {
    var at = c.attrs[a], v = n.attrs[at.n];
    if (v == null) return false;
    if (at.op === '=' && v !== at.v) return false;
    if (at.op === '^=' && (at.v === '' || v.indexOf(at.v) !== 0)) return false;
    if (at.op === '$=' && (at.v === '' || v.slice(-at.v.length) !== at.v)) return false;
    if (at.op === '*=' && (at.v === '' || v.indexOf(at.v) < 0)) return false;
    if (at.op === '~=' && (' ' + v.replace(/\s+/g, ' ') + ' ').indexOf(' ' + at.v + ' ') < 0) return false;
    if (at.op === '|=' && v !== at.v && v.indexOf(at.v + '-') !== 0) return false;
  }
  for (var p = 0; p < c.pseudos.length; p++) {
    var ps = c.pseudos[p], sib, idx;
    if (ps.name === 'contains') { if (n.text().toLowerCase().indexOf(ps.arg) < 0) return false; }
    else if (ps.name === 'not') { if (ps.arg.some(function (g) { return g.length === 1 && _matchOne(n, g[0]); })) return false; }
    else if (ps.name === 'first-child') { if (n.prev()) return false; }
    else if (ps.name === 'last-child') { if (n.next()) return false; }
    else if (ps.name === 'nth-child') {
      sib = n.parent ? n.parent.children : [n]; idx = sib.indexOf(n) + 1;
      var arg = String(ps.arg).replace(/\s+/g, '').toLowerCase();
      if (arg === 'odd') { if (idx % 2 !== 1) return false; }
      else if (arg === 'even') { if (idx % 2 !== 0) return false; }
      else if (/^\d+$/.test(arg)) { if (idx !== +arg) return false; }
      else return false;
    } else return false;
  }
  return true;
}

function _matchChain(n, chain, i, scope) {
  var c = chain[i];
  if (!_matchOne(n, c)) return false;
  if (i === 0) return true;
  var comb = c.comb, p;
  if (comb === '>') { p = n.parent; return !!p && p !== scope.parent && p.tag !== '#root' && _matchChain(p, chain, i - 1, scope); }
  if (comb === ' ') {
    for (p = n.parent; p && p.tag !== '#root'; p = p.parent) if (_matchChain(p, chain, i - 1, scope)) return true;
    return false;
  }
  if (comb === '+') { p = n.prev(); return !!p && _matchChain(p, chain, i - 1, scope); }
  if (comb === '~') { for (p = n.prev(); p; p = p.prev()) if (_matchChain(p, chain, i - 1, scope)) return true; return false; }
  return false;
}
