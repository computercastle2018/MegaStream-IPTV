export const VIDEO_HEIGHTS = [360, 480, 720, 1080, 1440, 2160];
export interface VideoQuality { height: number | null; fixed: boolean }
const key = "MegaStream.videoQuality";

export function loadVideoQuality(): VideoQuality {
  try {
    const value = JSON.parse(localStorage.getItem(key) ?? "null");
    if (value && (value.height === null || VIDEO_HEIGHTS.includes(value.height)) && typeof value.fixed === "boolean") return value;
  } catch { /* Unavailable or malformed storage keeps the safe default. */ }
  return { height: 1080, fixed: false };
}

export function saveVideoQuality(value: VideoQuality): boolean {
  try { localStorage.setItem(key, JSON.stringify(value)); return true; } catch { return false; }
}

export function selectQualityLevel(levels: readonly { height: number; bitrate?: number }[], height: number | null): number {
  if (!levels.length) return -1;
  const candidates = levels.map((level, index) => ({ ...level, index }));
  const below = candidates.filter(level => height === null || (level.height > 0 && level.height <= height));
  if (below.length) return below.reduce((best, level) => level.height > best.height ||
    (level.height === best.height && (level.bitrate ?? 0) > (best.bitrate ?? 0)) ? level : best).index;
  return candidates.reduce((best, level) => (level.height || Infinity) < (best.height || Infinity) ||
    (level.height === best.height && (level.bitrate ?? 0) < (best.bitrate ?? 0)) ? level : best).index;
}
