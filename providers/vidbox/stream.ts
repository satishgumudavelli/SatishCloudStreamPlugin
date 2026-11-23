import { ProviderContext, Stream, TextTracks } from "../types";
import { decodeTarget, extractBalanced, getJson, getText } from "./api";
import {
  aesGcmDecryptNoTag,
  base64UrlToBytes,
  hexToBytes,
  mvmDecode,
  utf8Decode,
  vidnestDecode,
} from "./crypto";

// VidBox's player asks many embed servers for the same TMDB id and lists what each one returns. This does the same: every server below
// is asked at once, a server that fails or has nothing is skipped, and what is found is returned together.
//
// Left out on purpose: the Max server (needs a WebAssembly decryptor), CinemaOS (key derivation too slow without native crypto; the
// CinemaOS provider covers it), Frembed (hands over to other sites' players), Tongo and Bravo (packed-script players that could not
// be checked live), and Videasy and Nxsha (checked live on 2026-11-23: Videasy's seed endpoint is gone, Nxsha's passphrase changed).

type Job = {
  id: number;
  season?: number;
  episode?: number;
  title: string;
  year?: number;
  pc: ProviderContext;
  signal?: AbortSignal;
};

type Server = (j: Job) => Promise<Stream[]>;

/** Why a server gave nothing: a line in the plugin log, never an error (a dead server is normal). */
const why = (name: string, e: any): [] => {
  console.log(`${name}: ${String(e?.message || e).slice(0, 100)}`);
  return [];
};

const isTv = (j: Job) => j.season !== undefined && j.season !== null;
const qs = (pairs: [string, string][]) => pairs.map(([k, v]) => `${k}=${encodeURIComponent(v)}`).join("&");
const get = (j: Job, url: string, h: Record<string, string> = {}) => getText(j.pc, url, h, j.signal);
const json = (j: Job, url: string, h: Record<string, string> = {}) => getJson(j.pc, url, h, j.signal);

const qualityOf = (label: unknown): string | undefined => {
  const s = String(label ?? "");
  const m = s.match(/(\d{3,4})\s*p?\b/i);
  if (m && /^(240|360|480|540|720|1080|1440|2160)$/.test(m[1])) return m[1];
  return /4k/i.test(s) ? "2160" : undefined;
};

const tracks = (list: any, url: (s: any) => string | undefined, lang: (s: any) => string | undefined): TextTracks | undefined => {
  if (!Array.isArray(list)) return undefined;
  const out: TextTracks = [];
  for (const s of list) {
    const uri = url(s);
    if (!uri || !/^https?:/i.test(uri)) continue;
    out.push({ title: lang(s) || "Unknown", language: lang(s) || "Unknown", type: /\.srt(\?|$)/i.test(uri) ? "application/x-subrip" : "text/vtt", uri });
  }
  return out.length ? out : undefined;
};

/** One stream: a playlist when the address or [kind] says so, else a file. */
const stream = (server: string, link: string, opts: { kind?: string; quality?: string; headers?: Record<string, string>; subtitles?: TextTracks } = {}): Stream => ({
  server,
  link,
  type: opts.kind === "hls" || opts.kind === "m3u8" || (!opts.kind && /\.m3u8(\?|$)/i.test(link)) || /\.m3u8(\?|$)/i.test(link) ? "m3u8" : "mp4",
  quality: opts.quality,
  headers: opts.headers,
  subtitles: opts.subtitles,
});

const headersOf = (h: any): Record<string, string> | undefined => {
  if (!h || typeof h !== "object") return undefined;
  const out: Record<string, string> = {};
  for (const k of Object.keys(h)) if (typeof h[k] === "string") out[k] = h[k];
  return Object.keys(out).length ? out : undefined;
};

// ── Rock (vidrock.net): each stream address is its own AES-256-GCM token: base64url(nonce[12] + ciphertext + tag[16]) ──
const VIDROCK = "https://vidrock.net";
const VIDROCK_KEY = hexToBytes("7f3e9c2a8b5d1f4e6a9c3b7d2e5f8a1c4b6d9e2f5a8c1b4d7e9f2a5c8b1d4e7f");

