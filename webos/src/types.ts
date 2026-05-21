export type ProviderType = "m3u" | "xtream";

export interface M3uProvider {
  id: string;
  type: "m3u";
  name: string;
  url: string;
}

export interface XtreamProvider {
  id: string;
  type: "xtream";
  name: string;
  serverUrl: string;
  username: string;
  password: string;
}

export type Provider = M3uProvider | XtreamProvider;

export type ContentType = "live" | "movie" | "series";

export type StreamFormat = "hls" | "ts" | "auto";

/** A browsable item: a live channel, a movie, or a series card. */
export interface ContentItem {
  id: string;
  kind: ContentType;
  name: string;
  number?: number;
  imageUrl?: string;
  groupTitle: string;
  /** Present for directly-playable items (live + movie); absent for series cards. */
  streamUrl?: string;
  streamFormat: StreamFormat;
  /** Present for series cards — used to fetch episodes. */
  seriesId?: string;
  /** Unix seconds the item was added at the provider (when available). */
  addedAt?: number;
}

export interface Episode {
  id: string;
  title: string;
  season: number;
  episode: number;
  streamUrl: string;
  streamFormat: StreamFormat;
}

/** The minimal shape the player needs. */
export interface Playable {
  name: string;
  number?: number;
  groupTitle: string;
  streamUrl: string;
  streamFormat: StreamFormat;
}

export interface Category {
  id: string;
  name: string;
}
