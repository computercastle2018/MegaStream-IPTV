import { useEffect, useState } from "react";
import { FocusContext, setFocus, useFocusable } from "@noriginmedia/norigin-spatial-navigation";
import type { Playable } from "../types";
import { useHlsPlayer } from "../player/useHlsPlayer";
import { onBack } from "../remote/keys";
import { BackButton, FocusButton, HomeButton } from "../components/Focusable";
import { Play, RefreshCw, RotateCcw, SlidersHorizontal, X } from "lucide-react";
import { useLang } from "../i18n/LanguageContext";
import { clearPlaybackProgress, loadPlaybackProgress } from "../services/playbackProgress";
import { loadVideoQuality, saveVideoQuality, VIDEO_HEIGHTS, type VideoQuality } from "../services/videoQuality";
import "./PlayerScreen.css";
import { useAppearance } from "../components/Appearance";
import TvProTransport from "../components/TvProTransport";

interface Props {
  channel: Playable;
  onBack: () => void;
  onHome: () => void;
  onSelectEpisode?: (episode: Playable) => void;
}

export default function PlayerScreen({ channel, onBack: goBack, onHome, onSelectEpisode }: Props) {
  const { t, lang } = useLang();
  const { design } = useAppearance();
  const tvpro = design === "tvpro";
  const [aspect, setAspect] = useState<"contain" | "cover" | "fill">("contain");
  const [transportExpanded, setTransportExpanded] = useState(false);
  const [saved] = useState(() => window.megastreamDesktop && channel.progressKey ? loadPlaybackProgress(channel.progressKey) : null);
  const [startAt, setStartAt] = useState<number | null>(saved ? null : 0);
  const [restartError, setRestartError] = useState(false);
  const [quality, setQuality] = useState(loadVideoQuality);
  const [qualityOpen, setQualityOpen] = useState(false);
  const [qualitySaveError, setQualitySaveError] = useState(false);
  const { videoRef, state, error, retry, progressError, tracks, selectTrack } = useHlsPlayer(startAt === null ? null : channel, startAt ?? 0, quality);
  const [overlayVisible, setOverlayVisible] = useState(true);
  const [interaction, setInteraction] = useState(0);

  useEffect(() => onBack(goBack), [goBack]);

  // Auto-hide the channel banner a few seconds after playback starts.
  useEffect(() => {
    if (state !== "playing" || (tvpro && (qualityOpen || transportExpanded))) {
      setOverlayVisible(true);
      return;
    }
    const t = setTimeout(() => setOverlayVisible(false), 4000);
    return () => clearTimeout(t);
  }, [state, interaction, tvpro, qualityOpen, transportExpanded]);

  useEffect(() => {
    if (!tvpro || overlayVisible) return;
    const reveal = (event: KeyboardEvent) => {
      if (["Escape", "Backspace"].includes(event.key) || event.keyCode === 461 || event.keyCode === 10009) return;
      event.preventDefault(); event.stopImmediatePropagation();
      setOverlayVisible(true); setInteraction(value => value + 1);
    };
    window.addEventListener("keydown", reveal, true);
    return () => window.removeEventListener("keydown", reveal, true);
  }, [tvpro, overlayVisible]);

  // Any key press re-shows the banner.
  useEffect(() => {
    const show = () => { setOverlayVisible(true); setInteraction((value) => value + 1); };
    window.addEventListener("keydown", show);
    window.addEventListener("pointermove", show);
    return () => { window.removeEventListener("keydown", show); window.removeEventListener("pointermove", show); };
  }, []);

  return (
    <div className={`player-screen ${window.megastreamDesktop ? "desktop-player" : ""} ${tvpro ? "tvpro-player" : ""}`}>
      <video ref={videoRef} autoPlay={startAt !== null} playsInline controls={!tvpro && Boolean(window.megastreamDesktop) && startAt !== null}
        style={tvpro ? { objectFit: aspect } : undefined} />

      <div className={`player-top ${overlayVisible ? "" : "hidden"}`}>
        <BackButton onEnter={goBack} autoFocus={startAt !== null} />
        <HomeButton onEnter={onHome} />
        {tvpro && <h1 dir="auto">{channel.name}</h1>}
        {!tvpro && <FocusButton className="back-btn" title={lang === "ar" ? "جودة العرض" : "Video quality"}
          hasPopup="dialog" expanded={qualityOpen} onEnter={() => setQualityOpen(true)}>
          <SlidersHorizontal size={20} aria-hidden="true" />{lang === "ar" ? "الجودة" : "Quality"}
        </FocusButton>}
      </div>

      {qualityOpen && <QualityDialog value={quality} currentHeight={videoRef.current?.videoHeight ?? 0}
        saveError={qualitySaveError} onClose={() => setQualityOpen(false)} onChange={value => {
          setQuality(value); setQualitySaveError(!saveVideoQuality(value));
        }} />}

      {startAt === null && saved && <ResumeDialog name={channel.name} position={saved.position} onClose={goBack}
        onResume={() => setStartAt(saved.position)} onRestart={() => {
          setRestartError(Boolean(channel.progressKey && !clearPlaybackProgress(channel.progressKey)));
          setStartAt(0);
        }} />}
      {(progressError || restartError) && <p className="playback-save-error" role="status">
        {lang === "ar" ? "تعذر حفظ موضع التوقف على هذا الجهاز" : "Unable to save playback position on this device"}
      </p>}
      {state === "loading" && startAt !== null && <div className="center-msg">{t("player.tuning")}</div>}
      {state === "error" && (
        <div className="center-msg error">
          <div>{t("player.failed")}</div>
          {error && <div style={{ fontSize: 22 }}>{error}</div>}
          <FocusButton className="back-btn retry-playback" onEnter={retry} autoFocus>
            <RefreshCw size={20} aria-hidden="true" />{lang === "ar" ? "إعادة المحاولة" : "Retry playback"}
          </FocusButton>
          <div style={{ fontSize: 20 }}>{t("player.back")}</div>
        </div>
      )}

      {tvpro && overlayVisible && startAt !== null && <TvProTransport videoRef={videoRef} channel={channel}
        tracks={tracks} selectTrack={selectTrack} onQuality={() => setQualityOpen(true)} aspect={aspect}
        onAspect={() => setAspect(value => value === "contain" ? "cover" : value === "cover" ? "fill" : "contain")}
        onEpisode={onSelectEpisode} onExpanded={setTransportExpanded} />}
      {!tvpro && overlayVisible && (
        <div className="player-overlay">
          <span className="ch-name">
            {channel.number ? `${channel.number}. ` : ""}
            {channel.name}
          </span>
          <span className="ch-group">{channel.groupTitle}</span>
        </div>
      )}
    </div>
  );
}