const vidrock: Server = async (j) => {
  const data = await json(j, isTv(j) ? `${VIDROCK}/api/tv/${j.id}/${j.season}/${j.episode}` : `${VIDROCK}/api/movie/${j.id}`);
  const headers = { Referer: `${VIDROCK}/` };
  const out: Stream[] = [];
  for (const key of Object.keys(data || {})) {
    const o = data[key];
    const token = o && typeof o.url === "string" ? o.url : "";
    if (!token || token === "null") continue;
    try {
      const raw = base64UrlToBytes(token);
      const url = utf8Decode(aesGcmDecryptNoTag(VIDROCK_KEY, raw.subarray(0, 12), raw.subarray(12, raw.length - 16)));
      if (/^https?:\/\//.test(url)) out.push(stream(`Vidrock [${key}] ${o.language || "Unknown"}`, url, { headers }));
    } catch (_) {}
  }
  return out;
};

// ── Echo (vidlink.pro): the id is encoded by enc-dec.app first ──
const VIDLINK = "https://vidlink.pro";

const vidlink: Server = async (j) => {
  const enc = (await json(j, `https://enc-dec.app/api/enc-vidlink?text=${j.id}`))?.result;
  if (!enc) return [];
  const headers = { Referer: `${VIDLINK}/`, Origin: VIDLINK };
  const url = isTv(j) ? `${VIDLINK}/api/b/tv/${enc}/${j.season}/${j.episode}` : `${VIDLINK}/api/b/movie/${enc}`;
  const s = (await json(j, url, headers))?.stream;
  if (!s) return [];
  const subtitles = tracks(s.captions, (c) => c.url, (c) => c.language);
  if (typeof s.playlist === "string" && s.playlist) {
    const hdr = s.playlist.match(/[?&]headers=([^&]+)/)?.[1];
    let referer = `${VIDLINK}/`;
    try {
      if (hdr) referer = JSON.parse(decodeURIComponent(hdr)).referer || referer;
    } catch (_) {}
    return [stream("Vidlink", s.playlist.split("?")[0], { kind: "hls", headers: { ...headers, Referer: referer }, subtitles })];
  }
  const out: Stream[] = [];
  for (const k of Object.keys(s.qualities || {})) {
    const u = s.qualities[k]?.url;
    // the CDN wants the Referer and Origin the answer names for that file, not vidlink's
    if (u) out.push(stream(`Vidlink ${k}p`, u, { kind: /\.m3u8/.test(u) ? "hls" : "mp4", quality: qualityOf(k), headers: headersOf(s.qualities[k]?.headers) || headers, subtitles }));
  }
  return out;
};

// ── Movy (api.wecollege.net): the "mvm1" keystream backend (the same cipher served Videasy, whose endpoint is gone) ──
const mvmServer =
  (name: string, api: string, servers: [string, string | null][], path: (srv: string) => string, withTitle: boolean, hdr: Record<string, string>): Server =>
  async (j) => {
    if (!j.title) return [];
    const seed = (await json(j, `${api}/seed?mediaId=${j.id}`, hdr))?.seed;
    if (!seed) return [];
    const lists = await Promise.all(
      servers.map(async ([srv, extra]): Promise<Stream[]> => {
        const pairs: [string, string][] = [["title", j.title], ["mediaType", isTv(j) ? "tv" : "movie"], ["tmdbId", String(j.id)]];
        if (withTitle) pairs.push(["imdbId", ""]);
        pairs.push(["year", j.year ? String(j.year) : ""], ["enc", "2"], ["seed", seed]);
        if (isTv(j)) pairs.push(["seasonId", String(j.season)], ["episodeId", String(j.episode)]);
        let query = qs(pairs);
        if (extra) query += `&${extra}`;
        try {
          const body = await get(j, `${api}/${path(srv)}?${query}`, hdr);
          const data = JSON.parse(mvmDecode(body.trim(), seed, j.id));
          const subtitles = tracks(data.subtitles, (s) => s.url, (s) => s.lang || s.language);
          return (data.sources || [])
            .filter((s: any) => s?.url)
            .map((s: any) => stream(`${name} [${srv}] ${s.quality ?? ""}`.trim(), s.url, { kind: "hls", quality: qualityOf(s.quality), headers: name === "Movy" ? hdr : undefined, subtitles }));
        } catch (e) {
          return why(`${name} ${srv}`, e);
        }
      }),
    );
    return lists.flat();
  };

const browserUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

const movy = mvmServer(
  "Movy",
  "https://api.wecollege.net",
  [
    ["miami", null], ["seattle", null], ["denver", null], ["chicago", null], ["dallas", null], ["atlanta", null], ["houston", null],
    ["austin", null], ["boston", null], ["munich", "language=german"], ["berlin", null], ["paris", null], ["delhi", null], ["cancun", null],
  ],
  (srv) => `${srv}/sources`,
  false,
  { "User-Agent": browserUa, Referer: "https://www.movy.bz/", Origin: "https://www.movy.bz" },
);

// ── MovieNight (movienig.ht): one text/event-stream answer per server; the "event: done" block holds the sources ──
const MOVIENIGHT = "https://movienig.ht";

const movieNight: Server = async (j) => {
  if (!j.title) return [];
  const hdr = { "User-Agent": browserUa, Referer: `${MOVIENIGHT}/`, Origin: MOVIENIGHT, Accept: "text/event-stream" };
  const servers: [string, string][] = [["dallas", "Dallas 4K"], ["austin", "Austin"], ["helena", "Helena"], ["seattle", "Seattle 4K"], ["vixsrc-1", "Newport Beach"], ["tucson", "Tucson"], ["salem", "Salem"]];
  const lists = await Promise.all(
    servers.map(async ([id, label]): Promise<Stream[]> => {
      const path = isTv(j) ? `tv/${j.id}/${j.season}/${j.episode ?? 1}` : `movie/${j.id}`;
      try {
        const body = await get(j, `${MOVIENIGHT}/api/stream/v1/${path}?title=${encodeURIComponent(j.title)}${j.year ? `&year=${j.year}` : ""}&server=${id}&only=1`, hdr);
        const done = body.indexOf("event: done");
        const data = done === -1 ? -1 : body.indexOf("data: ", done);
        if (data === -1) return [];
        const start = data + 6;
        const end = body.indexOf("\n", start);
        const o = JSON.parse(body.substring(start, end === -1 ? body.length : end).trim());
        const subtitles = tracks(o.subtitles, (s) => s.url, (s) => s.label || s.language);
        return (o.sources || [])
          .filter((s: any) => s?.url)
          .map((s: any) => stream(`MovieNight ${label} (${s.quality || "Auto"})`, s.url, { kind: (s.type || "hls") === "hls" ? "hls" : "mp4", quality: qualityOf(s.quality), headers: hdr, subtitles }));
      } catch (e) {
        return why(`movienight ${id}`, e);
      }
    }),
  );
  return lists.flat();
};

// ── Vuflix (vuflix.co): the site lists its providers, each gives sources; an address may be a relay token holding the real one ──
const VUFLIX = "https://vuflix.co";
const vuflixHeaders = { "User-Agent": browserUa, Referer: `${VUFLIX}/`, Origin: VUFLIX, Accept: "application/json, text/plain, */*" };
const VUFLIX_FALLBACK = ["vsembed", "moonflix", "megasource", "hdghar", "moviebox", "cineplay", "huhu", "bingr", "onlyflix", "vaplayer", "flixhqz", "castle", "cinejoy", "filesun", "yoru", "fzmovies", "opstream", "yesmovies", "torrentio", "netmirror", "hdhub4u"];

const unwrapRelay = (raw: string): { url: string; headers: Record<string, string> } => {
  if (!/(v|a)-relay\?t=/.test(raw)) return { url: raw, headers: vuflixHeaders };
  try {
    const t = raw.split("t=")[1].split("&")[0];
    const o = JSON.parse(utf8Decode(base64UrlToBytes(t)));
    if (o.u) return { url: o.u, headers: { ...vuflixHeaders, ...(headersOf(o.h) || {}) } };
  } catch (_) {}
  return { url: raw, headers: vuflixHeaders };
};

const vuflix: Server = async (j) => {
  let providers = VUFLIX_FALLBACK;
  try {
    const p = await json(j, `${VUFLIX}/api/player/providers`, vuflixHeaders);
    const ids = p?.ok && Array.isArray(p.providers) ? p.providers.map((x: any) => x?.id).filter(Boolean) : [];
    if (ids.length) providers = ids;
  } catch (_) {}
  const type = isTv(j) ? "tv" : "movie";
  const se = isTv(j) ? `&season=${j.season}&episode=${j.episode ?? 1}` : "";
  const lists = await Promise.all(
    providers.map(async (provider): Promise<Stream[]> => {
      try {
        const r = await json(j, `${VUFLIX}/api/player/sources?type=${type}&tmdbId=${j.id}${se}&provider=${provider}`, vuflixHeaders);
        if (!r?.ok || !Array.isArray(r.sources)) return [];
        const out: Stream[] = [];
        for (const item of r.sources) {
          const name = item.providerName || item.publicLabel || provider;
          const emit = (raw: unknown, quality: string, extra: string) => {
            if (typeof raw !== "string" || !raw) return;
            const { url, headers } = unwrapRelay(raw);
            out.push(stream(`Vuflix [${name}] ${quality}${extra}`, url, { kind: /\.m3u8/.test(url) || (item.type || "hls") === "hls" ? "hls" : "mp4", quality: qualityOf(quality), headers }));
          };
          (item.qualities || []).forEach((q: any) => emit(q?.url, q?.quality || "Auto", ""));
          (item.candidates || []).forEach((c: any, i: number) => emit(c?.url, c?.quality || "1080p", ` Mirror ${i + 1}`));
          (item.audioTracks || []).forEach((t: any) => emit(t?.switchUrl || t?.url, t?.label || t?.language || "Audio", " Audio"));
          emit(item.url, item.quality || "HD", "");
        }
        return out;
      } catch (e) {
        return why(`vuflix ${provider}`, e);
      }
    }),
  );
  return lists.flat();
};

// ── 111movies (api.shows.st behind player.vidlove.cc) ──
const movies111: Server = async (j) => {
  const headers = { Referer: "https://vidlove.cc/", Origin: "https://vidlove.cc" };
  const url = isTv(j) ? `https://api.shows.st/tv?id=${j.id}&season=${j.season}&episode=${j.episode}&mode=json` : `https://api.shows.st/movie?id=${j.id}&mode=json`;
  const o = await json(j, url, headers);
  const link = o?.source?.url;
  return link ? [stream("111Movies", link, { kind: "hls", headers, subtitles: tracks(o.subtitles, (s) => s.file, (s) => s.label) })] : [];
};

// ── Peachify (none.eat-peach.sbs): AES-256-GCM addresses written "iv.ciphertext.tag" in base64url ──
const PEACH = "https://none.eat-peach.sbs";
const PEACH_KEY = hexToBytes("a8f2a1b5e9c470814f6b2c3a5d8e7f9c1a2b3c4d5e3f7a8b8cad1e2d0a4d5c5d");

const peachify: Server = async (j) => {
  const headers = { Referer: "https://peachify.top/", Origin: "https://peachify.top" };
  const lists = await Promise.all(
    ["air", "holly", "moviebox"].map(async (srv): Promise<Stream[]> => {
      try {
        const o = await json(j, isTv(j) ? `${PEACH}/${srv}/tv/${j.id}/${j.season}/${j.episode}` : `${PEACH}/${srv}/movie/${j.id}`, headers);
        if (!Array.isArray(o?.sources)) return [];
        const subtitles = tracks(o.subtitles, (s) => s.url || s.file, (s) => s.lang || s.label);
        const out: Stream[] = [];
        for (const s of o.sources) {
          if (!s?.url) continue;
          let url: string = s.url;
          if (o.isEncrypted) {
            const p = url.split(".");
            if (p.length !== 3) continue;
            try {
              url = utf8Decode(aesGcmDecryptNoTag(PEACH_KEY, base64UrlToBytes(p[0]), base64UrlToBytes(p[1])));
            } catch (_) {
              continue;
            }
          }
          if (!/^https?:\/\//.test(url)) continue;
          out.push(stream(`Peachify [${srv}] ${s.dub || ""}`.trim(), url, { kind: s.type === "mp4" ? "mp4" : "hls", quality: s.quality ? String(s.quality) : undefined, headers: headersOf(s.headers) || headers, subtitles }));
        }
        return out;
      } catch (e) {
        return why(srv, e);
      }
    }),
  );
  return lists.flat();
};

// ── Vidnest (new.vidnest.fun): "data" is base64 with a shuffled alphabet ──
const VIDNEST = "https://new.vidnest.fun";

const vidnest: Server = async (j) => {
  const providers = ["hollymoviehd", "videasy", "buzz", "vidzee", "nextgencloudfabric", "klikxxi", "vidxyz", "allmovies", "vidlink"];
  const lists = await Promise.all(
    providers.map(async (srv): Promise<Stream[]> => {
      try {
        const r = await json(j, isTv(j) ? `${VIDNEST}/${srv}/tv/${j.id}/${j.season}/${j.episode}` : `${VIDNEST}/${srv}/movie/${j.id}`);
        if (!r?.data) return [];
        const d = JSON.parse(vidnestDecode(r.data));
        if (Array.isArray(d.streams)) {
          return d.streams
            .filter((s: any) => s?.url)
            .map((s: any) => stream(`Vidnest [${srv}] ${s.language || ""}`.trim(), s.url, { kind: s.type === "mp4" ? "mp4" : "hls", quality: s.quality ? String(s.quality) : undefined, headers: s.headers?.Referer ? { Referer: s.headers.Referer } : undefined }));
        }
        const urls: string[] = Array.isArray(d.all_urls) ? d.all_urls.filter(Boolean) : d.url ? [d.url] : [];
        const headers = headersOf(d.headers) || (d.referer ? { Referer: d.referer } : undefined);
        return urls.map((u) => stream(`Vidnest-${srv}`, u, { kind: "hls", headers }));
      } catch (e) {
        return why(srv, e);
      }
    }),
  );
  return lists.flat();
};

// ── Mplay (rozgarlelo.modiplay.xyz): an embed page holds an iframe whose page names the playlist ──
const MODIPLAY = "https://rozgarlelo.modiplay.xyz";

const mplay: Server = async (j) => {
  const embed = isTv(j) ? `${MODIPLAY}/embed/tmdb/tv?id=${j.id}&s=${j.season}&e=${j.episode}` : `${MODIPLAY}/embed/tmdb/movie?id=${j.id}`;
  const $ = j.pc.cheerio.load(await get(j, embed));
  const src = $("#playerFrame").attr("src");
  if (!src) return [];
  const html = await get(j, src.startsWith("http") ? src : `${MODIPLAY}${src}`);
  const path = html.match(/\/proxy\.php\?serve_m3u8=1[^"'\s]+/)?.[0];
  return path ? [stream("Mplay", path.startsWith("http") ? path : `${MODIPLAY}${path}`, { kind: "hls" })] : [];
};

// ── Xpass (play.xpass.top): the embed page lists "backups", each a JSON playlist ──
const XPASS = "https://play.xpass.top";

const xpass: Server = async (j) => {
  const html = await get(j, isTv(j) ? `${XPASS}/e/tv/${j.id}/${j.season}/${j.episode}` : `${XPASS}/e/movie/${j.id}`);
  const at = html.indexOf("var backups=");
  if (at === -1) return [];
  const raw = extractBalanced(html, at + "var backups=".length);
  const headers = { Referer: `${XPASS}/` };
  const backups: any[] = JSON.parse(raw);
  const lists = await Promise.all(
    backups.map(async (b): Promise<Stream[]> => {
      if (!b?.url) return [];
      try {
        const o = await json(j, b.url.startsWith("http") ? b.url : `${XPASS}${b.url}`, headers);
        const out: Stream[] = [];
        for (const p of o?.playlist || []) for (const s of p?.sources || []) if (s?.file) out.push(stream(`Xpass-${b.name || "Xpass"}`, s.file, { kind: "hls", headers }));
        return out;
      } catch (e) {
        return why(`xpass ${b.name || ""}`, e);
      }
    }),
  );
  return lists.flat();
};

// ── 2Embed (king.2embed.stream): plain HTTP, the page holds `file: "…"` ──
const embed2: Server = async (j) => {
  const html = await get(j, isTv(j) ? `http://king.2embed.stream/serverusa2/tv/${j.id}/${j.season}/${j.episode}?seasonInfo=false` : `http://king.2embed.stream/serverusa2/movie/${j.id}`);
  const out: Stream[] = [];
  const re = /file\s*:\s*"([^"]+)"/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(html))) out.push(stream("2Embed", m[1].replace(/\\\//g, "/"), { kind: "hls", headers: { Referer: "http://king.2embed.stream/" } }));
  return out;
};

// ── Rive (scrapper.rivestream.app): one plain request per provider ──
const RIVE = "https://scrapper.rivestream.app/api/provider";

const rive: Server = async (j) => {
  const headers = { Referer: "https://www.rivestream.app/", Origin: "https://www.rivestream.app", Accept: "application/json, text/plain, */*" };
  const names = ["apex", "pulse", "solstice", "quasar", "horizon", "primevids", "flowcast", "asiacloud", "citadel", "hindicast", "guru"];
  const bust = new Set(["primevids", "citadel"]);
  const lists = await Promise.all(
    names.map(async (srv): Promise<Stream[]> => {
      const se = isTv(j) ? `&season=${j.season}&episode=${j.episode ?? 1}` : "";
      const cb = bust.has(srv) ? `&cb=${Math.floor(Date.now() / 3000000)}` : "";
      try {
        const d = (await json(j, `${RIVE}?provider=${srv}&id=${j.id}${se}${cb}`, headers))?.data;
        const subtitles = tracks(d?.captions, (c) => c.file, (c) => c.label || c.language);
        return (d?.sources || [])
          .filter((s: any) => s?.url)
          .map((s: any) => stream(`Rive [${srv}] ${s.quality ?? ""}`.trim(), s.url, { kind: s.format === "hls" ? "hls" : "mp4", quality: qualityOf(s.quality), headers, subtitles }));
      } catch (e) {
        return why(srv, e);
      }
    }),
  );
  return lists.flat();
};

const SERVERS: [string, Server][] = [
  ["vidrock", vidrock],
  ["vidlink", vidlink],
  ["111movies", movies111],
  ["peachify", peachify],
  ["vidnest", vidnest],
  ["mplay", mplay],
  ["xpass", xpass],
  ["2embed", embed2],
  ["rive", rive],
  ["movy", movy],
  ["movienight", movieNight],
  ["vuflix", vuflix],
];

export const getStream = async function ({
  link,
  signal,
  providerContext,
}: {
  link: string;
  type: string;
  signal: AbortSignal;
  providerContext: ProviderContext;
  isDownload?: boolean;
}): Promise<Stream[]> {
  const t = decodeTarget(link);
  const job: Job = { id: t.id, season: t.season, episode: t.episode, title: t.title, year: t.year, pc: providerContext, signal };
  const lists = await Promise.all(
    SERVERS.map(async ([name, run]): Promise<Stream[]> => {
      try {
        return await run(job);
      } catch (e: any) {
        console.log(`${name}: ${String(e?.message || e).slice(0, 120)}`);
        return [];
      }
    }),
  );
  // a link found twice (two servers returning the same address) is listed once
  const seen = new Set<string>();
  return lists.flat().filter((s) => (seen.has(s.link) ? false : (seen.add(s.link), true)));
};
