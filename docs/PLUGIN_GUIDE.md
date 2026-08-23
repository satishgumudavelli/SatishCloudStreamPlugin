# Writing a CloudStream plugin

Notes from building/maintaining VidboxProvider, plus a comparison against another
plugin on disk (`/var/www/html/Server_for_Korean_insta1/Pmsm`) that uses a
different pattern. Not official docs - just what's been confirmed by reading
the actual `cloudstream.jar` API and by live-testing this repo's extractors.

## 1. Project shape

```
build.gradle.kts          # root: applies com.lagradost.cloudstream3.gradle plugin
settings.gradle.kts       # includes each provider module
repo.json                 # repo manifest (name/description shown in CloudStream's repo list)
YourProvider/
  build.gradle.kts        # module: cloudstream { ... } block (name, description, iconUrl, ...)
  src/main/kotlin/com/you/
    YourProviderPlugin.kt # @CloudstreamPlugin entrypoint, registers everything
    YourProvider.kt       # MainAPI implementation
    YourExtractor.kt       # scraping/decryption logic (optional separate file)
```

Reference: `build.gradle.kts:1-60`, `VidboxProvider/src/main/kotlin/com/vidbox/`.

## 2. The plugin entrypoint

Two base classes exist: `Plugin()` (newer) and `BasePlugin()` (older/simpler).
This repo uses `BasePlugin`:

```kotlin
@CloudstreamPlugin
class VidboxProviderPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(VidboxProvider())
    }
}
```

`Plugin()` uses `override fun load(context: Context)` instead and can also
register `ExtractorApi` classes (see §4).

## 3. MainAPI - the provider itself

`MainAPI` subclasses implement the catalog + detail + link-resolution surface.
The methods CloudStream calls:

- `mainPage` - the home screen's category rows.
- `search(query)` - search results.
- `load(url)` - a movie/show's detail page → `LoadResponse`.
- `loadLinks(data, isCasting, subtitleCallback, callback)` - given the data
  string from `load()`, emit `ExtractorLink`s via `callback` and
  `SubtitleFile`s via `subtitleCallback`. Returns `Boolean` (found anything).

`VidboxProvider.kt:160-183` is a `loadLinks` that fans out to ~13 source
functions in parallel with `runAllAsync { ... }`, each one owning its own
network calls + decryption and calling `callback`/`subtitleCallback` directly.

## 4. Two ways to resolve a stream URL - pick the right one

This is the thing that's easy to get backwards.

