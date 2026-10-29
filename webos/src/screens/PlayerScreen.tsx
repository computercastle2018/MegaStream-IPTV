import { useEffect, useState } from "react";
import type { Playable } from "../types";
import { useHlsPlayer } from "../player/useHlsPlayer";
import { onBack } from "../remote/keys";
import { BackButton, FocusButton, HomeButton } from "../components/Focusable";
import { RefreshCw } from "lucide-react";
import { useLang } from "../i18n/LanguageContext";

interface Props {
  channel: Playable;
  onBack: () => void;
  onHome: () => void;
}

export default function PlayerScreen({ channel, onBack: goBack, onHome }: Props) {
  const { t, lang } = useLang();
  const { videoRef, state, error, retry } = useHlsPlayer(channel);
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
      <video ref={videoRef} autoPlay playsInline controls={Boolean(window.megastreamDesktop)} />

      <div className={`player-top ${overlayVisible ? "" : "hidden"}`}>
        <BackButton onEnter={goBack} autoFocus />
        <HomeButton onEnter={onHome} />
      </div>

      {state === "loading" && <div className="center-msg">{t("player.tuning")}</div>}
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
