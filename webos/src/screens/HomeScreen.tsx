import { useCallback, useEffect, useState } from "react";
import type { ContentItem, ContentType, Provider } from "../types";
import { FocusButton, LanguageToggle } from "../components/Focusable";
import LoadingOverlay from "../components/LoadingOverlay";
import ContentImage from "../components/ContentImage";
import { getAccountInfo } from "../services/xtreamClient";
import { loadContent, refreshAllContent, supportedTypes } from "../services/content";
import { onBack } from "../remote/keys";
import { useLang } from "../i18n/LanguageContext";
import { AppearanceDialog, useAppearance } from "../components/Appearance";
import {
  Award,
  BarChart3,
  CalendarDays,
  Clapperboard,
  Flame,
  History,
  Lightbulb,
  Palette,
  Play,
  RefreshCw,
  Search,
  Settings,
  Star,
  Trophy,
  Tv,
} from "lucide-react";

declare const __APP_VERSION__: string;

interface Props {
  provider: Provider;
  onOpenType: (type: ContentType) => void;
  onChangePlaylist: () => void;
  onSettings: () => void;
}

const CHANNEL_SLOTS = 16;

export default function HomeScreen({ provider, onOpenType, onChangePlaylist, onSettings }: Props) {
  const { t, lang } = useLang();
  const { design } = useAppearance();
  const tv = !!window.megastreamWebos && !window.megastreamDesktop;
  const types = supportedTypes(provider);
  const hasSeries = types.includes("series");
  const [expiry, setExpiry] = useState<string>(t("home.unlimited"));
  const [expDays, setExpDays] = useState<number | null>(null);
  const [syncing, setSyncing] = useState(false);
  const [syncResult, setSyncResult] = useState<"done" | "failed" | null>(null);
  const [appearanceOpen, setAppearanceOpen] = useState(false);
  const [reloadTick, setReloadTick] = useState(0);
  const [liveItems, setLiveItems] = useState<ContentItem[] | null>(null);
  const [movieItems, setMovieItems] = useState<ContentItem[] | null>(null);
  const [seriesItems, setSeriesItems] = useState<ContentItem[] | null>(null);
  const [favCount, setFavCount] = useState(0);

  useEffect(() => onBack(() => {}), []);

  const refreshExpiry = useCallback(
    async (signal?: AbortSignal) => {
      if (provider.type !== "xtream") {
        setExpiry(t("home.unlimited"));
        setExpDays(null);
        return;
      }
      try {
        const info = await getAccountInfo(provider, signal);
        if (info.expDate) {
          setExpiry(new Date(info.expDate * 1000).toLocaleDateString());
          setExpDays(Math.max(0, Math.ceil((info.expDate * 1000 - Date.now()) / 86_400_000)));
        } else {
          setExpiry(t("home.unlimited"));
          setExpDays(null);
        }
      } catch {
        if (!signal?.aborted) {
          setExpiry(t("home.unlimited"));
          setExpDays(null);
        }
      }
    },
    [provider, t],
  );

  useEffect(() => {
    const controller = new AbortController();
    void refreshExpiry(controller.signal);
    return () => controller.abort();
  }, [refreshExpiry]);

  // Dashboard data (modern/studio only): fill counts, channel logos and poster
  // artwork in after the first paint so the shell renders instantly.
  useEffect(() => {
    if (design === "classic") return;
    const controller = new AbortController();
    const { signal } = controller;
    const load = (type: ContentType, setter: (items: ContentItem[]) => void) =>
      loadContent(provider, type, signal)
        .then((items) => {
          if (!signal.aborted) setter(items);
        })
        .catch(() => {
          if (!signal.aborted) setter([]);
        });
    void load("live", setLiveItems);
    void load("movie", setMovieItems);
    if (hasSeries) void load("series", setSeriesItems);
    else setSeriesItems([]);
    return () => controller.abort();
  }, [design, provider, hasSeries, reloadTick]);

  useEffect(() => {
    try {
      const saved: unknown = JSON.parse(localStorage.getItem(`MegaStream.favorites.${provider.id}`) || "[]");
      setFavCount(Array.isArray(saved) ? saved.length : 0);
    } catch {
      setFavCount(0);
    }
  }, [provider.id, reloadTick]);

  // Reload: re-sync the whole library (live / movies / series) plus the
  // account info, showing the animated loading overlay until it completes.
  const handleReload = useCallback(async () => {
    if (syncing) return;
    setSyncing(true);
    setSyncResult(null);
    try {
      await Promise.all([refreshAllContent(provider), refreshExpiry()]);
      setSyncResult("done");
    } catch {
      setSyncResult("failed");
    } finally {
      setSyncing(false);
      setReloadTick((n) => n + 1);
    }
  }, [syncing, provider, refreshExpiry]);

  const syncStatus = syncResult && (
    <div role="status" className="sync-result">{syncResult === "done"
      ? (lang === "ar" ? "تمت المزامنة" : "Sync complete")
      : (lang === "ar" ? "تعذرت المزامنة. أعد المحاولة." : "Sync failed. Try again.")}</div>
  );

  const overlays = (
    <>
      {syncStatus}
      {syncing && <LoadingOverlay message={t("home.syncing")} />}
      {appearanceOpen && <AppearanceDialog onClose={() => setAppearanceOpen(false)} onAccount={() => {
        if (window.megastreamDesktop) window.dispatchEvent(new Event("megastream-license-open"));
        else onSettings();
      }} />}
    </>
  );

  /* ── Crimson redesign (modern / studio) ── */
  if (design !== "classic") {
    const liveLogos = (liveItems ?? []).filter((item) => item.imageUrl);
    const guideChannel = liveItems?.[0];
    const statusText = expDays != null ? t("home.activeDays", { days: expDays }) : t("home.unlimited");
    return (
      <div className="home-screen home-redesign dash-root">
        <header className="dash-topbar">
          <div className="dash-tip">
            <Lightbulb className="dash-tip-icon" aria-hidden="true" />
            <span className="dash-tip-texts">
              <strong className="dash-tip-title">{t("home.tipTitle")}</strong>
              <small className="dash-tip-sub">{t("home.tipBody")}</small>
            </span>
          </div>
          <div className="dash-center">
            <div className="dash-brand">
              <img className="dash-brand-logo" src="./icon.png" alt="" />
              <span className="dash-brand-name">MegaStream <b>TV</b></span>
              <span className="dash-brand-sep" aria-hidden="true" />
              <BrandClock />
            </div>
            <FocusButton className="dash-status dash-f" focusKey="home-status" onEnter={onChangePlaylist} title={t("home.changePlaylist")}>
              <span className="dash-status-dot" aria-hidden="true" />
              <span>{statusText}</span>
            </FocusButton>
          </div>
          <div className="dash-top-actions">
            <FocusButton className="dash-icon-btn appearance-open dash-f" focusKey="home-appearance"
              onEnter={() => setAppearanceOpen(true)} title={t("home.settings")} hasPopup="dialog">
              <Settings aria-hidden="true" />
            </FocusButton>
            <FocusButton className="dash-icon-btn dash-f" focusKey="home-sync" onEnter={handleReload}
              disabled={syncing} title={t("home.syncAll")}>
              <RefreshCw aria-hidden="true" className={syncing ? "dash-spin" : undefined} />
            </FocusButton>
            <LanguageToggle />
            <span className="home-version">v{__APP_VERSION__}</span>
          </div>
        </header>

        <main className="dash-main">
          <FocusButton className="home-tile tile-live dash-hero dash-f" focusKey="home-live" autoFocus
            onEnter={() => onOpenType("live")} title={t("home.live")}>
            {tv && <span className="remote-key remote-live" data-remote-key="403" aria-hidden="true" />}
            <div className="dash-hero-info">
              <span className="dash-live-chip"><span className="dash-live-dot" aria-hidden="true" />{t("home.liveChip")}</span>
              <h1 className="dash-hero-title">{t("home.live")}</h1>
              <span className="dash-hero-count">{liveItems == null ? "…" : t("home.channelsCount", { n: liveItems.length })}</span>
              <span className="dash-watch-now"><Play aria-hidden="true" fill="currentColor" />{t("home.watchNow")}</span>
            </div>
            <div className="dash-channel-grid" aria-hidden="true">
              <ChannelGrid items={liveItems} />
            </div>
          </FocusButton>

          <FocusButton className="home-tile dash-panel dash-f" focusKey="home-movies"
            onEnter={() => onOpenType("movie")} title={t("home.movies")}>
            {tv && <span className="remote-key remote-movie" data-remote-key="404" aria-hidden="true" />}
            <header className="dash-panel-head">
              <h2 className="dash-panel-title">{t("home.movies")}</h2>
              <span className="dash-panel-icon" aria-hidden="true"><Clapperboard /></span>
            </header>
            <PosterFan items={movieItems} kind="movie" />
            <div className="dash-panel-count">
              <strong>{movieItems == null ? "…" : movieItems.length}</strong>
              <span>{t("home.movieUnit")}</span>
            </div>
          </FocusButton>

          <FocusButton className="home-tile dash-panel dash-f" focusKey="home-series"
            disabled={tv && !hasSeries} onEnter={() => onOpenType("series")} title={t("home.series")}>
            {tv && hasSeries && <span className="remote-key remote-series" data-remote-key="405" aria-hidden="true" />}
            <header className="dash-panel-head">
              <h2 className="dash-panel-title">{t("home.series")}</h2>
              <span className="dash-panel-icon" aria-hidden="true"><Tv /></span>
            </header>
            <PosterFan items={seriesItems} kind="series" />
            <div className="dash-panel-count">
              <strong>{seriesItems == null ? "…" : seriesItems.length}</strong>
              <span>{t("home.seriesUnit")}</span>
            </div>
          </FocusButton>
        </main>

        <section className="dash-features">
          <FocusButton className="home-tile dash-feature dash-f" focusKey="home-guide"
            onEnter={() => onOpenType("live")} title={t("home.tvGuide")}>
            <header className="dash-feature-head">
              <h3 className="dash-feature-title">{t("home.tvGuide")}</h3>
              <span className="dash-feature-icon" aria-hidden="true"><CalendarDays /></span>
            </header>
            <div className="dash-feature-body">
              <span className="dash-guide-logo" aria-hidden="true">
                {guideChannel ? <ContentImage src={guideChannel.imageUrl} name={guideChannel.name} logo /> : <CalendarDays size={22} />}
              </span>
              <span className="dash-feature-text">
                <small dir="auto">{guideChannel?.name ?? t("home.guideChannel")}</small>
                <strong>{t("home.pressForGuide")}</strong>
              </span>
            </div>
          </FocusButton>

          <FocusButton className="dash-feature dash-f" focusKey="home-matches"
            onEnter={() => onOpenType("live")} title={t("home.matches")}>
            <header className="dash-feature-head">
              <h3 className="dash-feature-title">{t("home.matches")}</h3>
              <span className="dash-feature-icon" aria-hidden="true"><Trophy /></span>
            </header>
            <div className="dash-feature-body">
              <span className="dash-match">
                <span className="dash-match-teams">
                  <span className="dash-crest" aria-hidden="true">
                    {liveLogos[0] ? <ContentImage src={liveLogos[0].imageUrl} name={liveLogos[0].name} logo /> : <Trophy size={18} />}
                  </span>
                  <span className="dash-match-score">{t("home.matchScore")}</span>
                  <span className="dash-crest" aria-hidden="true">
                    {liveLogos[1] ? <ContentImage src={liveLogos[1].imageUrl} name={liveLogos[1].name} logo /> : <Trophy size={18} />}
                  </span>
                </span>
                <span className="dash-match-time">{t("home.matchTime")}</span>
              </span>
            </div>
          </FocusButton>

          <FocusButton className="dash-feature dash-feature-simple dash-f" focusKey="home-favorites"
            onEnter={() => onOpenType("live")} title={t("home.favorites")}>
            <span className="dash-feature-icon" aria-hidden="true"><Star /></span>
            <h3 className="dash-feature-title">{t("home.favorites")}</h3>
            <span className="dash-feature-note">{t("home.savedCount", { n: favCount })}</span>
          </FocusButton>

          <FocusButton className="dash-feature dash-feature-simple dash-f" focusKey="home-watch-later"
            onEnter={() => onOpenType("movie")} title={t("home.watchLater")}>
            <span className="dash-feature-icon" aria-hidden="true"><History /></span>
            <h3 className="dash-feature-title">{t("home.watchLater")}</h3>
            <span className="dash-feature-note">{t("home.nothingYet")}</span>
          </FocusButton>

          <FocusButton className="dash-feature dash-feature-simple dash-f" focusKey="home-kids"
            onEnter={() => onOpenType("movie")} title={t("home.kids")}>
            <span className="dash-feature-icon" aria-hidden="true"><Palette /></span>
            <h3 className="dash-feature-title">{t("home.kids")}</h3>
            <span className="dash-feature-note">{t("home.kidsSafe")}</span>
          </FocusButton>
        </section>

        <nav className="home-side dash-actions">
          <FocusButton className="dash-action dash-f" focusKey="home-q-search"
            onEnter={() => onOpenType("live")} title={t("home.qSearch")}>
            <Search className="dash-action-icon" aria-hidden="true" />
            <span className="dash-action-text">
              <strong>{t("home.qSearch")}</strong>
              <small>{t("home.qSearchSub")}</small>
            </span>
          </FocusButton>
          <FocusButton className="dash-action dash-f" focusKey="home-q-guide"
            onEnter={() => onOpenType("live")} title={t("home.qGuide")}>
            <CalendarDays className="dash-action-icon" aria-hidden="true" />
            <span className="dash-action-text">
              <strong>{t("home.qGuide")}</strong>
              <small>{t("home.qGuideSub")}</small>
            </span>
          </FocusButton>
          <FocusButton className="dash-action dash-f" focusKey="home-q-most"
            onEnter={() => onOpenType("live")} title={t("home.qMost")}>
            <Flame className="dash-action-icon" aria-hidden="true" />
            <span className="dash-action-text">
              <strong>{t("home.qMost")}</strong>
              <small>{t("home.qMostSub")}</small>
            </span>
          </FocusButton>
          <FocusButton className="dash-action dash-f" focusKey="home-q-top-movies"
            onEnter={() => onOpenType("movie")} title={t("home.qTopMovies")}>
            <BarChart3 className="dash-action-icon" aria-hidden="true" />
            <span className="dash-action-text">
              <strong>{t("home.qTopMovies")}</strong>
              <small>{t("home.qTopSub")}</small>
            </span>
          </FocusButton>
          <FocusButton className="dash-action dash-f" focusKey="home-q-top-series"
            disabled={tv && !hasSeries} onEnter={() => onOpenType("series")} title={t("home.qTopSeries")}>
            <Award className="dash-action-icon" aria-hidden="true" />
            <span className="dash-action-text">
              <strong>{t("home.qTopSeries")}</strong>
              <small>{t("home.qTopSub")}</small>
            </span>
          </FocusButton>
        </nav>

        {overlays}
      </div>
    );
  }

  /* ── Classic dashboard (unchanged) ── */
  return (
    <div className="home-screen">
      <header className="home-header">
        <div className="home-brand">
          <svg className="home-logo" viewBox="0 0 48 48" width="1em" height="1em" aria-hidden="true">
            <defs>
              <linearGradient id="home-grad" x1="0" y1="0" x2="1" y2="1">
                <stop offset="0" stopColor="#a855f7" />
                <stop offset="1" stopColor="#6d28d9" />
              </linearGradient>
            </defs>
            <rect x="3" y="3" width="42" height="42" rx="12" fill="url(#home-grad)" />
            <path d="M20 17l11 7-11 7z" fill="#fff" />
          </svg>
          <span className="home-title">MegaStream</span>
        </div>
        <div className="home-meta">
          <span className="home-expiry">{t("home.expires", { date: expiry })}</span>
          <LanguageToggle />
        </div>
      </header>

      <div className="home-grid">
        <FocusButton className="home-tile tile-live" autoFocus onEnter={() => onOpenType("live")}>
          {tv && <span className="remote-key remote-live" data-remote-key="403" aria-hidden="true" />}
          <IconLive />
          <span className="tile-label">{t("home.live")}</span>
        </FocusButton>

        <FocusButton className="home-tile" onEnter={() => onOpenType("movie")}>
          {tv && <span className="remote-key remote-movie" data-remote-key="404" aria-hidden="true" />}
          <IconMovies />
          <span className="tile-label">{t("home.movies")}</span>
        </FocusButton>

        <FocusButton className="home-tile" disabled={tv && !types.includes("series")} onEnter={() => onOpenType("series")}>
          {tv && types.includes("series") && <span className="remote-key remote-series" data-remote-key="405" aria-hidden="true" />}
          <IconSeries />
          <span className="tile-label">{t("home.series")}</span>
        </FocusButton>

        <FocusButton className="home-tile tile-muted" onEnter={() => {}}>
          <IconSports />
          <span className="tile-label">{t("home.sportsGuide")}</span>
          <span className="tile-badge">{t("home.soon")}</span>
        </FocusButton>

        <FocusButton className="home-tile" onEnter={onChangePlaylist}>
          <IconSwap />
          <span className="tile-label">{t("home.changePlaylist")}</span>
        </FocusButton>

        <div className="home-side">
          <FocusButton className="side-btn appearance-open" onEnter={() => setAppearanceOpen(true)}>
            <IconGear />
            <span>{t("home.settings")}</span>
          </FocusButton>
          <FocusButton className="side-btn sync-all" onEnter={handleReload} disabled={syncing}>
            <IconReload />
            <span>{lang === "ar" ? "مزامنة جميع القوائم" : "Sync all lists"}</span>
          </FocusButton>
          <FocusButton className="side-btn" onEnter={() => window.close()}>
            <IconExit />
            <span>{t("home.exit")}</span>
          </FocusButton>
        </div>
      </div>

      <div className="home-version">v{__APP_VERSION__}</div>

      {overlays}
    </div>
  );
}

