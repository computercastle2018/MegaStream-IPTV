import { useEffect, useState } from "react";
import type { Playable } from "../types";
import { useHlsPlayer } from "../player/useHlsPlayer";
import { onBack } from "../remote/keys";
import { BackButton } from "../components/Focusable";
import { useLang } from "../i18n/LanguageContext";

interface Props {
  channel: Playable;
  onBack: () => void;
}

export default function PlayerScreen({ channel, onBack: goBack }: Props) {
  const { t } = useLang();
  const { videoRef, state, error } = useHlsPlayer(channel);
  const [overlayVisible, setOverlayVisible] = useState(true);

  useEffect(() => onBack(goBack), [goBack]);

  // Auto-hide the channel banner a few seconds after playback starts.
  useEffect(() => {
    if (state !== "playing") {
      setOverlayVisible(true);
      return;
    }
    const t = setTimeout(() => setOverlayVisible(false), 4000);
    return () => clearTimeout(t);
  }, [state]);

  // Any key press re-shows the banner.
  useEffect(() => {
    const show = () => setOverlayVisible(true);
    window.addEventListener("keydown", show);
    return () => window.removeEventListener("keydown", show);
  }, []);

  return (
    <div className="player-screen">
      <video ref={videoRef} autoPlay playsInline />

      <div className={`player-top ${overlayVisible ? "" : "hidden"}`}>
        <BackButton onEnter={goBack} autoFocus />
      </div>

      {state === "loading" && <div className="center-msg">{t("player.tuning")}</div>}
      {state === "error" && (
        <div className="center-msg error">
          <div>{t("player.failed")}</div>
          {error && <div style={{ fontSize: 22 }}>{error}</div>}
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
