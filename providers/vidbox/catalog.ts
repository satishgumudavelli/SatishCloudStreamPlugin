import { Catalog } from "../types";

// Each filter is the query string of vidbox's own browse endpoint (/api/search/discover): the "view all" link of one home row.
export const catalog: Catalog[] = [
  { title: "Now Playing", filter: "type=movie&now_playing=true" },
  { title: "Trending Movies", filter: "type=movie&trending=true" },
  { title: "Trending TV Shows", filter: "type=tv&trending=true" },
  { title: "Netflix Originals", filter: "type=tv&watch_provider=8" },
  { title: "Amazon Prime Shows", filter: "type=tv&watch_provider=9" },
  { title: "Apple TV+ Shows", filter: "type=tv&watch_provider=350" },
  { title: "Disney+ Shows", filter: "type=tv&watch_provider=337" },
  { title: "Indian Movies", filter: "type=movie&country=IN" },
  { title: "Peacock TV Shows", filter: "type=tv&watch_provider=386" },
  { title: "Max Shows", filter: "type=tv&watch_provider=1899" },
];

export const genres: Catalog[] = [];
