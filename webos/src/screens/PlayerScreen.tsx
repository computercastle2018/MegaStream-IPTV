import { useEffect, useState } from "react";
import { FocusContext, setFocus, useFocusable } from "@noriginmedia/norigin-spatial-navigation";
import type { Playable } from "../types";
import { useHlsPlayer } from "../player/useHlsPlayer";
import { onBack } from "../remote/keys";
import { BackButton, FocusButton, HomeButton } from "../components/Focusable";
import { Play, RefreshCw, RotateCcw, X } from "lucide-react";
import { useLang } from "../i18n/LanguageContext";
import { clearPlaybackProgress, loadPlaybackProgress } from "../services/playbackProgress";
import "./PlayerScreen.css";

interface Props {
  channel: Playable;
  onBack: () => void;
  onHome: () => void;
}

export default function PlayerScreen({ channel, onBack: goBack, onHome }: Props) {
  const { t, lang } = useLang();
  const [saved] = useState(() => window.megastreamDesktop && channel.progressKey ? loadPlaybackProgress(channel.progressKey) : null);
  const [startAt, setStartAt] = useState<number | null>(saved ? null : 0);
  const [restartError, setRestartError] = useState(false);
  const { videoRef, state, error, retry, progressError } = useHlsPlayer(startAt === null ? null : channel, startAt ?? 0);
  const [overlayVisible, setOverlayVisible] = useState(true);
  const [interaction, setInteraction] = useState(0);

  useEffect(() => onBack(goBack), [goBack]);

  // Auto-hide the channel banner a few seconds after playback starts.
  useEffect(() => {
    if (state !== "playing") {
      setOverlayVisible(true);
      return;
    }
    const t = setTimeout(() => setOverlayVisible(false), 4000);
    return () => clearTimeout(t);
  }, [state, interaction]);

  // Any key press re-shows the banner.
  useEffect(() => {
    const show = () => { setOverlayVisible(true); setInteraction((value) => value + 1); };
    window.addEventListener("keydown", show);
    window.addEventListener("pointermove", show);
    return () => { window.removeEventListener("keydown", show); window.removeEventListener("pointermove", show); };
  }, []);

  return (
    <div className={`player-screen ${window.megastreamDesktop ? "desktop-player" : ""}`}>
      <video ref={videoRef} autoPlay={startAt !== null} playsInline controls={Boolean(window.megastreamDesktop) && startAt !== null} />

      <div className={`player-top ${overlayVisible ? "" : "hidden"}`}>
        <BackButton onEnter={goBack} autoFocus={startAt !== null} />
        <HomeButton onEnter={onHome} />
      </div>

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

      {overlayVisible && (
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
