import { useEffect, useMemo, useState } from "react";
import { useFocusable } from "@noriginmedia/norigin-spatial-navigation";
import type { ContentItem, ContentType, Provider } from "../types";
import { groupByCategory, loadContent, supportedTypes } from "../services/content";
import { compareNames, matchesQuery, normalizeText } from "../services/text";
import { onBack } from "../remote/keys";
import { BackButton, FocusButton, HomeButton, LanguageToggle, SearchInput } from "../components/Focusable";
import { useLang } from "../i18n/LanguageContext";
import ContentImage from "../components/ContentImage";
import { RefreshCw, Star } from "lucide-react";
import { groupGenre, groupGenreLabels, type GroupGenre } from "../services/groupGenre";

type SortBy = "added" | "name";

export interface BrowseState {
  category: string;
  sortBy: SortBy;
  query: string;
  searchOpen: boolean;
  selectedId: string | null;
  favoritesOnly: boolean;
  genre: GroupGenre;
}

export const initialBrowseState: BrowseState = {
  category: "__all__", sortBy: "added", query: "", searchOpen: false, selectedId: null, favoritesOnly: false, genre: "all",
};

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
  viewState: BrowseState;
  onViewStateChange: (next: BrowseState) => void;
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
  viewState,
  onViewStateChange,
}: Props) {
  const { t, lang } = useLang();
  const ar = lang === "ar";
  const [items, setItems] = useState<ContentItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [restoreFocus, setRestoreFocus] = useState(true);
  const [refresh, setRefresh] = useState(0);
  const orderKey = `MegaStream.groupOrder.${provider.id}.${contentType}`;
  const [groupOrder, setGroupOrder] = useState(() => {
    const saved = localStorage.getItem(orderKey);
    return saved === "asc" || saved === "desc" ? saved : "provider";
  });
  const favoritesKey = `MegaStream.favorites.${provider.id}`;
  const [favorites, setFavorites] = useState<Set<string>>(() => {
    try {
      const saved: unknown = JSON.parse(localStorage.getItem(favoritesKey) || "[]");
      return new Set(Array.isArray(saved) ? saved.filter((id): id is string => typeof id === "string") : []);
    } catch { return new Set(); }
  });
  const { category, sortBy, query, searchOpen, selectedId, favoritesOnly, genre } = viewState;
  const { ref: orderRef, focused: orderFocused, focusSelf: focusOrder } = useFocusable<HTMLSelectElement>({
    onEnterPress: () => orderRef.current?.focus(),
  });
  useEffect(() => { if (orderFocused) orderRef.current?.focus(); }, [orderFocused, orderRef]);
  const { ref: genreRef, focused: genreFocused, focusSelf: focusGenre } = useFocusable<HTMLSelectElement>({
    onEnterPress: () => genreRef.current?.focus(),
  });
  useEffect(() => { if (genreFocused) genreRef.current?.focus(); }, [genreFocused, genreRef]);
  const toggleFavorite = (id: string) => {
    const next = new Set(favorites);
    if (next.has(id)) next.delete(id); else next.add(id);
    localStorage.setItem(favoritesKey, JSON.stringify([...next]));
    setFavorites(next);
  };
  const setCategory = (category: string) => {
    setRestoreFocus(false);
    onViewStateChange({ ...viewState, category, selectedId: null });
  };
  const setSortBy = (sortBy: SortBy) => onViewStateChange({ ...viewState, sortBy });
  const setQuery = (query: string) => onViewStateChange({ ...viewState, query, selectedId: null });
  const setSearchOpen = (searchOpen: boolean) => onViewStateChange({ ...viewState, searchOpen });

  const types = useMemo(() => supportedTypes(provider), [provider]);

  const closeSearch = () => {
    onViewStateChange({ ...viewState, searchOpen: false, query: "" });
  };

  // Return one level at a time; opening connection settings is an explicit action.
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
    loadContent(provider, contentType, controller.signal, refresh > 0)
      .then((list) => {
        if (controller.signal.aborted) return;
        setItems(list);
        setLoading(false);
      })
      .catch((e: unknown) => {
        if (controller.signal.aborted) return;
        setError(e instanceof Error ? e.message : "Failed to load");
        setLoading(false);
      });
    return () => controller.abort();
  }, [provider, contentType, refresh]);

  const genreItems = useMemo(() => genre === "all" ? items : items.filter(item => groupGenre(item.groupTitle || "") === genre), [items, genre]);
  const availableGenres = useMemo(() => new Set(items.map(item => groupGenre(item.groupTitle || ""))), [items]);
  const sorted = useMemo(() => {
    const copy = [...genreItems];
    if (sortBy === "name") {
      copy.sort((a, b) => compareNames(a.name, b.name));
    } else {
      // Recently added first. M3U items have no timestamp (all 0), so the stable
      // sort preserves their original playlist order.
      copy.sort((a, b) => (b.addedAt ?? 0) - (a.addedAt ?? 0));
    }
    return copy;
  }, [genreItems, sortBy]);

  const grouped = useMemo(() => groupByCategory(sorted), [sorted]);

  const categoryEntries = useMemo<CategoryEntry[]>(() => {
    const entries: CategoryEntry[] = [{ key: ALL, label: t("common.all"), count: sorted.length }];
    const categories = [...groupByCategory(genreItems)].map(([name, list]) => ({ key: name, label: name, count: list.length }));
    if (groupOrder !== "provider") categories.sort((a, b) => compareNames(a.label, b.label) * (groupOrder === "desc" ? -1 : 1));
    entries.push(...categories);
    return entries;
  }, [genreItems, groupOrder, sorted.length, t]);

  const normalizedQuery = useMemo(() => normalizeText(query), [query]);
  const selectedGroup = category === ALL || !grouped.has(category) ? sorted : grouped.get(category)!;
  const base = favoritesOnly ? selectedGroup.filter(item => favorites.has(item.id)) : selectedGroup;
  const visible = normalizedQuery ? base.filter((i) => matchesQuery(i.name, normalizedQuery)) : base;
  const focusId = visible.find(item => item.id === selectedId)?.id ?? visible[0]?.id;

  return (
    <div className="screen">
      <header className="app-header">
        <BackButton onEnter={onManageProviders} />
        <HomeButton onEnter={onManageProviders} />
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
        <FocusButton className="back-btn sync-current" disabled={loading} title={ar ? "مزامنة القائمة" : "Sync this list"} onEnter={() => setRefresh(value => value + 1)}>
          <RefreshCw size={20} aria-hidden="true" />{ar ? "مزامنة" : "Sync"}
        </FocusButton>
        {window.megastreamDesktop && contentType === "live" && <FocusButton className={`back-btn favorites-toggle ${favoritesOnly ? "active" : ""}`}
          pressed={favoritesOnly} onEnter={() => onViewStateChange({ ...viewState, favoritesOnly: !favoritesOnly })}>
          <Star size={20} aria-hidden="true" fill={favoritesOnly ? "currentColor" : "none"} />{ar ? "المفضلة" : "Favorites"}
        </FocusButton>}
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
            <label className="genre-control"><span>{ar ? "الفئة" : "Category"}</span>
              <select className="genre-select" ref={genreRef} value={genre} onFocus={() => focusGenre()}
                onKeyDown={event => event.stopPropagation()} onChange={event => {
                  setRestoreFocus(false);
                  onViewStateChange({ ...viewState, genre: event.target.value as GroupGenre, category: ALL, selectedId: null });
                }}>
                {(Object.keys(groupGenreLabels[lang]) as GroupGenre[]).filter(key => key === "all" || availableGenres.has(key) || key === genre).map(key =>
                  <option value={key} key={key}>{groupGenreLabels[lang][key]}</option>)}
              </select>
            </label>
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
            <label className="group-sort-control">
              <span>{ar ? "ترتيب المجموعات" : "Group order"}</span>
              <select ref={orderRef} className="group-sort" value={groupOrder} onFocus={() => focusOrder()}
                onKeyDown={event => event.stopPropagation()} onChange={event => {
                  setGroupOrder(event.target.value); localStorage.setItem(orderKey, event.target.value);
                }}>
                <option value="provider">{ar ? "حسب الفئة · ترتيب المزوّد" : "Category · provider order"}</option>
                <option value="asc">{ar ? "أبجديًا · أ–ي" : "Alphabetical · A–Z"}</option>
                <option value="desc">{ar ? "أبجديًا · ي–أ" : "Alphabetical · Z–A"}</option>
              </select>
            </label>
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
          <div key={JSON.stringify([category, genre, normalizedQuery, sortBy])} className={`content-grid ${contentType === "live" ? "live" : "poster"}`}>
            {visible.length === 0 ? (
              <div className="center-msg">{t("common.nothingHere")}</div>
            ) : (
              visible.map((item) => (
                <ContentCard key={item.id} item={item}
                  favorite={favorites.has(item.id)} onFavorite={window.megastreamDesktop && contentType === "live" ? () => toggleFavorite(item.id) : undefined}
                  autoFocus={restoreFocus && !searchOpen && item.id === focusId}
                  onSelect={() => {
                    onViewStateChange({ ...viewState, selectedId: item.id });
                    onSelect(item);
                  }} />
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
  const { ref, focused, focusSelf } = useFocusable({
    onEnterPress: onSelect,
    onFocus: ({ node }) => node.scrollIntoView({ block: "nearest", inline: "nearest" }),
  });
  return (
    <div
      ref={ref}
      className={`category-item ${active ? "active" : ""} ${focused ? "focused" : ""}`}
      onClick={() => { focusSelf(); onSelect(); }}
      onFocus={() => focusSelf()}
      role="button"
      tabIndex={0}
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
  favorite,
  onFavorite,
}: {
  item: ContentItem;
  autoFocus: boolean;
  onSelect: () => void;
  favorite: boolean;
  onFavorite?: () => void;
}) {
  const { lang } = useLang();
  const { ref, focused, focusSelf } = useFocusable({
    onEnterPress: onSelect,
    onFocus: ({ node }) => node.scrollIntoView({ block: "nearest", inline: "nearest" }),
  });
  useEffect(() => {
    if (autoFocus) focusSelf();
  }, [autoFocus, focusSelf]);

  if (item.kind === "live") {
    return (
      <div ref={ref} role="button" tabIndex={0} onFocus={event => { if (event.target === event.currentTarget) focusSelf(); }} className={`channel-card focusable ${focused ? "focused" : ""}`} onClick={() => { focusSelf(); onSelect(); }}>
        <span className="num">{item.number ?? ""}</span>
        <ContentImage src={item.imageUrl} name={item.name} logo />
        <span className="name">{item.name}</span>
        {onFavorite && <button className="favorite-button" aria-pressed={favorite}
          title={lang === "ar" ? (favorite ? "إزالة من المفضلة" : "إضافة إلى المفضلة") : (favorite ? "Remove favorite" : "Add favorite")}
          onFocus={event => event.stopPropagation()} onKeyDown={event => event.stopPropagation()}
          onClick={event => { event.stopPropagation(); onFavorite(); }}>
          <Star size={21} fill={favorite ? "currentColor" : "none"} aria-hidden="true" />
        </button>}
      </div>
    );
  }

  // Movie / series poster card
  return (
    <div ref={ref} role="button" tabIndex={0} onFocus={() => focusSelf()} className={`poster-card focusable ${focused ? "focused" : ""}`} onClick={() => { focusSelf(); onSelect(); }}>
      <div className="poster-img">
        <ContentImage src={item.imageUrl} name={item.name} />
      </div>
      <span className="poster-name" title={item.name}>
        {item.name}
      </span>
    </div>
  );
}