function QualityDialog({ value, currentHeight, saveError, onChange, onClose }: {
  value: VideoQuality; currentHeight: number; saveError: boolean;
  onChange: (value: VideoQuality) => void; onClose: () => void;
}) {
  const { lang } = useLang();
  const ar = lang === "ar";
  const { ref, focusKey } = useFocusable<HTMLDialogElement>({ isFocusBoundary: true });
  useEffect(() => {
    const previous = document.activeElement;
    ref.current?.showModal();
    setFocus(`quality-${value.height ?? "auto"}`);
    const back = (event: KeyboardEvent) => {
      if (!["Escape", "Backspace"].includes(event.key) && event.keyCode !== 461 && event.keyCode !== 10009) return;
      event.preventDefault(); event.stopImmediatePropagation(); onClose();
    };
    window.addEventListener("keydown", back, true);
    return () => {
      window.removeEventListener("keydown", back, true);
      if (previous instanceof HTMLElement && previous.isConnected) previous.focus();
    };
  }, [ref]);
  return <FocusContext.Provider value={focusKey}><dialog ref={ref} className="appearance-dialog quality-dialog"
    aria-labelledby="quality-title" onCancel={event => { event.preventDefault(); onClose(); }}>
    <header><h2 id="quality-title">{ar ? "جودة العرض" : "Video quality"}</h2>
      <FocusButton className="appearance-close" title={ar ? "إغلاق" : "Close"} onEnter={onClose}><X size={22} /></FocusButton>
    </header>
    {currentHeight > 0 && <p>{ar ? "الدقة الحالية" : "Current resolution"}: <bdi>{currentHeight}p</bdi></p>}
    <fieldset><legend>{ar ? "الدقة المفضلة" : "Preferred resolution"}</legend>
      <div className="quality-options">{[null, ...VIDEO_HEIGHTS].map(height => <FocusButton key={height ?? "auto"}
        className="quality-option" focusKey={`quality-${height ?? "auto"}`} pressed={value.height === height}
        onEnter={() => onChange({ ...value, height })}>
        {height === null ? (ar ? "تلقائي" : "Auto") : height === 2160 ? "2160p (4K)" : `${height}p`}
      </FocusButton>)}</div>
    </fieldset>
    <FocusButton className="quality-lock" pressed={value.fixed} onEnter={() => onChange({ ...value, fixed: !value.fixed })}>
      <input type="checkbox" checked={value.fixed} readOnly tabIndex={-1} aria-hidden="true" />
      {ar ? "تثبيت الجودة وإيقاف التخفيض التلقائي" : "Lock quality (no automatic reduction)"}
    </FocusButton>
    {saveError && <p role="alert">{ar ? "تعذر حفظ إعداد الجودة" : "Unable to save quality preference"}</p>}
  </dialog></FocusContext.Provider>;
}

function ResumeDialog({ name, position, onResume, onRestart, onClose }: {
  name: string; position: number; onResume: () => void; onRestart: () => void; onClose: () => void;
}) {
  const { lang } = useLang();
  const ar = lang === "ar";
  const { ref, focusKey } = useFocusable<HTMLDialogElement>({ isFocusBoundary: true });
  useEffect(() => {
    ref.current?.showModal();
    setFocus("resume-play");
    ref.current?.querySelector(".resume-primary")?.focus();
  }, [ref]);
  const seconds = Math.floor(position);
  const time = [Math.floor(seconds / 3600), Math.floor(seconds / 60) % 60, seconds % 60]
    .map(value => String(value).padStart(2, "0")).join(":");
  return <FocusContext.Provider value={focusKey}>
    <dialog ref={ref} className="appearance-dialog resume-dialog" aria-labelledby="resume-title"
      onCancel={event => { event.preventDefault(); onClose(); }}>
      <header><h2 id="resume-title">{ar ? "متابعة المشاهدة" : "Continue watching"}</h2>
        <FocusButton className="appearance-close" title={ar ? "إغلاق" : "Close"} onEnter={onClose}><X size={22} /></FocusButton>
      </header>
      <p className="resume-name" dir="auto">{name}</p>
      <p className="resume-position">{ar ? "توقفت عند" : "Last watched at"} <bdi>{time}</bdi></p>
      <div className="resume-actions">
        <FocusButton className="resume-primary" focusKey="resume-play" onEnter={onResume}><Play size={20} />{ar ? "استئناف المشاهدة" : "Resume watching"}</FocusButton>
        <FocusButton className="resume-restart" onEnter={onRestart}><RotateCcw size={20} />{ar ? "البدء من جديد" : "Start over"}</FocusButton>
      </div>
    </dialog>
  </FocusContext.Provider>;
}
