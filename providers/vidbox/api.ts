import { ProviderContext } from "../types";

// VidBox (https://vidbox.wtf): a TMDB-keyed streaming guide. Its own /api/search/discover lists and searches titles, the
// /movie/{id} and /tv/{id} pages are server-rendered React (the details are inside `self.__next_f.push` blocks), and its
// player fans out to many embed servers keyed by the TMDB id (see stream.ts).

export const FALLBACK_BASE = "https://vidbox.wtf";

// vidbox.vc 403s requests with no browser User-Agent.
export const UA =
  "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
export const headers = { "User-Agent": UA };

// vidbox.vc's own TMDB key, taken from its client bundle: used only where vidbox has no endpoint (per-season episode lists).
export const TMDB_KEY = "ef311eb0b9b07b9c73e9fb0a732cc150";
export const TMDB_API = "https://api.themoviedb.org/3";

/** Where the site lives now: the repository's urls.json (key "vidbox") through the app, else the built-in address. */
export const getBase = async (providerContext: ProviderContext): Promise<string> => {
  try {
    const url = await (providerContext as any).getBaseUrl?.("vidbox");
    if (typeof url === "string" && /^https?:\/\//.test(url)) return url.replace(/\/+$/, "");
  } catch (_) {}
  return FALLBACK_BASE;
};

export const getText = async (
  providerContext: ProviderContext,
  url: string,
  extra: Record<string, string> = {},
  signal?: AbortSignal,
): Promise<string> => {
  const res = await providerContext.axios.get(url, {
    headers: { ...headers, ...extra },
    responseType: "text",
    transformResponse: [(d: any) => d],
    signal,
  } as any);
  return typeof res.data === "string" ? res.data : JSON.stringify(res.data);
};

export const getJson = async (
  providerContext: ProviderContext,
  url: string,
  extra: Record<string, string> = {},
  signal?: AbortSignal,
): Promise<any> => {
  const text = await getText(providerContext, url, extra, signal);
  return JSON.parse(text);
};

// ── what a title's link carries ──

/** The data the stream step needs, kept in a link (a link is plain text, so it is JSON). */
export type Target = { id: number; season?: number; episode?: number; title: string; year?: number };

export const encodeTarget = (t: Target): string => JSON.stringify(t);
export const decodeTarget = (s: string): Target => JSON.parse(s);

// ── the discover API ──

export type DiscoverItem = {
  id: number;
  media_type?: string;
  title?: string;
  name?: string;
  release_date?: string;
  first_air_date?: string;
  poster_path?: string;
  vote_average?: number;
  [k: string]: any;
};

export const discover = async (
  providerContext: ProviderContext,
  params: string,
  page: number,
  signal?: AbortSignal,
): Promise<DiscoverItem[]> => {
  const base = await getBase(providerContext);
  try {
    const json = await getJson(providerContext, `${base}/api/search/discover?${params}&page=${page}`, {}, signal);
    return Array.isArray(json?.results) ? json.results : [];
  } catch (_) {
    return [];
  }
};

// ── the server-rendered page ──

const rscBlock = /self\.__next_f\.push\(\[1,"([\s\S]*?)"\]\)/g;

const unescapeJs = (s: string): string =>
  s.replace(/\\(u[0-9a-fA-F]{4}|.)/g, (_m, g: string) => {
    if (g[0] === "u" && g.length === 5) return String.fromCharCode(parseInt(g.slice(1), 16));
    switch (g) {
      case "n": return "\n";
      case "t": return "\t";
      case "r": return "\r";
      default: return g; // \" \\ \/ and anything else: the character itself
    }
  });

/** The page's server-rendered React payload, unescaped and joined. */
export const fetchRscText = async (providerContext: ProviderContext, url: string, signal?: AbortSignal): Promise<string> => {
  const html = await getText(providerContext, url, {}, signal);
  const out: string[] = [];
  let m: RegExpExecArray | null;
  rscBlock.lastIndex = 0;
  while ((m = rscBlock.exec(html))) out.push(unescapeJs(m[1]));
  return out.join("\n");
};

/** The balanced `[...]` or `{...}` text starting at [start] (which points at the opening bracket). */
export const extractBalanced = (text: string, start: number): string => {
  let depth = 0;
  let inStr = false;
  let esc = false;
  for (let i = start; i < text.length; i++) {
    const c = text[i];
    if (inStr) {
      if (esc) esc = false;
      else if (c === "\\") esc = true;
      else if (c === '"') inStr = false;
    } else if (c === '"') inStr = true;
    else if (c === "[" || c === "{") depth++;
    else if (c === "]" || c === "}") {
      depth--;
      if (depth === 0) return text.substring(start, i + 1);
    }
  }
  return text.substring(start);
};

export const yearOf = (date?: string): number | undefined => {
  const y = parseInt((date || "").split("-")[0], 10);
  return Number.isFinite(y) ? y : undefined;
};

export const tmdbImage = (path: string | undefined, size = "w342"): string =>
  path ? `https://image.tmdb.org/t/p/${size}${path.startsWith("/") ? "" : "/"}${path}` : "";
