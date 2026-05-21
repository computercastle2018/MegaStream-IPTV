import type { ContentItem, ContentType, Provider, StreamFormat } from "../types";
import { parseM3u, type M3uEntry } from "./m3uParser";
import { getLiveStreams, getMovies, getSeries } from "./xtreamClient";

/** Loads browsable items for a given provider + content type. */
export async function loadContent(
  provider: Provider,
  type: ContentType,
  signal?: AbortSignal,
): Promise<ContentItem[]> {
  if (provider.type === "xtream") {
    if (type === "live") return getLiveStreams(provider, signal);
    if (type === "movie") return getMovies(provider, signal);
    return getSeries(provider, signal);
  }
  // M3U: series are not expressible in a flat playlist.
  if (type === "series") return [];
  return loadM3uItems(provider.id, provider.url, type, signal);
}

/** Which content types this provider can browse. */
export function supportedTypes(provider: Provider): ContentType[] {
  return provider.type === "xtream" ? ["live", "movie", "series"] : ["live", "movie"];
}

async function loadM3uItems(
  providerId: string,
  url: string,
  type: ContentType,
  signal?: AbortSignal,
): Promise<ContentItem[]> {
  const res = await fetch(url, { signal });
  if (!res.ok) throw new Error(`HTTP ${res.status} fetching playlist`);
  const { entries } = parseM3u(await res.text());
  const wantVod = type === "movie";
  const items: ContentItem[] = [];
  entries.forEach((entry, index) => {
    if (isVodEntry(entry) !== wantVod) return;
    items.push({
      id: `${providerId}:${type}:${index}`,
      kind: type,
      name: entry.name,
      number: type === "live" ? entry.tvgChno ?? index + 1 : undefined,
      imageUrl: entry.tvgLogo,
      groupTitle: entry.groupTitle,
      streamUrl: entry.url,
      streamFormat: detectFormat(entry.url),
    });
  });
  return items;
}

// Port of M3uParser.isVodEntry from the Android codebase.
function isVodEntry(entry: M3uEntry): boolean {
  const url = entry.url.toLowerCase();
  const group = entry.groupTitle.toLowerCase();
  return (
    url.endsWith(".mp4") ||
    url.endsWith(".mkv") ||
    url.endsWith(".avi") ||
    url.includes("/movie/") ||
    group.includes("movie") ||
    group.includes("vod") ||
    group.includes("film")
  );
}

function detectFormat(url: string): StreamFormat {
  const lower = url.split("?")[0].toLowerCase();
  if (lower.endsWith(".m3u8")) return "hls";
  if (lower.endsWith(".ts")) return "ts";
  return "auto";
}

/** Stable grouping of items by category for the sidebar. */
export function groupByCategory(items: ContentItem[]): Map<string, ContentItem[]> {
  const groups = new Map<string, ContentItem[]>();
  for (const item of items) {
    const list = groups.get(item.groupTitle);
    if (list) list.push(item);
    else groups.set(item.groupTitle, [item]);
  }
  return groups;
}