**Pattern A - plain function, called directly.** If your provider's
`loadLinks` already knows which source to hit (because it's calling that
source's API directly, not receiving an arbitrary iframe/embed URL), write a
plain suspend function and call it by name. No registration needed.
This is what every function in `VidboxExtractor.kt` does
(`invokeVidlink`, `invokeNxsha`, etc.) - see `VidboxProvider.kt:167-180`.

**Pattern B - `ExtractorApi` subclass, auto-dispatched by URL.** If instead
you get back an arbitrary embed URL (e.g. a third-party aggregator hands you
`https://dhtpre.com/e/xyz` and you don't know ahead of time which of a dozen
embed hosts it'll be), extend `ExtractorApi` (or a shared base like
`VidhideExtractor`/`VidStack` that CloudStream ships), then call
`registerExtractorAPI(YourExtractor())` in `load()`. CloudStream's
`loadExtractor(url, referer, subtitleCallback, callback)` then matches the
URL's host against each registered extractor's `mainUrl` and picks the right
one automatically.

```kotlin
// Pmsm's pattern - PmsmPlugin.kt
override fun load(context: Context) {
    registerMainAPI(Pmsm())
    registerExtractorAPI(DhtprePmsm())   // : VidhideExtractor()
    registerExtractorAPI(Playerxupns())  // : VidStack()
    registerExtractorAPI(Larhu())        // : ExtractorApi()
    // ...
}
```

`loadExtractor` isn't only for your own registered extractors - CloudStream
ships a bunch of generic ones (Voe, Dood, Uqload, ...) already registered by
the app itself. `VidboxExtractor.invokeFrembed` (`VidboxExtractor.kt:283-304`)
leans on exactly that: Frembed hands back a redirect to some third-party
embed, and `loadExtractor(target, frembedApi, subtitleCallback, callback)`
resolves it without VidboxProvider needing to know or register anything.

Before writing a raw `ExtractorApi()` subclass for a new embed host, check
whether it's just a reskin of a host CloudStream already ships a base class
for - a survey of a large (80+ provider, 317-file) real-world CloudStream
extensions collection found ~380 `registerExtractorAPI` call sites, and the
large majority extend one of a handful of stdlib base classes rather than
raw `ExtractorApi()`: `StreamWishExtractor()` (31 uses), `Filesim()` (28),
`StreamSB()` (7), `AWSStream()` (7), `DoodLaExtractor()` (5), `Voe()` (4),
`Ridoo()` (3). Extending one of these instead of `ExtractorApi()` directly
usually means overriding just `mainUrl`/`name`, not re-implementing the
extraction logic.

**Rule of thumb:** if you're calling the source's API/URL yourself inside
`loadLinks`, use Pattern A. If you're being handed someone else's embed URL
and don't know its shape in advance, use Pattern B - and check for an
existing base class before extending `ExtractorApi()` from scratch.

## 5. Emitting results

```kotlin
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.newSubtitleFile
```

- **Direct file (mp4/etc.)**: build one `ExtractorLink` per quality/variant.
  ```kotlin
  callback(
      newExtractorLink("SourceName", "Display Name", fileUrl, ExtractorLinkType.VIDEO) {
          this.headers = mapOf("Referer" to "...")
          this.quality = 1080          // plain int, matches Qualities.P1080.value
      }
  )
  ```
- **HLS (m3u8)**: use `generateM3u8(source, streamUrl, referer, quality = null, headers = ...)`.
  It parses the manifest itself (including embedded subtitle/quality
  renditions), returning a `List<ExtractorLink>` - `forEach(callback)` it.
  A survey of a large real-world provider collection found 0 of 64
  `generateM3u8` call sites passing an explicit `quality` - manifest
  auto-detection is universally relied on there; only set `quality`
  explicitly when the API hands back separate per-resolution URLs (§6).
- **Subtitles**: prefer the builder `newSubtitleFile(lang, url) { this.headers = ... }`
  (`com.lagradost.cloudstream3.newSubtitleFile`) over constructing
  `SubtitleFile(lang, url)` directly - same style as `newExtractorLink`, and
  it's what real provider code actually uses when a subtitle track needs
  its own headers. Either way it only fires if your function actually
  accepts a `subtitleCallback` parameter and the caller threads it through -
  it's easy to add a new source function and forget to wire this up (see §7).

`Qualities` (`com.lagradost.cloudstream3.utils.Qualities`) is an enum
(`P144`...`P2160`, `Unknown`) but `ExtractorLink.quality` is just an `Int`.
Don't hand-roll a regex to turn a label like `"1080p"` into that int -
`getQualityFromName(label: String): Int` (`com.lagradost.cloudstream3.utils`)
already does this and is the repo-wide convention (confirmed in a real
80+ provider collection, e.g. `AnimePahe/.../Utils.kt:97`,
`Movies4u/.../Extractor.kt:365`) - it's even what the pre-fork ancestor of
this repo's own `VidboxExtractor.kt` used
(`this.quality = getQualityFromName("$res")`) before this repo's own
Videasy/Nxsha fixes reached for a hand-written `Regex("""\d+""")` instead.
Prefer the stdlib helper.

## 6. Video resolution and audio tracks

These two get missed constantly because the mechanism differs depending on
whether the source is HLS or direct files - see §7 for concrete bugs this
caused across this repo's extractors.

### Resolution

- **Single HLS manifest with multiple variants** (one `#EXT-X-STREAM-INF`
  per resolution, one `master.m3u8` URL): pass that one URL to
  `generateM3u8(...)`. It parses the manifest and returns one `ExtractorLink`
  per variant already tagged with the right quality - nothing else to do.
  Confirmed live on 111Movies' manifest (`#EXT-X-STREAM-INF:...,RESOLUTION=1920x800,...`
  next to 1280x534 and 640x266 variants in the same playlist).
- **API hands back separate URLs per resolution** (a `qualities` map, or a
  `sources` array where each entry has its own `quality`/`label`): this is
  direct-file territory even if the URL itself ends in `.m3u8` - each entry
  needs its **own** `ExtractorLink`/`generateM3u8` call with `quality` set
  from that entry, not one call for the whole array. Seen on Vidlink
  (`stream.qualities: {"360": {...}, "480": {...}, "1080": {...}}`, no
  `playlist` field at all for these titles) and Videasy/speedracelight
  (`sources: [{"quality":"1080p","url":"..."}, {"quality":"720p",...}]`).
