import { useEffect, useState, type RefObject } from "react";
import { FocusContext, getCurrentFocusKey, setFocus, useFocusable } from "@noriginmedia/norigin-spatial-navigation";
import { AudioLines, Captions, Expand, FastForward, ListVideo, Pause, Play, Rewind, SkipBack, SkipForward, SlidersHorizontal, X } from "lucide-react";
import type { Playable } from "../types";
import type { PlayerTrack } from "../player/useHlsPlayer";
import { useLang } from "../i18n/LanguageContext";
import { playbackTime, seekTarget } from "../services/playerControls";
import { FocusButton } from "./Focusable";

interface Props {
  videoRef: RefObject<HTMLVideoElement>;
  channel: Playable;
  tracks: { audio: PlayerTrack[]; subtitles: PlayerTrack[] };
  selectTrack: (kind: "audio" | "subtitles", id: number) => void;
  onQuality: () => void;
  onAspect: () => void;
  aspect: string;
  onEpisode?: (episode: Playable) => void;
  onExpanded: (expanded: boolean) => void;
}

export default function TvProTransport({ videoRef, channel, tracks, selectTrack, onQuality, onAspect, aspect, onEpisode, onExpanded }: Props) {
  const { lang } = useLang();
  const ar = lang === "ar";
  const [media, setMedia] = useState({ current: 0, start: 0, end: 0, paused: false });
  const [menu, setMenu] = useState<"audio" | "subtitles" | null>(null);
  const [tray, setTray] = useState(false);
  const [season, setSeason] = useState<number | null>(null);
  const [playError, setPlayError] = useState(false);
  const queue = channel.episodeQueue ?? [];
  const currentIndex = queue.findIndex(item => item.progressKey === channel.progressKey);
  const seasonOf = (item: Playable) => item.season ?? 1;
  const seasons = [...new Set(queue.map(seasonOf))];
  useEffect(() => {
    const video = videoRef.current;
    if (!video) return;
    const update = () => setMedia({ current: video.currentTime, paused: video.paused,
      start: Number.isFinite(video.duration) ? 0 : video.seekable.length ? video.seekable.start(0) : 0,
      end: Number.isFinite(video.duration) ? video.duration : video.seekable.length ? video.seekable.end(video.seekable.length - 1) : 0 });
    const events = ["timeupdate", "durationchange", "progress", "play", "pause", "loadedmetadata"];
    events.forEach(event => video.addEventListener(event, update)); update();
    return () => events.forEach(event => video.removeEventListener(event, update));
  }, [videoRef]);
  useEffect(() => { onExpanded(tray || menu !== null || media.paused); return () => onExpanded(false); }, [tray, menu, media.paused, onExpanded]);
  useEffect(() => {
    if (!tray) return;
    setFocus("tvpro-tray-current");
    const back = (event: KeyboardEvent) => {
      if (!["Escape", "Backspace"].includes(event.key) && event.keyCode !== 461 && event.keyCode !== 10009) return;
      event.preventDefault(); event.stopImmediatePropagation(); setTray(false); setFocus("tvpro-tray-toggle");
    };
    window.addEventListener("keydown", back, true);
    return () => window.removeEventListener("keydown", back, true);
  }, [tray]);
  const seek = (target: number) => {
    const position = seekTarget(target, media.start, media.end);
    if (position !== null && videoRef.current) videoRef.current.currentTime = position;
  };
  const togglePlay = () => {
    const video = videoRef.current;
    if (!video) return;
    setPlayError(false);
    if (video.paused) video.play().catch(() => setPlayError(true)); else video.pause();
  };
  const seekable = media.end > media.start;
  return <div className="tvpro-transport">
    <div className="tvpro-seek-line" dir="ltr">
      <time>{playbackTime(media.current)}</time>
      <SeekBar value={media.current} start={media.start} end={media.end} onSeek={seek} label={ar ? "موضع التشغيل" : "Playback position"} />
      <time>{seekable ? playbackTime(media.end) : (ar ? "مباشر" : "LIVE")}</time>
    </div>
    <div className="tvpro-transport-buttons" dir="ltr">
      <FocusButton title={ar ? "الحلقة السابقة" : "Previous episode"} disabled={currentIndex <= 0 || !onEpisode} onEnter={() => onEpisode?.(queue[currentIndex - 1])}><SkipBack /></FocusButton>
      <FocusButton title={ar ? "رجوع 10 ثوان" : "Rewind 10 seconds"} disabled={!seekable} onEnter={() => seek(media.current - 10)}><Rewind /></FocusButton>
      <FocusButton className="tvpro-play-toggle" focusKey="tvpro-play-toggle" autoFocus title={media.paused ? (ar ? "تشغيل" : "Play") : (ar ? "إيقاف مؤقت" : "Pause")} onEnter={togglePlay}>{media.paused ? <Play /> : <Pause />}</FocusButton>
      <FocusButton title={ar ? "تقديم 10 ثوان" : "Forward 10 seconds"} disabled={!seekable} onEnter={() => seek(media.current + 10)}><FastForward /></FocusButton>
      <FocusButton title={ar ? "الحلقة التالية" : "Next episode"} disabled={currentIndex < 0 || currentIndex >= queue.length - 1 || !onEpisode} onEnter={() => onEpisode?.(queue[currentIndex + 1])}><SkipForward /></FocusButton>
    </div>
    <div className="tvpro-player-tools">
      <FocusButton title={ar ? "الصوت" : "Audio"} hasPopup="dialog" expanded={menu === "audio"} onEnter={() => { setTray(false); setMenu("audio"); }}><AudioLines /></FocusButton>
      <FocusButton title={ar ? "الترجمة" : "Subtitles"} hasPopup="dialog" expanded={menu === "subtitles"} onEnter={() => { setTray(false); setMenu("subtitles"); }}><Captions /></FocusButton>
      <FocusButton title={`${ar ? "أبعاد الصورة" : "Aspect ratio"}: ${aspect}`} onEnter={onAspect}><Expand /></FocusButton>
      <FocusButton title={ar ? "جودة العرض" : "Video quality"} hasPopup="dialog" onEnter={() => { setTray(false); onQuality(); }}><SlidersHorizontal /></FocusButton>
      {queue.length > 0 && <FocusButton focusKey="tvpro-tray-toggle" title={ar ? "المواسم والحلقات" : "Seasons & episodes"} expanded={tray} onEnter={() => setTray(!tray)}><ListVideo /></FocusButton>}
    </div>
    {playError && <p role="alert">{ar ? "تعذر تشغيل الفيديو. أعد المحاولة." : "Unable to play video. Try again."}</p>}
    {tray && <section className="tvpro-episode-tray" aria-label={ar ? "الحلقات" : "Episodes"}>
      <nav>{seasons.map(value => <FocusButton key={value} pressed={(season ?? seasonOf(channel)) === value} onEnter={() => setSeason(value)}>{ar ? "الموسم" : "Season"} {value}</FocusButton>)}</nav>
      <div>{queue.filter(item => seasonOf(item) === (season ?? seasonOf(channel))).map(item => <FocusButton key={item.progressKey}
        focusKey={item.progressKey === channel.progressKey ? "tvpro-tray-current" : undefined} pressed={item.progressKey === channel.progressKey}
        onEnter={() => onEpisode?.(item)}><Play size={18} /><span dir="auto">{item.name}</span></FocusButton>)}</div>
    </section>}
    {menu && <TrackDialog title={menu === "audio" ? (ar ? "الصوت" : "Audio") : (ar ? "الترجمة" : "Subtitles")}
      tracks={menu === "subtitles" ? [{ id: -1, label: ar ? "إيقاف" : "Off", selected: !tracks.subtitles.some(track => track.selected) }, ...tracks.subtitles] : tracks.audio}
      onSelect={id => { selectTrack(menu, id); setMenu(null); }} onClose={() => setMenu(null)} />}
  </div>;
}