/* ── Redesign helper components ── */

/** Live date + time for the brand chip; re-renders alone every few seconds. */
function BrandClock() {
  const { lang } = useLang();
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    const timer = setInterval(() => setNow(new Date()), 5000);
    return () => clearInterval(timer);
  }, []);
  const locale = lang === "ar" ? "ar-u-nu-latn" : "en-GB";
  return (
    <>
      <span className="dash-brand-date">{now.toLocaleDateString(locale, { weekday: "long", day: "numeric", month: "long" })}</span>
      <span className="dash-brand-time">{now.toLocaleTimeString(locale, { hour: "numeric", minute: "2-digit" })}</span>
    </>
  );
}

/** The hero panel's grid of small channel-logo tiles (skeletons while loading). */
function ChannelGrid({ items }: { items: ContentItem[] | null }) {
  if (items == null) {
    return (
      <>
        {Array.from({ length: CHANNEL_SLOTS }, (_, i) => (
          <span key={i} className="dash-channel dash-channel-skel" />
        ))}
      </>
    );
  }
  const slots = items.slice(0, CHANNEL_SLOTS);
  return (
    <>
      {slots.map((item) => (
        <span key={item.id} className="dash-channel">
          <ContentImage src={item.imageUrl} name={item.name} logo />
        </span>
      ))}
      {Array.from({ length: Math.max(0, CHANNEL_SLOTS - slots.length) }, (_, i) => (
        <span key={`empty-${i}`} className="dash-channel dash-channel-empty" />
      ))}
    </>
  );
}