- `quality` is sometimes a plain int (`1080`), sometimes a string with a unit
  (`"1080p"`), and sometimes **not a resolution at all** - Videasy's
  `lamovie`/`hdmovie` providers put `"Vimeos"`/`"Hindi"` in the same
  `quality` field. Don't hand-roll a digit-extracting regex for this -
  `getQualityFromName(label)` (§5) already handles messy labels and falls
  back to `Qualities.Unknown` on anything unrecognized.
- **Never merge multiple resolutions into a single link.** If the API gives
  you N URLs for N resolutions, emit N `ExtractorLink`s with the same
  `source`/name prefix but distinct `quality` - CloudStream's player
  presents these as the quality picker. Collapsing them into one call (or
  reusing the same `name` for all of them) makes the others invisible even
  though the network calls succeeded.

### Audio tracks / multi-language dubs

Two unrelated mechanisms both get called "audio tracks" - keep them separate:

1. **Separate dub language = separate `ExtractorLink`.** This is the pattern
   every source in this repo actually uses: a language-switch dropdown in
   CloudStream's player is really just several same-title links with
   different `name`s, one per language, that the player lets you flip
   between. The API detail differs per source but the fix is always
   "put the language in the link's name and emit one link per language,
   don't collapse them":
   - Vidrock: top-level JSON keys already are per-language sources
     (`{"Hindi": {...}, "Tamil": {...}, ...}`) - iterate all of them.
   - Peachify/Vidnest/Nxsha: a `dub`/`language`/`label` field on each
     source object - **must** go into the emitted link's `name`, or same
     source, different language, produces one link that silently overwrites
     the other in the UI (this exact bug existed in `invokeNxsha` - see §7).
   - This is by far the more common case - reach for this first.
2. **`ExtractorLink.audioTracks: List<AudioFile>`.** For the rarer case of a
   *single* video-only stream that needs a *separately hosted* audio-only
   file muxed in at playback time (detached audio elementary stream, not a
   dub choice). `AudioFile(url, headers)` - notably **no language field**,
   so if you use this for multiple dubs you'd have no way to label them in
   the CloudStream UI. A survey of a large real-world provider collection
   (80+ providers, 317 Kotlin files) found **zero** uses of `AudioFile(` or
   `.audioTracks` anywhere - every single one of those providers does
   multi-language dubs via option 1 instead. Treat `audioTracks` as
   effectively dead API surface for this use case; only reach for it if an
   API genuinely hands you video and audio as two separate files for what's
   presented as *one* stream/language, not as a way to offer a language
   choice.

## 7. The #1 recurring bug in this repo: silently dropped subtitles/quality

Across two review passes on VidboxProvider, the same class of bug kept
showing up in *every* source function:

1. **A JSON field genuinely exists in the API response** (a `subtitles`/
   `captions` array, a `quality` field, a `language` label) **but the Kotlin
   code never reads it** - it only pulls `url` and drops the rest on the
   floor.
2. **A response shape assumption is wrong for some providers/response types**
   and the code returns nothing at all rather than degrading - e.g. code
   written for `optJSONArray("streams")` silently no-ops when a provider
   actually returns a flat object, or code written for `stream.playlist`
   (HLS) silently no-ops when the same API returns `stream.qualities` (direct
   files) for a different title.

**Do not trust the original extractor author's assumption about response
shape - verify it live.** Before touching an extractor:

```bash
curl -s "<the exact URL the Kotlin code builds>" -H "Referer: ..." | python3 -m json.tool
```

then diff that against what the Kotlin code actually reads. If the code has
a fallback chain (`?: resolved.optString(...) ?: ...`), that's often a tell
that the shape was never confirmed live - check the comment for
"wasn't fully pinned down" or similar.

When you fix a source function that takes a `subtitleCallback`/needs one
added, remember to thread it through **both** ends:
- the function signature in the extractor file, and
- the call site in `MainAPI.loadLinks` (`VidboxProvider.kt`), which otherwise
  still passes the old argument list and won't compile - the compiler will
  catch this, but it's the first place to look if you add a param and get a
  type-mismatch error.

## 8. Reverse-engineering an encrypted/obfuscated API

Recurring shapes seen across this repo's sources:
- **AES-GCM token**: `base64url(nonce[12] + ciphertext + tag[16])`, fixed key
  (Vidrock). Decrypt with `Cipher.getInstance("AES/GCM/NoPadding")`.
