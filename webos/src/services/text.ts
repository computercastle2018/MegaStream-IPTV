// Text normalization for search and sorting, with strong Arabic support.
//
// Arabic playlists are messy: the same channel/movie name appears with or
// without tashkeel (diacritics), with different alef/ya/ta-marbuta forms, and
// with tatweel (kashida) padding. Naive substring matching misses these. We
// fold all of that to a canonical form so "افلام" matches "أفـلام", etc.

const ARABIC_DIACRITICS = /[ؐ-ًؚ-ٰٟۖ-ۭ࣓-ࣿ]/g;
const LATIN_DIACRITICS = /[̀-ͯ]/g;
const TATWEEL = /ـ/g;

/** Folds a string to a canonical, accent/diacritic-insensitive lowercase form. */
export function normalizeText(input: string): string {
  return input
    .normalize("NFKD")
    .replace(ARABIC_DIACRITICS, "")
    .replace(LATIN_DIACRITICS, "")
    .replace(TATWEEL, "")
    // Alef variants (آ أ إ ٱ ٲ ٳ) -> ا
    .replace(/[آأإٱٲٳ]/g, "ا")
    // Alef maqsura (ى) -> ya (ي)
    .replace(/ى/g, "ي")
    // Ta marbuta (ة) -> ha (ه)
    .replace(/ة/g, "ه")
    // Hamza carriers: ؤ -> و, ئ -> ي, ء dropped
    .replace(/ؤ/g, "و")
    .replace(/ئ/g, "ي")
    .replace(/ء/g, "")
    // Arabic-Indic digits -> ASCII
    .replace(/[٠-٩]/g, (d) => String(d.charCodeAt(0) - 0x0660))
    .replace(/[۰-۹]/g, (d) => String(d.charCodeAt(0) - 0x06F0))
    .toLowerCase()
    .replace(/\s+/g, " ")
    .trim();
}

/** True if [haystack] contains [query] under Arabic-aware normalization. */
export function matchesQuery(haystack: string, normalizedQuery: string): boolean {
  if (!normalizedQuery) return true;
  return normalizeText(haystack).includes(normalizedQuery);
}

/** Locale-aware name comparator (Arabic-friendly, numeric, accent-insensitive). */
export function compareNames(a: string, b: string): number {
  return normalizeText(a).localeCompare(normalizeText(b), "ar", {
    numeric: true,
    sensitivity: "base",
  });
}
