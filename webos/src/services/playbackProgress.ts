const ENABLED_KEY = "MegaStream.savePlaybackProgress";
const PREFIX = "MegaStream.playbackProgress.";

export interface PlaybackProgress { position: number; duration: number }

export function playbackProgressEnabled(): boolean {
  try { return localStorage.getItem(ENABLED_KEY) !== "false"; }
  catch { return false; }
}

export function setPlaybackProgressEnabled(enabled: boolean): boolean {
  try { localStorage.setItem(ENABLED_KEY, String(enabled)); return true; }
  catch { return false; }
}

export function loadPlaybackProgress(key: string): PlaybackProgress | null {
  try {
    if (!playbackProgressEnabled()) return null;
    const value = JSON.parse(localStorage.getItem(PREFIX + key) ?? "null");
    return value && Number.isFinite(value.position) && Number.isFinite(value.duration) &&
      value.position >= 5 && value.duration > value.position ? value : null;
  } catch { return null; }
}

export function savePlaybackProgress(key: string, position: number, duration: number): boolean {
  if (!playbackProgressEnabled()) return true;
  if (!Number.isFinite(position) || !Number.isFinite(duration) || position < 0 || duration <= 0) return true;
  if (position < 5 || position >= duration) return clearPlaybackProgress(key);
  try {
    localStorage.setItem(PREFIX + key, JSON.stringify({ position, duration }));
    return true;
  } catch { return false; }
}

export function clearPlaybackProgress(key: string): boolean {
  try { localStorage.removeItem(PREFIX + key); return true; }
  catch { return false; }
}