/**
 * A fan of three overlapping poster cards. Real artwork is used when the
 * provider supplies it; otherwise a procedural gradient placeholder is shown.
 */
function PosterFan({ items, kind }: { items: ContentItem[] | null; kind: "movie" | "series" }) {
  const picks = (items ?? []).filter((item) => item.imageUrl).slice(0, 3);
  const Icon = kind === "movie" ? Clapperboard : Tv;
  return (
    <div className="dash-fan" aria-hidden="true">
      {[0, 1, 2].map((slot) => {
        const item = picks[slot] ?? null;
        return (
          <div key={slot} className={`dash-poster dash-poster-${slot}`}>
            {item?.imageUrl ? (
              <ContentImage src={item.imageUrl} name={item.name} />
            ) : (
              <span className={`dash-poster-fallback dash-poster-grad-${slot}`}>
                {item ? <strong>{item.name.charAt(0).toUpperCase()}</strong> : <Icon size={30} />}
                {item && <small dir="auto">{item.name}</small>}
              </span>
            )}
          </div>
        );
      })}
    </div>
  );
}

/* ── Classic icons ── */
function IconLive() {
  return (
    <svg className="tile-icon" viewBox="0 0 24 24" aria-hidden="true">
      <rect x="2" y="6" width="20" height="13" rx="2" fill="none" stroke="currentColor" strokeWidth="1.8" />
      <path d="M8 3l4 3 4-3" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
      <path d="M10.5 10.5l4 2-4 2z" fill="currentColor" />
    </svg>
  );
}
function IconMovies() {
  return (
    <svg className="tile-icon" viewBox="0 0 24 24" aria-hidden="true">
      <circle cx="12" cy="12" r="9" fill="none" stroke="currentColor" strokeWidth="1.8" />
      <path d="M10 8.5l5 3.5-5 3.5z" fill="currentColor" />
    </svg>
  );
}
function IconSeries() {
  return (
    <svg className="tile-icon" viewBox="0 0 24 24" aria-hidden="true">
      <rect x="3" y="8" width="18" height="12" rx="2" fill="none" stroke="currentColor" strokeWidth="1.8" />
      <path d="M5 8l3-4 3 4M11 8l3-4 3 4" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
    </svg>
  );
}
function IconSports() {
  return (
    <svg className="tile-icon" viewBox="0 0 24 24" aria-hidden="true">
      <circle cx="12" cy="12" r="9" fill="none" stroke="currentColor" strokeWidth="1.8" />
      <path d="M12 3v18M3 12h18" fill="none" stroke="currentColor" strokeWidth="1.4" />
    </svg>
  );
}
function IconSwap() {
  return (
    <svg className="tile-icon" viewBox="0 0 24 24" aria-hidden="true">
      <path d="M7 7h11l-3-3M17 17H6l3 3" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}
function IconGear() {
  return (
    <svg className="side-icon" viewBox="0 0 24 24" aria-hidden="true">
      <circle cx="12" cy="12" r="3.2" fill="none" stroke="currentColor" strokeWidth="1.8" />
      <path d="M12 2v3M12 19v3M2 12h3M19 12h3M5 5l2 2M17 17l2 2M19 5l-2 2M7 17l-2 2" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
    </svg>
  );
}
function IconReload() {
  return (
    <svg className="side-icon" viewBox="0 0 24 24" aria-hidden="true">
      <path d="M20 12a8 8 0 1 1-2.3-5.6" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
      <path d="M20 4v4h-4" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}
function IconExit() {
  return (
    <svg className="side-icon" viewBox="0 0 24 24" aria-hidden="true">
      <path d="M14 4h4a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-4" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
      <path d="M9 16l-4-4 4-4M5 12h10" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}
