// Minimal Xtream Codes client. Mirrors the endpoints used by the StreamVault
// Android XtreamApiService / XtreamUrlFactory, for Live, VOD (movies) and Series:
//   player_api.php?username=&password=                          -> auth
//   ...&action=get_live_categories | get_live_streams           -> live
//   ...&action=get_vod_categories  | get_vod_streams            -> movies
//   ...&action=get_series_categories | get_series               -> series
//   ...&action=get_series_info&series_id=ID                     -> episodes
//   {server}/live|movie|series/{user}/{pass}/{id}.{ext}         -> playback
import type { ContentItem, Episode, XtreamProvider } from "../types";

interface XtreamAuthResponse {
  user_info?: { auth?: number; status?: string; message?: string; exp_date?: string | number | null };
}

export interface AccountInfo {
  /** Unix seconds the subscription expires, or null for unlimited. */
  expDate: number | null;
  status?: string;
}

interface CategoryDto {
  category_id?: string | number;
  category_name?: string;
}

interface LiveStreamDto {
  stream_id?: string | number;
  name?: string;
  num?: string | number;
  stream_icon?: string;
  category_name?: string;
  category_id?: string | number;
  added?: string | number;
}

interface VodStreamDto {
  stream_id?: string | number;
  name?: string;
  num?: string | number;
  stream_icon?: string;
  container_extension?: string;
  category_name?: string;
  category_id?: string | number;
  added?: string | number;
}

interface SeriesDto {
  series_id?: string | number;
  name?: string;
  cover?: string;
  category_name?: string;
  category_id?: string | number;
  last_modified?: string | number;
}

interface SeriesInfoDto {
  episodes?: Record<string, EpisodeDto[]>;
}

interface EpisodeDto {
  id?: string | number;
  title?: string;
  episode_num?: string | number;
  season?: string | number;
  container_extension?: string;
}

function trimServer(serverUrl: string): string {
  return serverUrl.trim().replace(/\/+$/, "");
}

function seg(s: string): string {
  return encodeURIComponent(s).replace(/\+/g, "%20");
}

function playerApiUrl(p: XtreamProvider, action?: string, extra?: Record<string, string>): string {
  const params = new URLSearchParams();
  params.set("username", p.username);
  params.set("password", p.password);
  if (action) params.set("action", action);
  if (extra) for (const [k, v] of Object.entries(extra)) params.set(k, v);
  return `${trimServer(p.serverUrl)}/player_api.php?${params.toString()}`;
}

async function fetchJson<T>(url: string, signal?: AbortSignal): Promise<T> {
  const res = await fetch(url, { signal, headers: { Accept: "application/json" } });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const text = await res.text();
  if (!text.trim()) return [] as unknown as T;
  return JSON.parse(text) as T;
}

export async function authenticate(p: XtreamProvider, signal?: AbortSignal): Promise<void> {
  const data = await fetchJson<XtreamAuthResponse>(playerApiUrl(p), signal);
  const info = data.user_info;
  const ok = info?.auth === 1 || info?.status === "Active";
  if (!ok) throw new Error(info?.message || "Authentication failed (check server URL / credentials)");
}

export async function getAccountInfo(p: XtreamProvider, signal?: AbortSignal): Promise<AccountInfo> {
  const data = await fetchJson<XtreamAuthResponse>(playerApiUrl(p), signal);
  const raw = data.user_info?.exp_date;
  const exp = raw != null ? Number(raw) : NaN;
  return {
    expDate: Number.isFinite(exp) && exp > 0 ? exp : null,
    status: data.user_info?.status,
  };
}

function normalizeNum(value?: string | number): number | undefined {
  if (value == null) return undefined;
  const n = parseInt(String(value), 10);
  return Number.isFinite(n) ? n : undefined;
}

/** Fetches a category_id -> category_name map for the given category action. */
async function fetchCategoryMap(
  p: XtreamProvider,
  action: string,
  signal?: AbortSignal,
): Promise<Map<string, string>> {
  const dtos = await fetchJson<CategoryDto[]>(playerApiUrl(p, action), signal);
  const map = new Map<string, string>();
  for (const d of dtos || []) {
    if (d.category_id != null && d.category_name) {
      map.set(String(d.category_id), d.category_name);
    }
  }
  return map;
}

/**
 * Resolves the display category name in priority order:
 *   1. the provider's named category (from get_*_categories), then
 *   2. the category_name embedded in the stream/series row, then
 *   3. "Uncategorized".
 */
