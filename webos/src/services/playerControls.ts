export function playbackTime(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds < 0) return "--:--";
  const total = Math.floor(seconds);
  const minutes = Math.floor(total / 60) % 60;
  const parts = [minutes, total % 60].map(value => String(value).padStart(2, "0"));
  if (total >= 3600) parts.unshift(String(Math.floor(total / 3600)));
  return parts.join(":");
}

export function seekTarget(target: number, start: number, end: number): number | null {
  if (![target, start, end].every(Number.isFinite) || start < 0 || end <= start) return null;
  return Math.min(end, Math.max(start, target));
}