- **CryptoJS.AES.encrypt(json, passphrase)**: OpenSSL `"Salted__"` + 8-byte
  salt + ciphertext, key/IV via `EVP_BytesToKey` (MD5-based) - see
  `VidboxCrypto.kt`'s `CryptoJsAes` object (Nxsha).
- **HMAC-derived per-request secret**: `HMAC(secondaryKey, HMAC(primaryKey, content))`
  sent as a query param, response optionally AES-GCM/PBKDF2-encrypted
  (Cinemaos).
- **Custom XOR keystream w/ seeded PRNG**: reverse-engineered from a webpack
  chunk, magic-byte-prefixed plaintext (`MvmCipher` / `mvm1`, Videasy/Vidking).
- **Shuffled base64 alphabet**: not real encryption, just an alphabet swap
  before standard base64 decode (Vidnest).

When reverse-engineering a new one: port the exact byte-for-byte algorithm to
a throwaway Python script first (fast iteration, real crypto libs), confirm
it round-trips against a live response, *then* port to Kotlin. Don't guess
the shape from the minified JS alone - the request/response often differs
subtly per endpoint (e.g. same passphrase, different param name).

## 9. Building & releasing

```bash
./gradlew :YourProvider:compileDebugKotlin   # fast correctness check while iterating
./gradlew make makePluginsJson               # produces the real .cs3 + build/plugins.json
```

This repo's release flow (mirrored from `.github/workflows/build.yml`, which
runs it automatically on push to master/main):

1. Commit source changes on `master`.
2. `./gradlew make makePluginsJson`.
3. `git checkout builds` (a separate branch that tracks only `*.cs3` +
   `plugins.json` - no source at all, see `.gitignore`'s `**/build` exclusion
   for why the build artifacts survive the branch switch).
4. Copy `<Module>/build/*.cs3` and `build/plugins.json` into the repo root.
5. Commit as `"Update <Module> build"`, matching the existing history's
   convention exactly (no `--amend`, unlike CI which force-amends).
6. `git checkout master` to keep working.

CloudStream's app polls `plugins.json` (via `repositoryUrl`) to know when to
offer users an update - `fileHash`/`fileSize` in it must match the `.cs3`
being served, which is why they're regenerated together, never hand-edited.

## 10. Useful local references

- The actual API surface (what methods/fields really exist, not what you
  remember): unzip `~/.gradle/caches/cloudstream/cloudstream/cloudstream.jar`
  and `javap -p` the class you care about, e.g.
  `com/lagradost/cloudstream3/utils/ExtractorLink.class`,
  `.../utils/Qualities.class`, `.../SubtitleFile.class`.
- `VidboxCrypto.kt` for copy-pasteable AES-GCM/CryptoJS/HMAC/PBKDF2 helpers.
- `VidboxExtractor.kt` for ~13 worked examples of source functions, each with
  a comment describing the auth/crypto scheme it reverse-engineers.
- `DomainResolver.kt` for a copy-pasteable "pick the first reachable domain
  for this site out of a shared `domains.json`" utility - useful for any
  site that's rotated domains before. It's deliberately provider-agnostic
  (constructor params, no hardcoded site name) since providers here don't
  depend on each other's Gradle modules - copy the file into a new
  provider's package and instantiate it with that provider's own
  `domainsJsonUrl`/`targetName`/`fallbackDomain`/`headers`, same as
  `VidboxScraper`'s `domainResolver` does. The root `domains.json` itself is
  already shared/keyed by `name`, so a new provider just needs its own
  `{"name": ..., "domain": ...}` entries added to the existing file, not a
  new one.
- **A large real-world multi-provider collection** at
  `/var/www/html/Server_for_Korean_insta1` (80+ provider modules, `Pmsm`
  referenced in §4) is a good place to check "is there already a stdlib/
  convention for this" before inventing something - e.g. it's how
  `getQualityFromName` and `newSubtitleFile` (§5) and the shared extractor
  base classes (§4) got confirmed as the real conventions rather than
  guesses. It also happens to contain an older ancestor copy of this exact
  plugin (`VidboxProvider/src/main/kotlin/com/vidbox/VidboxExtractor.kt`,
  pre-fork: `vidrock.ru` + AES-CBC + `moviesapi.club`, since replaced in
  this repo by `vidrock.net` + AES-GCM and other sources) - a real diff of
  how the same plugin's sources drift/rot over time as origin sites change.
