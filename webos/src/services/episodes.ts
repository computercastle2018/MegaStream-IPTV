import type { Episode } from "../types";
import { matchesQuery, normalizeText } from "./text";

export type EpisodeOrder = "newest" | "oldest";

/** Accept Unix seconds/milliseconds or ISO dates; ignore malformed provider dates. */
export function episodeTimestamp(raw: unknown): number | undefined {
  if (typeof raw === "number" || (typeof raw === "string" && /^\d+(\.\d+)?$/.test(raw.trim()))) {
    const value = Number(raw);
    const millis = value < 100_000_000_000 ? value * 1000 : value;
    return Number.isFinite(millis) && millis > 0 && millis <= 8_640_000_000_000_000 ? millis : undefined;
  }
  if (typeof raw !== "string") return undefined;
  const value = raw.trim();
  const date = /^(\d{4})-(\d{2})-(\d{2})(?:$|T)/.exec(value);
  if (!date) return undefined;
  const [, year, month, day] = date;
  const calendarDate = new Date(Date.UTC(Number(year), Number(month) - 1, Number(day)));
  if (calendarDate.getUTCFullYear() !== Number(year)
    || calendarDate.getUTCMonth() + 1 !== Number(month)
    || calendarDate.getUTCDate() !== Number(day)) return undefined;
  const timestamp = Date.parse(value);
  return Number.isFinite(timestamp) && timestamp > 0 ? timestamp : undefined;
}

export function selectEpisodes(
  episodes: readonly Episode[],
  season: number | null,
  query: string,
  order: EpisodeOrder,
): Episode[] {
  const normalizedQuery = normalizeText(query);
  const direction = order === "newest" ? -1 : 1;
  return episodes
    .filter((episode) => (season === null || episode.season === season)
      && matchesQuery(episode.title, normalizedQuery))
    .sort((a, b) => {
      const dateA = Number.isFinite(a.newestAt) && a.newestAt! > 0 ? a.newestAt! : 0;
      const dateB = Number.isFinite(b.newestAt) && b.newestAt! > 0 ? b.newestAt! : 0;
      // Dated episodes precede undated ones in either direction; missing dates
      // use season/episode order without creating a non-transitive comparator.
      if (!!dateA !== !!dateB) return dateA ? -1 : 1;
      return direction * (dateA - dateB || a.season - b.season || a.episode - b.episode);
    });
}
