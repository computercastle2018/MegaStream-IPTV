import { useEffect, useMemo, useState } from "react";
import { useFocusable } from "@noriginmedia/norigin-spatial-navigation";
import type { ContentItem, ContentType, Provider } from "../types";
import { groupByCategory, loadContent, supportedTypes } from "../services/content";
import { compareNames, matchesQuery, normalizeText } from "../services/text";
import { onBack } from "../remote/keys";
import { BackButton, FocusButton, LanguageToggle, SearchInput } from "../components/Focusable";
import { useLang } from "../i18n/LanguageContext";

type SortBy = "added" | "name";

const TYPE_KEYS: Record<ContentType, string> = {
  live: "content.live",
  movie: "content.movies",
  series: "content.series",
};

interface Props {
  provider: Provider;
  providers: Provider[];
  contentType: ContentType;
  onChangeType: (type: ContentType) => void;
  onSelectProvider: (id: string) => void;
  onSelect: (item: ContentItem) => void;
  onManageProviders: () => void;
}

const ALL = "__all__";

interface CategoryEntry {
  key: string;
  label: string;
  count: number;
}

export default function ContentScreen({
  provider,
  contentType,
  onChangeType,
  onSelect,
  onManageProviders,
}: Props) {
  const { t } = useLang();
  const [items, setItems] = useState<ContentItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [category, setCategory] = useState<string>(ALL);
  const [sortBy, setSortBy] = useState<SortBy>("added");
  const [query, setQuery] = useState("");
  const [searchOpen, setSearchOpen] = useState(false);

  const types = useMemo(() => supportedTypes(provider), [provider]);

  const closeSearch = () => {
    setSearchOpen(false);
    setQuery("");
  };

  // Back closes the search box first (if open), otherwise leaves to providers.
  useEffect(
    () => onBack(() => (searchOpen ? closeSearch() : onManageProviders())),
    [searchOpen, onManageProviders],
  );

  // If the active provider doesn't support the current type, fall back to live.
  useEffect(() => {
    if (!types.includes(contentType)) onChangeType("live");
  }, [types, contentType, onChangeType]);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    setCategory(ALL);
    setQuery("");
    setSearchOpen(false);
    loadContent(provider, contentType, controller.signal)
      .then((list) => {
        setItems(list);
        setLoading(false);
      })
      .catch((e: unknown) => {
        if (controller.signal.aborted) return;
        setError(e instanceof Error ? e.message : "Failed to load");
        setLoading(false);
      });
    return () => controller.abort();
  }, [provider, contentType]);

  const sorted = useMemo(() => {
    const copy = [...items];
    if (sortBy === "name") {
      copy.sort((a, b) => compareNames(a.name, b.name));
    } else {
      // Recently added first. M3U items have no timestamp (all 0), so the stable
      // sort preserves their original playlist order.
      copy.sort((a, b) => (b.addedAt ?? 0) - (a.addedAt ?? 0));
    }
    return copy;
  }, [items, sortBy]);

  const grouped = useMemo(() => groupByCategory(sorted), [sorted]);

  const categoryEntries = useMemo<CategoryEntry[]>(() => {
    const entries: CategoryEntry[] = [{ key: ALL, label: t("common.all"), count: sorted.length }];
    for (const [name, list] of grouped) entries.push({ key: name, label: name, count: list.length });
    return entries;
  }, [grouped, sorted.length, t]);

  const normalizedQuery = useMemo(() => normalizeText(query), [query]);
  const base = category === ALL ? sorted : grouped.get(category) ?? [];
  const visible = normalizedQuery ? base.filter((i) => matchesQuery(i.name, normalizedQuery)) : base;

  return (
    <div className="screen">
      <header className="app-header">
        <BackButton onEnter={onManageProviders} />
        <h1>MegaStream</h1>
        <span className="subtitle">{provider.name}</span>
        <div className="type-tabs">
          {types.map((ct) => (
            <FocusButton
              key={ct}
              className={`type-tab ${ct === contentType ? "active" : ""}`}
              autoFocus={ct === contentType}
              onEnter={() => onChangeType(ct)}
            >
              {t(TYPE_KEYS[ct])}
            </FocusButton>
          ))}
        </div>
        <LanguageToggle />
      </header>

      {loading && <div className="center-msg">{t("content.loading", { type: t(TYPE_KEYS[contentType]) })}</div>}
      {error && (
        <div className="center-msg error">
          <div>{error}</div>
          <div style={{ fontSize: "0.7em" }}>{t("content.backToProviders")}</div>
        </div>
      )}

      {!loading && !error && (
        <>
          <div className="browse-toolbar">
            {searchOpen ? (
              <SearchInput value={query} onChange={setQuery} placeholder={t("common.searchPh")} autoFocus />
            ) : (
              <FocusButton className="search-toggle" onEnter={() => setSearchOpen(true)}>
                <svg viewBox="0 0 24 24" width="1em" height="1em" aria-hidden="true">
                  <circle cx="11" cy="11" r="7" fill="none" stroke="currentColor" strokeWidth="2" />
                  <path d="M16.5 16.5L21 21" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
                </svg>
                <span>{t("common.search")}</span>
              </FocusButton>
            )}
            <div className="sort-control">
              <span className="sort-label">{t("common.sort")}</span>
              <FocusButton
                className={`sort-pill ${sortBy === "added" ? "active" : ""}`}
                onEnter={() => setSortBy("added")}
              >
                {t("common.sortAdded")}
              </FocusButton>
              <FocusButton
                className={`sort-pill ${sortBy === "name" ? "active" : ""}`}
                onEnter={() => setSortBy("name")}
              >
                {t("common.sortName")}
              </FocusButton>
            </div>
          </div>
          <div className="channels-body">
            <nav className="category-rail">
            <div className="rail-label">{t("common.categories")}</div>
            {categoryEntries.map((entry) => (
              <CategoryItem
                key={entry.key}
                entry={entry}
                active={entry.key === category}
                onSelect={() => setCategory(entry.key)}
              />
            ))}
          </nav>
          <div className={`content-grid ${contentType === "live" ? "live" : "poster"}`}>
            {visible.length === 0 ? (
              <div className="center-msg">{t("common.nothingHere")}</div>
            ) : (
              visible.map((item, i) => (
                <ContentCard key={item.id} item={item} autoFocus={i === 0} onSelect={() => onSelect(item)} />
              ))
            )}
            </div>
          </div>
        </>
      )}
    </div>
  );
}

