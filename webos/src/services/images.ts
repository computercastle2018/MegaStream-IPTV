export function resolveImageUrl(value: unknown, base: string): string | undefined {
  const input = typeof value === "string" ? value.trim() : "";
  if (!input || /^(null|undefined|n\/a|-)$/i.test(input)) return undefined;
  try {
    const url = new URL(input, base);
    return url.protocol === "http:" || url.protocol === "https:" ? url.href : undefined;
  } catch {
    return undefined;
  }
}
