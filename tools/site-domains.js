// ---- domains.json (snippet: tools/site-domains.js) ----
//
// Sites change domain often. The repository keeps one file, domains.json, that lists the current domains
// ({"domains": [{"name": "Moviesmod", "domain": "moviesmod.fi"}, ...]}, a name may appear several times, the first
// is preferred), so a rotation is fixed by editing that file: no plugin update. A plugin keeps its own list as
// the fallback:
//
//   var DOMAINS = ['https://moviesmod.fi'];
//   async function getStreams(q) {
//     DOMAINS = await siteDomains('Moviesmod', DOMAINS);   // listed ones first, then the plugin's own
//
// The file is read once per run (3 s limit); when it can't be read the plugin's own list is used.

var DOMAINS_URL = 'https://raw.githubusercontent.com/satishgumudavelli/SatishCloudStreamPlugin/refs/heads/master/domains.json';
var _domainsFile = null;

async function siteDomains(name, fallback, url) {
  var out = [], seen = {};
  function add(d) {
    d = String(d == null ? '' : d).replace(/^ +| +$/g, '').replace(/\/+$/g, '');
    if (!d) return;
    if (!/^https?:\/\//i.test(d)) d = 'https://' + d;
    var k = d.toLowerCase();
    if (seen[k]) return;
    seen[k] = 1;
    out.push(d);
  }
  try {
    if (!_domainsFile) {
      var r = await http(url || DOMAINS_URL, { timeout: 3000 });
      _domainsFile = r.status === 200 ? JSON.parse(r.text) : { domains: [] };
    }
    (_domainsFile.domains || []).forEach(function (x) {
      if (x && String(x.name).toLowerCase() === String(name).toLowerCase()) add(x.domain);
    });
  } catch (e) {
    _domainsFile = { domains: [] }; // not readable: ask once, then go on with the plugin's own
  }
  (fallback || []).forEach(add);
  return out;
}
