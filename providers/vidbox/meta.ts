import { Info, Link, ProviderContext } from "../types";
import { encodeTarget, extractBalanced, fetchRscText, getBase, tmdbImage, yearOf } from "./api";

// What the movie page's server-rendered markup looks like (found in the site's own HTML).
const movieTitle = /"className":"mb-4 text-3xl font-bold md:text-4xl","children":"([^"]+)"/;
const movieRatingYear =
  /fill-yellow-500 text-yellow-500"[\s\S]*?"span",null,\{"children":"([0-9.]+)"\}\]\]\}\],\["\$","span",null,\{"children":"(\d{4})"\}/;
const movieGenre = /"span","\d+",\{"className":"rounded-full bg-slate-900[^"]*","children":"([^"]+)"\}\]/g;
const movieOverview = /"p",null,\{"className":"mb-6 text-lg","children":"([^"]*)"\}\]/;
const posterPath = /image\.tmdb\.org%2Ft%2Fp%2Fw342%2F([^&"]+)/;
const backdropPath = /image\.tmdb\.org%2Ft%2Fp%2Foriginal%2F([^&"]+)/;

const pathOf = (link: string): string => {
  try {
    return new URL(link).pathname;
  } catch (_) {
    return link.startsWith("/") ? link : `/${link}`;
  }
};

export const getMeta = async function ({
  link,
  providerContext,
}: {
  link: string;
  providerContext: ProviderContext;
}): Promise<Info> {
  const path = pathOf(link);
  const id = parseInt(path.split("/").pop() || "", 10);
  const base = await getBase(providerContext);
  const text = await fetchRscText(providerContext, `${base}${path}`);
  const empty: Info = { title: "", image: "", synopsis: "", imdbId: "", type: "movie", linkList: [] };
  if (!Number.isFinite(id) || !text) return empty;

  if (path.startsWith("/tv/")) {
    // the series page carries the whole TMDB `tv/{id}` answer inside a "props" object
    const anchor = '"props":{"adult"';
    const at = text.indexOf(anchor);
    if (at === -1) return empty;
    let tv: any;
    try {
      tv = JSON.parse(extractBalanced(text, at + '"props":'.length));
    } catch (_) {
      return empty;
    }
    const title: string = tv.name || "";
    const year = yearOf(tv.first_air_date);
    const linkList: Link[] = (tv.seasons || [])
      .filter((s: any) => s && s.season_number >= 1)
      .map((s: any) => ({
        title: `Season ${s.season_number}`,
        // the episodes of a season are loaded when it is opened (episodes.ts): this is what it needs
        episodesLink: encodeTarget({ id, season: s.season_number, title, year }),
      }));
    return {
      title,
      image: tmdbImage(tv.poster_path),
      poster: tmdbImage(tv.poster_path),
      synopsis: tv.overview || "",
      imdbId: "",
      tmdbId: String(id),
      type: "series",
      tags: (tv.genres || []).map((g: any) => g.name).filter(Boolean),
      rating: tv.vote_average ? String(tv.vote_average) : undefined,
      linkList,
    };
  }

  const title = text.match(movieTitle)?.[1];
  if (!title) return empty;
  const ry = text.match(movieRatingYear);
  const year = ry ? parseInt(ry[2], 10) : undefined;
  const genres = Array.from(new Set(Array.from(text.matchAll(movieGenre)).map((m) => m[1])));
  const poster = text.match(posterPath)?.[1];
  const backdrop = text.match(backdropPath)?.[1];
  return {
    title,
    image: poster ? `https://image.tmdb.org/t/p/w342/${poster}` : "",
    poster: backdrop ? `https://image.tmdb.org/t/p/original/${backdrop}` : undefined,
    synopsis: text.match(movieOverview)?.[1] || "",
    imdbId: "",
    tmdbId: String(id),
    type: "movie",
    tags: genres,
    rating: ry?.[1],
    linkList: [
      {
        title: "Movie",
        directLinks: [{ title: "Movie", link: encodeTarget({ id, title, year }), type: "movie" }],
      },
    ],
  };
};
