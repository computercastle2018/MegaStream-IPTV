import { normalizeText } from "./text";

export type GroupGenre = "all" | "news" | "sports" | "movies" | "series" | "kids" | "documentary" | "religion" | "music" | "other";

// Provider group names are the only reliable category metadata in M3U/Xtream catalogs.
export function groupGenre(name: string): Exclude<GroupGenre, "all"> {
  const value = normalizeText(name);
  if (/\bnews\b|اخبار|اخباري/.test(value)) return "news";
  if (/\bsports?\b|\bppv\b|\bnba\b|\bcricket\b|thamanyah|رياض|كره|ثمانيه/.test(value)) return "sports";
  if (/\bseries\b|مسلسل/.test(value)) return "series";
  if (/\bmovies?\b|\bcinema\b|\bfilms?\b|افلام/.test(value)) return "movies";
  if (/\bkids?\b|\bchildren\b|\bcartoons?\b|اطفال|كرتون/.test(value)) return "kids";
  if (/\bdocumentar\w*\b|\bnature\b|وثايق|طبيعه/.test(value)) return "documentary";
  if (/\bislamic\b|\breligio\w*\b|\bquran\b|اسلام|دين|قران/.test(value)) return "religion";
  if (/\bmusic\b|موسيق|اغاني/.test(value)) return "music";
  return "other";
}

export const groupGenreLabels: Record<"ar" | "en", Record<GroupGenre, string>> = {
  ar: { all: "كل الفئات", news: "أخبار", sports: "رياضة", movies: "أفلام", series: "مسلسلات", kids: "أطفال", documentary: "وثائقيات", religion: "دينية", music: "موسيقى", other: "أخرى" },
  en: { all: "All categories", news: "News", sports: "Sports", movies: "Movies", series: "Series", kids: "Kids", documentary: "Documentaries", religion: "Religion", music: "Music", other: "Other" },
};