function resolveCategory(
  map: Map<string, string>,
  id?: string | number,
  inlineName?: string,
): string {
  if (id != null) {
    const named = map.get(String(id));
    if (named) return named;
  }
  return inlineName?.trim() || "Uncategorized";
}

function normalizeExt(ext?: string, fallback = "mp4"): string {
  const cleaned = (ext || "").trim().replace(/^\./, "").toLowerCase();
  return /^[a-z0-9]{1,12}$/.test(cleaned) ? cleaned : fallback;
}

// ───────────────────────── Live ─────────────────────────
export async function getLiveStreams(p: XtreamProvider, signal?: AbortSignal): Promise<ContentItem[]> {
  const [cats, dtos] = await Promise.all([
    fetchCategoryMap(p, "get_live_categories", signal),
    fetchJson<LiveStreamDto[]>(playerApiUrl(p, "get_live_streams"), signal),
  ]);
  return (dtos || [])
    .filter((d) => d.stream_id != null && d.name)
    .map((d) => ({
      id: `${p.id}:live:${d.stream_id}`,
      kind: "live" as const,
      name: String(d.name),
      number: normalizeNum(d.num),
      imageUrl: d.stream_icon || undefined,
      groupTitle: resolveCategory(cats, d.category_id, d.category_name),
      streamUrl: `${trimServer(p.serverUrl)}/live/${seg(p.username)}/${seg(p.password)}/${seg(String(d.stream_id))}.m3u8`,
      streamFormat: "hls" as const,
      addedAt: normalizeNum(d.added),
    }));
}

// ───────────────────────── Movies (VOD) ─────────────────────────
export async function getMovies(p: XtreamProvider, signal?: AbortSignal): Promise<ContentItem[]> {
  const [cats, dtos] = await Promise.all([
    fetchCategoryMap(p, "get_vod_categories", signal),
    fetchJson<VodStreamDto[]>(playerApiUrl(p, "get_vod_streams"), signal),
  ]);
  return (dtos || [])
    .filter((d) => d.stream_id != null && d.name)
    .map((d) => {
      const ext = normalizeExt(d.container_extension, "mp4");
      return {
        id: `${p.id}:movie:${d.stream_id}`,
        kind: "movie" as const,
        name: String(d.name),
        imageUrl: d.stream_icon || undefined,
        groupTitle: resolveCategory(cats, d.category_id, d.category_name),
        streamUrl: `${trimServer(p.serverUrl)}/movie/${seg(p.username)}/${seg(p.password)}/${seg(String(d.stream_id))}.${ext}`,
        streamFormat: "auto" as const,
        addedAt: normalizeNum(d.added),
      };
    });
}

// ───────────────────────── Series ─────────────────────────
export async function getSeries(p: XtreamProvider, signal?: AbortSignal): Promise<ContentItem[]> {
  const [cats, dtos] = await Promise.all([
    fetchCategoryMap(p, "get_series_categories", signal),
    fetchJson<SeriesDto[]>(playerApiUrl(p, "get_series"), signal),
  ]);
  return (dtos || [])
    .filter((d) => d.series_id != null && d.name)
    .map((d) => ({
      id: `${p.id}:series:${d.series_id}`,
      kind: "series" as const,
      name: String(d.name),
      imageUrl: d.cover || undefined,
      groupTitle: resolveCategory(cats, d.category_id, d.category_name),
      streamFormat: "auto" as const,
      seriesId: String(d.series_id),
      addedAt: normalizeNum(d.last_modified),
    }));
}

export async function getEpisodes(
  p: XtreamProvider,
  seriesId: string,
  signal?: AbortSignal,
): Promise<Episode[]> {
  const info = await fetchJson<SeriesInfoDto>(
    playerApiUrl(p, "get_series_info", { series_id: seriesId }),
    signal,
  );
  const seasons = info.episodes || {};
  const episodes: Episode[] = [];
  for (const [seasonKey, list] of Object.entries(seasons)) {
    for (const ep of list || []) {
      if (ep.id == null) continue;
      const ext = normalizeExt(ep.container_extension, "mp4");
      const season = normalizeNum(ep.season) ?? normalizeNum(seasonKey) ?? 1;
      const number = normalizeNum(ep.episode_num) ?? 0;
      episodes.push({
        id: String(ep.id),
        title: ep.title || `Episode ${number}`,
        season,
        episode: number,
        streamUrl: `${trimServer(p.serverUrl)}/series/${seg(p.username)}/${seg(p.password)}/${seg(String(ep.id))}.${ext}`,
        streamFormat: "auto",
      });
    }
  }
  episodes.sort((a, b) => a.season - b.season || a.episode - b.episode);
  return episodes;
}
