// ---- run guard (snippet: tools/run-guard.js) ----
//
// Paste this, unchanged, at the END of a plugin (after the domains snippet if it has one). tools/domains.test.mjs checks the copies.
//
// A plugin run has 20 s of script time and the app stops waiting at 25 s: a plugin still waiting for a slow host then loses everything
// it had found. This guard gives every run a time budget and a retry for flaky connections:
//   - each request's timeout is clipped to what is left of the budget (BUDGET_MS: 16 s unless the plugin sets its own earlier in the
//     file), and once the budget is used up requests fail at once (status 0, "out of time"), so the plugin finishes with what it has
//   - a GET whose connection was reset or refused is tried again, twice at most and only while 3 s are left: some networks reset
//     connections at random and a retry usually gets through. A request that got an answer, timed out, could not resolve its name,
//     or is not a GET is never retried (pass retry: false to switch it off for one request).
var DEADLINE = 0;
var BUDGET_MS = typeof BUDGET_MS === 'number' ? BUDGET_MS : 16000;
var _guardedHttp = http;
http = async function (url, opts) {
  opts = opts || {};
  var method = String(opts.method || 'GET').toUpperCase(), r;
  for (var attempt = 0; attempt < 3; attempt++) {
    var o = opts;
    if (DEADLINE) {
      var left = DEADLINE - Date.now();
      if (left < 400) return { status: 0, error: 'out of time', text: '', headers: {}, url: String(url) };
      o = Object.assign({}, opts, { timeout: Math.min(opts.timeout || 15000, left) });
    }
    r = await _guardedHttp(url, o);
    var why = String(r.error || '');
    if (r.status !== 0 || method !== 'GET' || opts.retry === false) return r;
    if (!/reset|ECONNRESET|Connection failed|connection closed|fetch failed|refused|EPIPE/i.test(why) || /ENOTFOUND|host lookup|timeout|out of time/i.test(why)) return r;
    if (DEADLINE && DEADLINE - Date.now() < 3000) return r;
  }
  return r;
};
var _guardedGetStreams = getStreams;
getStreams = async function (q) {
  DEADLINE = Date.now() + BUDGET_MS;
  return _guardedGetStreams(q);
};
