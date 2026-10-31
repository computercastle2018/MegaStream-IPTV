import { useEffect, useMemo, useState } from "react";
import { setFocus, useFocusable } from "@noriginmedia/norigin-spatial-navigation";
import type { ContentItem, ContentType, Provider } from "../types";
import { groupByCategory, loadContent, supportedTypes } from "../services/content";
import { compareNames, matchesQuery, normalizeText } from "../services/text";
import { onBack } from "../remote/keys";
import { BackButton, FocusButton, HomeButton, LanguageToggle, SearchInput } from "../components/Focusable";
import { useLang } from "../i18n/LanguageContext";
import ContentImage from "../components/ContentImage";
import { ChevronLeft, ChevronRight, RefreshCw, Star } from "lucide-react";
import { groupGenre, groupGenreLabels, type GroupGenre } from "../services/groupGenre";
import { useAppearance } from "../components/Appearance";
import RemoteSelect from "../components/RemoteSelect";

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
const CONTENT_PAGE_SIZE = 100;
const CATEGORY_PAGE_SIZE = 40;

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
  const { design } = useAppearance();
  const tvRemote = document.documentElement.dataset.platform === "webos";
  const tvLayout = tvRemote && design !== "classic";
  const contentPageSize = tvLayout ? 40 : CONTENT_PAGE_SIZE;
  const categoryPageSize = tvLayout ? 20 : CATEGORY_PAGE_SIZE;
  const PreviousIcon = tvLayout && ar ? ChevronRight : ChevronLeft;
  const NextIcon = tvLayout && ar ? ChevronLeft : ChevronRight;
  const [items, setItems] = useState<ContentItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [restoreFocus, setRestoreFocus] = useState(true);
  const [refresh, setRefresh] = useState(0);
  const [contentPage, setContentPage] = useState({ scope: "", index: 0 });
  const [categoryPage, setCategoryPage] = useState({ scope: "", index: 0 });
  const [focusCategoryPage, setFocusCategoryPage] = useState(false);
  const [categoryFocusEnd, setCategoryFocusEnd] = useState(false);
  const [pageFocusEnd, setPageFocusEnd] = useState(false);
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
    focusable: !tvRemote,
    onEnterPress: () => orderRef.current?.focus(),
  });
  useEffect(() => { if (orderFocused) orderRef.current?.focus(); }, [orderFocused, orderRef]);
  const { ref: genreRef, focused: genreFocused, focusSelf: focusGenre } = useFocusable<HTMLSelectElement>({
    focusable: !tvRemote,
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
    setPageFocusEnd(false);
    setFocusCategoryPage(false);
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

  const groupGenres = useMemo(() => {
    const groups = new Map<string, GroupGenre>();
    for (const item of items) {
      const title = item.groupTitle || "";
      if (!groups.has(title)) groups.set(title, groupGenre(title));
    }
    return groups;
  }, [items]);
  const genreItems = useMemo(() => genre === "all" ? items : items.filter(item => groupGenres.get(item.groupTitle || "") === genre), [items, genre, groupGenres]);
  const availableGenres = useMemo(() => new Set(groupGenres.values()), [groupGenres]);
  const genreOptions = (Object.keys(groupGenreLabels[lang]) as GroupGenre[])
    .filter(key => key === "all" || availableGenres.has(key) || key === genre)
    .map(key => ({ value: key, label: groupGenreLabels[lang][key] }));
  const changeGenre = (value: string) => {
    setRestoreFocus(false);
    onViewStateChange({ ...viewState, genre: value as GroupGenre, category: ALL, selectedId: null });
  };
  const changeGroupOrder = (value: string) => {
    setGroupOrder(value); localStorage.setItem(orderKey, value);
  };
  const orderOptions = [
    { value: "provider", label: ar ? "حسب الفئة · ترتيب المزوّد" : "Category · provider order" },
    { value: "asc", label: ar ? "أبجديًا · أ–ي" : "Alphabetical · A–Z" },
    { value: "desc", label: ar ? "أبجديًا · ي–أ" : "Alphabetical · Z–A" },
  ];
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
  const visible = useMemo(() => {
    const base = favoritesOnly ? selectedGroup.filter(item => favorites.has(item.id)) : selectedGroup;
    return normalizedQuery ? base.filter(item => matchesQuery(item.name, normalizedQuery)) : base;
  }, [selectedGroup, favoritesOnly, favorites, normalizedQuery]);
  const contentScope = JSON.stringify([category, genre, normalizedQuery, sortBy, favoritesOnly, refresh]);
  const selectedIndex = restoreFocus ? visible.findIndex(item => item.id === selectedId) : -1;
  const contentPages = Math.max(1, Math.ceil(visible.length / contentPageSize));
  const pageIndex = Math.min(contentPages - 1, contentPage.scope === contentScope ? contentPage.index : Math.max(0, Math.floor(selectedIndex / contentPageSize)));
  const pageItems = visible.slice(pageIndex * contentPageSize, (pageIndex + 1) * contentPageSize);
  const focusId = pageFocusEnd ? pageItems[pageItems.length - 1]?.id : pageItems.find(item => item.id === selectedId)?.id ?? pageItems[0]?.id;
  const categoryScope = JSON.stringify([genre, groupOrder]);
  const categoryPages = Math.max(1, Math.ceil((categoryEntries.length - 1) / categoryPageSize));
  const activeCategoryIndex = categoryEntries.findIndex(entry => entry.key === category) - 1;
  const categoryPageIndex = Math.min(categoryPages - 1, categoryPage.scope === categoryScope ? categoryPage.index : Math.max(0, Math.floor(activeCategoryIndex / categoryPageSize)));
  const pageCategories = categoryEntries.slice(1 + categoryPageIndex * categoryPageSize, 1 + (categoryPageIndex + 1) * categoryPageSize);
  const changeContentPage = (index: number, end = false) => {
    setFocusCategoryPage(false);
    setPageFocusEnd(end);
    setRestoreFocus(true);
    setContentPage({ scope: contentScope, index });
  };
  const cardArrow = (direction: string, index: number, node: HTMLElement | null) => {
    if (!tvLayout || !node?.parentElement) return true;
    const columns = getComputedStyle(node.parentElement).gridTemplateColumns.split(" ").length;
    if (direction === (ar ? "right" : "left") && index % columns === 0) {
      setRestoreFocus(false);
      setFocus(`tv-group:${pageCategories.some(entry => entry.key === category) ? category : ALL}`);
      return false;
    }
    if (direction === "down" && index + columns >= pageItems.length && pageIndex + 1 < contentPages) {
      changeContentPage(pageIndex + 1);
      return false;
    }
    if (direction === "up" && index < columns && pageIndex > 0) {
      changeContentPage(pageIndex - 1, true);
      return false;
    }
    return true;
  };
  const changeCategoryPage = (index: number, end = false) => {
    setRestoreFocus(false);
    setFocusCategoryPage(true);
    setCategoryFocusEnd(end);
    setCategoryPage({ scope: categoryScope, index });
  };
  const groupArrow = (direction: string, index: number) => {
    if (!tvLayout) return true;
    if (direction === (ar ? "left" : "right") && pageItems.length) {
      setFocus(`tv-content:${pageItems[0].id}`);
      return false;
    }
    if (direction === "down" && index === pageCategories.length - 1 && categoryPageIndex + 1 < categoryPages) {
      changeCategoryPage(categoryPageIndex + 1);
      return false;
    }
    if (direction === "up" && index === 0) {
      if (categoryPageIndex > 0) changeCategoryPage(categoryPageIndex - 1, true);
      else setFocus(`tv-group:${ALL}`);
      return false;
    }
    return true;
  };
  const categoryList = <>
    <CategoryItem entry={categoryEntries[0]} active={category === ALL} onSelect={() => setCategory(ALL)}
      focusKey={tvLayout ? `tv-group:${ALL}` : undefined} onArrowPress={direction => groupArrow(direction, -1)} />
    {pageCategories.map((entry, index) => <CategoryItem key={entry.key} entry={entry} active={entry.key === category}
      onSelect={() => setCategory(entry.key)} autoFocus={focusCategoryPage && index === (categoryFocusEnd ? pageCategories.length - 1 : 0)}
      focusKey={tvLayout ? `tv-group:${entry.key}` : undefined} onArrowPress={direction => groupArrow(direction, index)} />)}
  </>;

  return (
    <div className="screen">
      <header className="app-header">
        {!tvLayout && <BackButton onEnter={onManageProviders} />}
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
              {tvRemote && <span className={`remote-key remote-${ct}`} aria-hidden="true" />}
            </FocusButton>
          ))}
        </div>
        <FocusButton className="back-btn sync-current" disabled={loading} title={ar ? "مزامنة القائمة" : "Sync this list"} onEnter={() => setRefresh(value => value + 1)}>
          <RefreshCw size={20} aria-hidden="true" /><span>{ar ? "مزامنة" : "Sync"}</span>
        </FocusButton>
        {contentType === "live" && <FocusButton className={`back-btn favorites-toggle ${favoritesOnly ? "active" : ""}`}
          title={tvLayout ? (ar ? "المفضلة" : "Favorites") : undefined}
          pressed={favoritesOnly} onEnter={() => onViewStateChange({ ...viewState, favoritesOnly: !favoritesOnly })}>
          <Star size={20} aria-hidden="true" fill={favoritesOnly ? "currentColor" : "none"} /><span>{ar ? "المفضلة" : "Favorites"}</span>
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
              {tvRemote ? <RemoteSelect label={ar ? "الفئة" : "Category"} value={genre} options={genreOptions} onChange={changeGenre} />
                : <select className="genre-select" ref={genreRef} value={genre} onFocus={() => focusGenre()}
                  onKeyDown={event => event.stopPropagation()} onChange={event => changeGenre(event.target.value)}>
                  {genreOptions.map(option => <option value={option.value} key={option.value}>{option.label}</option>)}
                </select>}
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
                {tvRemote && <span className="remote-key remote-search" aria-hidden="true" />}
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
              {tvRemote ? <RemoteSelect label={ar ? "ترتيب المجموعات" : "Group order"} value={groupOrder} options={orderOptions} onChange={changeGroupOrder} />
                : <select ref={orderRef} className="group-sort" value={groupOrder} onFocus={() => focusOrder()}
                  onKeyDown={event => event.stopPropagation()} onChange={event => changeGroupOrder(event.target.value)}>
                  {orderOptions.map(option => <option value={option.value} key={option.value}>{option.label}</option>)}
                </select>}
            </label>
            <div className="rail-label">{t("common.categories")}</div>
            {categoryPages > 1 && <div className="browse-pagination category-pagination">
              <FocusButton title={ar ? "المجموعات السابقة" : "Previous groups"} disabled={categoryPageIndex === 0}
                onEnter={() => changeCategoryPage(categoryPageIndex - 1)}>
                <PreviousIcon size={22} aria-hidden="true" />
              </FocusButton>
              <span dir={tvLayout ? "ltr" : undefined}>{categoryPageIndex + 1} / {categoryPages}</span>
              <FocusButton title={ar ? "المجموعات التالية" : "Next groups"} disabled={categoryPageIndex + 1 === categoryPages}
                onEnter={() => changeCategoryPage(categoryPageIndex + 1)}>
                <NextIcon size={22} aria-hidden="true" />
              </FocusButton>
            </div>}
            {tvLayout ? <div className="tv-category-list">{categoryList}</div> : categoryList}
          </nav>
          <div className="browse-content">
          {(contentPages > 1 || tvLayout) && <div className="browse-pagination content-pagination">
            {tvLayout && <h2>{category === ALL ? t(TYPE_KEYS[contentType]) : category}</h2>}
            <FocusButton title={ar ? "الصفحة السابقة" : "Previous channels"} disabled={pageIndex === 0} onEnter={() => changeContentPage(pageIndex - 1)}>
              <PreviousIcon size={22} aria-hidden="true" />{tvLayout && <span>{ar ? "السابق" : "Previous"}</span>}
            </FocusButton>
            <span dir={tvLayout ? "ltr" : undefined}>{visible.length ? pageIndex * contentPageSize + 1 : 0}–{Math.min((pageIndex + 1) * contentPageSize, visible.length)} / {visible.length}</span>
            <FocusButton title={ar ? "الصفحة التالية" : "Next channels"} disabled={pageIndex + 1 === contentPages} onEnter={() => changeContentPage(pageIndex + 1)}>
              <NextIcon size={22} aria-hidden="true" />{tvLayout && <span>{ar ? "التالي" : "Next"}</span>}
            </FocusButton>
          </div>}
          <div key={JSON.stringify([contentScope, pageIndex])} className={`content-grid ${contentType === "live" ? "live" : "poster"}`}>
            {visible.length === 0 ? (
              <div className="center-msg">{t("common.nothingHere")}</div>
            ) : (
              pageItems.map((item, index) => (
                <ContentCard key={item.id} item={item}
                  focusKey={tvLayout ? `tv-content:${item.id}` : undefined}
                  onArrowPress={(direction, node) => cardArrow(direction, index, node)}
                  favorite={favorites.has(item.id)} onFavorite={contentType === "live" ? () => toggleFavorite(item.id) : undefined}
                  autoFocus={restoreFocus && !searchOpen && item.id === focusId}
                  onSelect={() => {
                    onViewStateChange({ ...viewState, selectedId: item.id });
                    onSelect(item);
                  }} />
              ))
            )}
            </div>
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
  autoFocus = false,
  focusKey,
  onArrowPress,
}: {
  entry: CategoryEntry;
  active: boolean;
  onSelect: () => void;
  autoFocus?: boolean;
  focusKey?: string;
  onArrowPress?: (direction: string) => boolean;
}) {
  const { ref, focused, focusSelf } = useFocusable({
    focusKey, onArrowPress,
    onEnterPress: onSelect,
    onFocus: ({ node }) => node.scrollIntoView({ block: "nearest", inline: "nearest" }),
  });
  useEffect(() => { if (autoFocus) focusSelf(); }, [autoFocus, focusSelf]);
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
  focusKey,
  onArrowPress,
}: {
  item: ContentItem;
  autoFocus: boolean;
  onSelect: () => void;
  favorite: boolean;
  onFavorite?: () => void;
  focusKey?: string;
  onArrowPress?: (direction: string, node: HTMLElement | null) => boolean;
}) {
  const { lang } = useLang();
  const { ref, focused, focusSelf } = useFocusable({
    focusKey,
    onArrowPress: (direction): boolean => onArrowPress?.(direction, ref.current) ?? true,
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
