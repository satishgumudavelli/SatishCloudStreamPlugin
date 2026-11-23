import { Post, ProviderContext } from "../types";
import { DiscoverItem, discover, tmdbImage, yearOf } from "./api";

// A post's link is the title's path on the site ("/movie/872585" or "/tv/1396"): the site's address can change, the path does not.
const toPost = (it: DiscoverItem): Post | null => {
  const kind = it.media_type || (it.first_air_date ? "tv" : "movie");
  const name = it.title || it.name;
  if (!name || !it.id || (kind !== "movie" && kind !== "tv")) return null;
  const year = yearOf(it.release_date || it.first_air_date);
  return {
    // the year is in the title so that a search by title can tell a remake from the original
    title: year ? `${name} (${year})` : name,
    link: `/${kind}/${it.id}`,
    image: tmdbImage(it.poster_path),
    cornerTag: it.vote_average ? it.vote_average.toFixed(1) : undefined,
  };
};

const toPosts = (items: DiscoverItem[]): Post[] => items.map(toPost).filter((p): p is Post => p !== null);

export const getPosts = async function ({
  filter,
  page,
  signal,
  providerContext,
}: {
  filter: string;
  page: number;
  providerValue: string;
  signal: AbortSignal;
  providerContext: ProviderContext;
}): Promise<Post[]> {
  return toPosts(await discover(providerContext, filter, page, signal));
};

export const getSearchPosts = async function ({
  searchQuery,
  page,
  signal,
  providerContext,
}: {
  searchQuery: string;
  page: number;
  providerValue: string;
  signal: AbortSignal;
  providerContext: ProviderContext;
}): Promise<Post[]> {
  return toPosts(await discover(providerContext, `q=${encodeURIComponent(searchQuery)}`, page, signal));
};