function SeekBar({ value, start, end, label, onSeek }: { value: number; start: number; end: number; label: string; onSeek: (value: number) => void }) {
  const enabled = end > start;
  const { ref, focused, focusSelf } = useFocusable<HTMLInputElement>({ focusable: enabled,
    onArrowPress: direction => {
      if (direction !== "left" && direction !== "right") return true;
      onSeek(value + (direction === "left" ? -10 : 10)); return false;
    },
  });
  return <input ref={ref} type="range" className={focused ? "focused" : ""} aria-label={label} disabled={!enabled}
    min={start} max={enabled ? end : start + 1} step="1" value={Math.max(start, Math.min(end || start, value))}
    onFocus={() => focusSelf()} onChange={event => onSeek(Number(event.target.value))} />;
}

function TrackDialog({ title, tracks, onSelect, onClose }: { title: string; tracks: PlayerTrack[]; onSelect: (id: number) => void; onClose: () => void }) {
  const { lang } = useLang();
  const { ref, focusKey } = useFocusable<HTMLDialogElement>({ isFocusBoundary: true });
  useEffect(() => {
    const previous = getCurrentFocusKey();
    ref.current?.showModal(); setFocus(focusKey);
    const back = (event: KeyboardEvent) => {
      if (!["Escape", "Backspace"].includes(event.key) && event.keyCode !== 461 && event.keyCode !== 10009) return;
      event.preventDefault(); event.stopImmediatePropagation(); onClose();
    };
    window.addEventListener("keydown", back, true);
    return () => { window.removeEventListener("keydown", back, true); if (previous) setFocus(previous); };
  }, [focusKey, ref]);
  return <FocusContext.Provider value={focusKey}><dialog ref={ref} className="appearance-dialog tvpro-track-dialog" aria-label={title}
    onCancel={event => { event.preventDefault(); onClose(); }}>
    <header><h2>{title}</h2><FocusButton className="appearance-close" title={lang === "ar" ? "إغلاق" : "Close"} onEnter={onClose}><X /></FocusButton></header>
    {tracks.length ? tracks.map(track => <FocusButton key={track.id} className="quality-option" pressed={track.selected} onEnter={() => onSelect(track.id)}>{track.label}</FocusButton>)
      : <p>{lang === "ar" ? "لا توجد مسارات متاحة من هذا المصدر" : "No tracks available from this source"}</p>}
  </dialog></FocusContext.Provider>;
}