function CategoryItem({
  entry,
  active,
  onSelect,
}: {
  entry: CategoryEntry;
  active: boolean;
  onSelect: () => void;
}) {
  const { ref, focused } = useFocusable({ onEnterPress: onSelect });
  return (
    <div
      ref={ref}
      className={`category-item ${active ? "active" : ""} ${focused ? "focused" : ""}`}
      onClick={onSelect}
      title={entry.label}
    >
      <span className="cat-name">{entry.label}</span>
      <span className="cat-count">{entry.count}</span>
    </div>
  );
}

function ContentCard({
  item,
  autoFocus,
  onSelect,
}: {
  item: ContentItem;
  autoFocus: boolean;
  onSelect: () => void;
}) {
  const { ref, focused, focusSelf } = useFocusable({ onEnterPress: onSelect });
  useEffect(() => {
    if (autoFocus) focusSelf();
  }, [autoFocus, focusSelf]);

  if (item.kind === "live") {
    return (
      <div ref={ref} className={`channel-card focusable ${focused ? "focused" : ""}`} onClick={onSelect}>
        <span className="num">{item.number ?? ""}</span>
        {item.imageUrl ? <img className="logo" src={item.imageUrl} alt="" loading="lazy" /> : <span className="logo" />}
        <span className="name">{item.name}</span>
      </div>
    );
  }

  // Movie / series poster card
  return (
    <div ref={ref} className={`poster-card focusable ${focused ? "focused" : ""}`} onClick={onSelect}>
      <div className="poster-img">
        {item.imageUrl ? (
          <img src={item.imageUrl} alt="" loading="lazy" />
        ) : (
          <span className="poster-fallback">{item.name.charAt(0).toUpperCase()}</span>
        )}
      </div>
      <span className="poster-name" title={item.name}>
        {item.name}
      </span>
    </div>
  );
}
