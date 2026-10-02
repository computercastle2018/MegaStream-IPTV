import { useEffect, useMemo, useState } from "react";
import { useFocusable } from "@noriginmedia/norigin-spatial-navigation";
import type { ContentItem, Episode, Playable, Provider } from "../types";
import { getEpisodes } from "../services/xtreamClient";
import { onBack } from "../remote/keys";
import { BackButton, FocusButton, SearchInput } from "../components/Focusable";
import { useLang } from "../i18n/LanguageContext";
import { selectEpisodes, type EpisodeOrder } from "../services/episodes";
import "./EpisodesScreen.css";

const episodeLabels = {
  en: { newest: "Newest first", oldest: "Oldest first", search: "Search episodes…", empty: "No matching episodes" },
  ar: { newest: "الأحدث أولًا", oldest: "الأقدم أولًا", search: "ابحث عن الحلقات…", empty: "لا توجد حلقات مطابقة" },
};

interface Props {
  provider: Provider;
  series: ContentItem;
  onPlay: (episode: Playable) => void;
  onBack: () => void;
}

export default function EpisodesScreen({ provider, series, onPlay, onBack: goBack }: Props) {
  const { t, lang } = useLang();
  const labels = episodeLabels[lang];
  const [episodes, setEpisodes] = useState<Episode[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [season, setSeason] = useState<number | null>(null);
  const [query, setQuery] = useState("");
  const [order, setOrder] = useState<EpisodeOrder>("newest");
  const [searchOpen, setSearchOpen] = useState(false);

  useEffect(() => onBack(goBack), [goBack]);

  useEffect(() => {
    if (provider.type !== "xtream" || !series.seriesId) {
      setError(t("episodes.xtreamOnly"));
      setLoading(false);
      return;
    }
    const controller = new AbortController();
    setEpisodes([]);
    setSeason(null);
    setQuery("");
    setOrder("newest");
    setSearchOpen(false);
    setLoading(true);
    setError(null);
    getEpisodes(provider, series.seriesId, controller.signal)
      .then((list) => {
        if (controller.signal.aborted) return;
        setEpisodes(list);
        setLoading(false);
      })
      .catch((e: unknown) => {
        if (controller.signal.aborted) return;
        setError(e instanceof Error ? e.message : t("episodes.failed"));
        setLoading(false);
      });
    return () => controller.abort();
  }, [provider, series]);

  const seasons = useMemo(() => {
    const set = new Set<number>();
    episodes.forEach((e) => set.add(e.season));
    return [...set].sort((a, b) => b - a);
  }, [episodes]);

  const visible = useMemo(() => selectEpisodes(episodes, season, query, order),
    [episodes, season, query, order]);

  const toPlayable = (ep: Episode): Playable => ({
    name: `${series.name} · S${ep.season}E${ep.episode}${ep.title ? ` — ${ep.title}` : ""}`,
    groupTitle: series.name,
    streamUrl: ep.streamUrl,
    streamFormat: ep.streamFormat,
  });

  return (
    <div className="screen episodes-screen">
      <header className="app-header">
        <BackButton onEnter={goBack} autoFocus />
        <h1 className="series-title">{series.name}</h1>
        <span className="subtitle">{t("episodes.series")}</span>
      </header>

      {loading && <div className="center-msg">{t("episodes.loading")}</div>}
      {error && (
        <div className="center-msg error">
          <div>{error}</div>
          <div style={{ fontSize: "0.7em" }}>{t("episodes.back")}</div>
        </div>
      )}

      {!loading && !error && (
        <>
          <div className="browse-toolbar episodes-toolbar">
            {searchOpen ? (
              <SearchInput value={query} onChange={setQuery} placeholder={labels.search} autoFocus />
            ) : (
              <FocusButton className="search-toggle" onEnter={() => setSearchOpen(true)}>
                {t("common.search")}
              </FocusButton>
            )}
            <div className="sort-control" role="group" aria-label={t("common.sort")}>
              <span className="sort-label">{t("common.sort")}</span>
              <FocusButton className={`sort-pill ${order === "newest" ? "active" : ""}`}
                onEnter={() => setOrder("newest")}>{labels.newest}</FocusButton>
              <FocusButton className={`sort-pill ${order === "oldest" ? "active" : ""}`}
                onEnter={() => setOrder("oldest")}>{labels.oldest}</FocusButton>
            </div>
            <span className="subtitle" aria-live="polite">{visible.length} / {episodes.length}</span>
          </div>
          <div className="channels-body">
            <nav className="category-rail">
              <div className="rail-label">{t("episodes.seasons")}</div>
              <SeasonItem label={t("common.all")} count={episodes.length}
                active={season === null} onSelect={() => setSeason(null)} />
              {seasons.map((s) => (
                <SeasonItem
                  key={s}
                  label={t("episodes.season", { n: s })}
                  count={episodes.filter((e) => e.season === s).length}
                  active={s === season}
                  onSelect={() => setSeason(s)}
                />
              ))}
            </nav>
            <div className="content-grid live">
              {visible.length === 0 ? (
                <div className="center-msg">{episodes.length === 0 ? t("episodes.none") : labels.empty}</div>
              ) : (
                visible.map((ep) => (
                  <EpisodeCard key={ep.id} episode={ep} onPlay={() => onPlay(toPlayable(ep))} />
                ))
              )}
            </div>
          </div>
        </>
      )}
    </div>
  );
}

function SeasonItem({
  label,
  count,
  active,
  onSelect,
}: {
  label: string;
  count: number;
  active: boolean;
  onSelect: () => void;
}) {
  const { ref, focused } = useFocusable({ onEnterPress: onSelect });
  useEffect(() => {
    if (focused) ref.current?.scrollIntoView({ block: "nearest", inline: "nearest" });
  }, [focused, ref]);
  return (
    <div
      ref={ref}
      className={`category-item ${active ? "active" : ""} ${focused ? "focused" : ""}`}
      onClick={onSelect}
      role="button"
      tabIndex={0}
      aria-pressed={active}
    >
      <span className="cat-name">{label}</span>
      <span className="cat-count">{count}</span>
    </div>
  );
}

function EpisodeCard({
  episode,
  onPlay,
}: {
  episode: Episode;
  onPlay: () => void;
}) {
  const { ref, focused } = useFocusable({ onEnterPress: onPlay });
  useEffect(() => {
    if (focused) ref.current?.scrollIntoView({ block: "nearest", inline: "nearest" });
  }, [focused, ref]);
  return (
    <div ref={ref} className={`channel-card focusable ${focused ? "focused" : ""}`} onClick={onPlay}
      role="button" tabIndex={0}>
      <span className="num" dir="ltr">S{episode.season} · E{episode.episode}</span>
      <span className="name" title={episode.title}>
        {episode.title}
      </span>
    </div>
  );
}
