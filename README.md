# VidBox for Vega

A provider for the [Vega](https://github.com/Zenda-Cross/vega-app) app (and for VyseTV, which can run Vega providers), in the
[vega-providers](https://github.com/Zenda-Cross/vega-providers) layout. It is the VidBox part of this repository's CloudStream
provider, rewritten as the five Vega modules.

```
manifest.json            one provider: "vidbox"
urls.json                where the site lives now (key "vidbox"): change the address here when the site moves
providers/vidbox/
  catalog.ts             the home rows (VidBox's own browse filters)
  posts.ts               a row's titles, and search (VidBox's /api/search/discover)
  meta.ts                a title's page: details, and the seasons of a series
  episodes.ts            the episodes of one season (TMDB, with the key the site itself uses)
  stream.ts              the embed servers: asked together, whatever answers is returned
  api.ts, crypto.ts      shared helpers (the page parser, the small AES / mvm1 / alphabet decoders, no libraries)
dist/vidbox/*.js         the built modules the app downloads (npm run build)
```

## Use it

Add this link as a source in Vega (Settings → Providers → add source), or as a repository in VyseTV (Settings → Plugins):

`https://raw.githubusercontent.com/satishgumudavelli/SatishCloudStreamPlugin/vega-provider-vidbox/manifest.json`

## Build

```
npm install
npm run build        # writes dist/vidbox/*.js (stamped with the manifest's version)
npm run typecheck
```

Raise `version` in `manifest.json` when you change a module, so apps download the new build.

## Servers

Asked together for a title's TMDB id; a server that is down or says no is skipped, and a line in the log says why.

| Server | Notes |
|---|---|
| Vidrock | AES-256-GCM tokens (decrypted without checking the tag: it only reads what the server sent) |
| Vidlink | the id is encoded by enc-dec.app first; mp4 files come with the Referer they need |
| Nxsha | CryptoJS passphrase tokens (new key, 2026-11-23); asks the server list, then each server. Embed-only servers are skipped |
| 111movies, Peachify, Vidnest, MovieNight, Movy, Vuflix, Xpass, 2Embed, Rive, Mplay | as the CloudStream provider did |

Left out: **Max** (needs a WebAssembly decryptor), **CinemaOS** (key derivation too slow without native crypto; it has its own
provider), **Frembed** (hands over to other sites' players), **Tongo** and **Bravo** (packed-script players that could not be
checked), **Videasy** (checked live on 2026-11-23: its seed endpoint is gone).

Checked live on 2026-11-23 (through Cloudflare WARP, on a network that resets the site's TLS): **Vidrock** (12 streams for a film and
for an episode), **Vidlink** and **Rive** (an episode), **Nxsha** (14 streams for a film, 16 for an episode: castle, awsind, nitro, streamflix, purstream, ophim answer; vixolity and watchout answer but their files returned 403/400; the other servers it lists return 404). Peachify was down (HTTP 523), Vidnest and MovieNight now ask for a token
the original code does not send, and 111movies, Movy and several Vidnest servers answered 403 to the WARP address used for the
check. They may work from another network; they are kept because the site can bring them back.

## Notes

* The site moved from `vidbox.vc` to `vidbox.wtf` (the old name redirects). On some networks `vidbox.wtf` resets TLS: use WARP or
  Anti-DPI in VyseTV's Settings → Network.
* The post title carries the year (`Oppenheimer (2023)`) so that a search by title can tell a remake from the original.
* No third-party libraries (a Vega provider cannot import any): the crypto in `crypto.ts` was checked against Node's `crypto` on
  random inputs when it was written.
