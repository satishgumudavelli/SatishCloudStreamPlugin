import { EpisodeLink, ProviderContext } from "../types";
import { TMDB_API, TMDB_KEY, decodeTarget, encodeTarget, getJson, tmdbImage } from "./api";

// vidbox has no endpoint for a season's episodes: its own pages ask TMDB straight from the browser with the key embedded in the
// site, so this does the same.
export const getEpisodes = async function ({
  url,
  providerContext,
}: {
  url: string;
  providerContext: ProviderContext;
}): Promise<EpisodeLink[]> {
  const t = decodeTarget(url);
  if (!t.season) return [];
  let json: any;
  try {
    json = await getJson(providerContext, `${TMDB_API}/tv/${t.id}/season/${t.season}?api_key=${TMDB_KEY}&language=en-US`);
  } catch (_) {
    return [];
  }
  return (json?.episodes || []).map((e: any) => ({
    // "Episode 3: name": the number comes first so it can be read back from the title
    title: `Episode ${e.episode_number}${e.name ? `: ${e.name}` : ""}`,
    link: encodeTarget({ id: t.id, season: t.season, episode: e.episode_number, title: t.title, year: t.year }),
    image: tmdbImage(e.still_path, "w300") || undefined,
    description: e.overview || undefined,
  }));
};
