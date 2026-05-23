import { useCallback, useEffect, useState } from "react";
import type { ContentType, Provider } from "../types";
import { FocusButton, LanguageToggle } from "../components/Focusable";
import LoadingOverlay from "../components/LoadingOverlay";
import { getAccountInfo } from "../services/xtreamClient";
import { refreshAllContent } from "../services/content";
import { onBack } from "../remote/keys";
import { useLang } from "../i18n/LanguageContext";

interface Props {
  provider: Provider;
  onOpenType: (type: ContentType) => void;
  onChangePlaylist: () => void;
  onSettings: () => void;
}

export default function HomeScreen({ provider, onOpenType, onChangePlaylist, onSettings }: Props) {
  const { t } = useLang();
  const [expiry, setExpiry] = useState<string>(t("home.unlimited"));
  const [syncing, setSyncing] = useState(false);

  useEffect(() => onBack(onChangePlaylist), [onChangePlaylist]);

  const refreshExpiry = useCallback(
    async (signal?: AbortSignal) => {
      if (provider.type !== "xtream") {
        setExpiry(t("home.unlimited"));
        return;
      }
      try {
        const info = await getAccountInfo(provider, signal);
        setExpiry(
          info.expDate
            ? new Date(info.expDate * 1000).toLocaleDateString()
            : t("home.unlimited"),
        );
      } catch {
        if (!signal?.aborted) setExpiry(t("home.unlimited"));
      }
    },
    [provider, t],
  );

  useEffect(() => {
    const controller = new AbortController();
    void refreshExpiry(controller.signal);
    return () => controller.abort();
  }, [refreshExpiry]);

  // Reload: re-sync the whole library (live / movies / series) plus the
  // account info, showing the animated loading overlay until it completes.
  const handleReload = useCallback(async () => {
    if (syncing) return;
    setSyncing(true);
    try {
      await Promise.all([refreshAllContent(provider), refreshExpiry()]);
    } catch {
      // Errors surface again when the user opens a section; keep the home calm.
    } finally {
      setSyncing(false);
    }
  }, [syncing, provider, refreshExpiry]);

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
          <IconLive />
          <span className="tile-label">{t("home.live")}</span>
        </FocusButton>

        <FocusButton className="home-tile" onEnter={() => onOpenType("movie")}>
          <IconMovies />
          <span className="tile-label">{t("home.movies")}</span>
        </FocusButton>

        <FocusButton className="home-tile" onEnter={() => onOpenType("series")}>
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
          <FocusButton className="side-btn" onEnter={onSettings}>
            <IconGear />
            <span>{t("home.settings")}</span>
          </FocusButton>
          <FocusButton className="side-btn" onEnter={handleReload}>
            <IconReload />
            <span>{t("home.reload")}</span>
          </FocusButton>
          <FocusButton className="side-btn" onEnter={() => window.close()}>
            <IconExit />
            <span>{t("home.exit")}</span>
          </FocusButton>
        </div>
      </div>

      <div className="home-version">v0.1.0</div>

      {syncing && <LoadingOverlay message={t("home.syncing")} />}
    </div>
  );
}

/* ── Icons ── */
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
