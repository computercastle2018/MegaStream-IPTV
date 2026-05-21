import { useEffect, useMemo, useState } from "react";
import { useFocusable } from "@noriginmedia/norigin-spatial-navigation";
import type { ContentItem, Episode, Playable, Provider } from "../types";
import { getEpisodes } from "../services/xtreamClient";
import { onBack } from "../remote/keys";
import { BackButton } from "../components/Focusable";
import { useLang } from "../i18n/LanguageContext";

interface Props {
  provider: Provider;
  series: ContentItem;
  onPlay: (episode: Playable) => void;
  onBack: () => void;
}

export default function EpisodesScreen({ provider, series, onPlay, onBack: goBack }: Props) {
  const { t } = useLang();
  const [episodes, setEpisodes] = useState<Episode[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [season, setSeason] = useState<number | null>(null);

  useEffect(() => onBack(goBack), [goBack]);

  useEffect(() => {
    if (provider.type !== "xtream" || !series.seriesId) {
      setError(t("episodes.xtreamOnly"));
      setLoading(false);
      return;
    }
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    getEpisodes(provider, series.seriesId, controller.signal)
      .then((list) => {
        setEpisodes(list);
        setSeason(list[0]?.season ?? null);
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
    return [...set].sort((a, b) => a - b);
  }, [episodes]);

  const visible = season == null ? episodes : episodes.filter((e) => e.season === season);

  const toPlayable = (ep: Episode): Playable => ({
    name: `${series.name} · S${ep.season}E${ep.episode}${ep.title ? ` — ${ep.title}` : ""}`,
    groupTitle: series.name,
    streamUrl: ep.streamUrl,
    streamFormat: ep.streamFormat,
  });

  return (
    <div className="screen">
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
        <div className="channels-body">
          <nav className="category-rail">
            <div className="rail-label">{t("episodes.seasons")}</div>
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
              <div className="center-msg">{t("episodes.none")}</div>
            ) : (
              visible.map((ep, i) => (
                <EpisodeCard key={ep.id} episode={ep} autoFocus={i === 0} onPlay={() => onPlay(toPlayable(ep))} />
              ))
            )}
          </div>
        </div>
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
  return (
    <div
      ref={ref}
      className={`category-item ${active ? "active" : ""} ${focused ? "focused" : ""}`}
      onClick={onSelect}
    >
      <span className="cat-name">{label}</span>
      <span className="cat-count">{count}</span>
    </div>
  );
}

function EpisodeCard({
  episode,
  autoFocus,
  onPlay,
}: {
  episode: Episode;
  autoFocus: boolean;
  onPlay: () => void;
}) {
  const { ref, focused, focusSelf } = useFocusable({ onEnterPress: onPlay });
  useEffect(() => {
    if (autoFocus) focusSelf();
  }, [autoFocus, focusSelf]);
  return (
    <div ref={ref} className={`channel-card focusable ${focused ? "focused" : ""}`} onClick={onPlay}>
      <span className="num">E{episode.episode}</span>
      <span className="name" title={episode.title}>
        {episode.title}
      </span>
    </div>
  );
}
