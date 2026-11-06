import { useEffect, useRef, useState } from "react";
import Hls from "hls.js";
import type { Playable } from "../types";
import { clearPlaybackProgress, savePlaybackProgress } from "../services/playbackProgress";
import { selectQualityLevel, type VideoQuality } from "../services/videoQuality";

export type PlaybackState = "loading" | "playing" | "error";
export interface PlayerTrack { id: number; label: string; selected: boolean }
interface NativeAudioTracks extends EventTarget {
  length: number;
  [index: number]: { label: string; language: string; enabled: boolean };
}

/**
 * Attaches a stream to a <video> element.
 *
 * Strategy:
 *  - .m3u8 (HLS): prefer native HLS on webOS; desktop uses hls.js/MSE because
 *    its native HLS path can play HEVC streams as audio only.
 *  - Raw TS: use mpegts.js on desktop, the native pipeline on webOS.
 *  - Xtream live sources include a TS alternative, tried once after HLS fails.
 */
export function useHlsPlayer(channel: Playable | null, startAt = 0, quality: VideoQuality = { height: 1080, fixed: false }) {
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const [state, setState] = useState<PlaybackState>("loading");
  const [error, setError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);
  const [fallbackFor, setFallbackFor] = useState<Playable | null>(null);
  const [progressError, setProgressError] = useState(false);
  const [tracks, setTracks] = useState<{ audio: PlayerTrack[]; subtitles: PlayerTrack[] }>({ audio: [], subtitles: [] });
  const selectTrackRef = useRef<(kind: "audio" | "subtitles", id: number) => void>(() => {});
  const qualityRef = useRef(quality);
  qualityRef.current = quality;
  const applyQualityRef = useRef<() => void>(() => {});
  useEffect(() => { applyQualityRef.current(); }, [quality.height, quality.fixed]);
  const checkpoint = useRef<{ channel: Playable | null; position: number }>({ channel: null, position: 0 });
  const useFallback = fallbackFor === channel && Boolean(channel?.fallbackStreamUrl);

  useEffect(() => {
    const video = videoRef.current;
    if (!video || !channel) return;

    setState("loading");
    setError(null);
    setProgressError(false);

    let hls: Hls | null = null;
    let transport: ReturnType<typeof import("mpegts.js").default.createPlayer> | null = null;
    let disposed = false;
    let recoveredMedia = false;
    const url = useFallback ? channel.fallbackStreamUrl! : channel.streamUrl;
    const isHls = !useFallback && (channel.streamFormat === "hls" || url.split("?")[0].toLowerCase().endsWith(".m3u8"));
    const nativeHls = video.canPlayType("application/vnd.apple.mpegurl") !== "";
    const nativePipeline = isHls && nativeHls && !window.megastreamDesktop;
    let nativeParserFailed = false;
    let nativeQualityWaiting = false;
    const nativeAudio = (video as HTMLVideoElement & { audioTracks?: NativeAudioTracks }).audioTracks;
    const updateTracks = () => {
      if (disposed) return;
      if (hls && !nativePipeline) setTracks({
        audio: hls.audioTracks.map((track, id) => ({ id, label: track.name || track.lang || String(id + 1), selected: hls!.audioTrack === id })),
        subtitles: hls.subtitleTracks.map((track, id) => ({ id, label: track.name || track.lang || String(id + 1), selected: hls!.subtitleTrack === id })),
      });
      else setTracks({
        audio: Array.from({ length: nativeAudio?.length ?? 0 }, (_, id) => ({ id,
          label: nativeAudio![id].label || nativeAudio![id].language || String(id + 1), selected: nativeAudio![id].enabled })),
        subtitles: Array.from(video.textTracks, (track, id) => ({ id,
          label: track.label || track.language || String(id + 1), selected: track.mode === "showing" })),
      });
    };
    selectTrackRef.current = (kind, id) => {
      if (hls && !nativePipeline) {
        if (kind === "audio" && hls.audioTracks[id]) hls.audioTrack = id;
        if (kind === "subtitles" && (id === -1 || hls.subtitleTracks[id])) { hls.subtitleTrack = id; hls.subtitleDisplay = id !== -1; }
      } else if (kind === "audio" && nativeAudio?.[id]) {
        for (let index = 0; index < nativeAudio.length; index++) nativeAudio[index].enabled = index === id;
      } else if (kind === "subtitles") {
        Array.from(video.textTracks).forEach((track, index) => { track.mode = index === id ? "showing" : "disabled"; });
      }
      updateTracks();
    };
    video.addEventListener("loadedmetadata", updateTracks);
    nativeAudio?.addEventListener("addtrack", updateTracks);
    nativeAudio?.addEventListener("change", updateTracks);
    video.textTracks.addEventListener("addtrack", updateTracks);
    video.textTracks.addEventListener("change", updateTracks);
    updateTracks();
    const applyQuality = () => {
      if (!hls?.levels.length) {
        if (nativePipeline && qualityRef.current.fixed) {
          nativeQualityWaiting ||= !video.paused || !hasPlayed;
          video.pause();
          if (nativeParserFailed) fail("Unable to select a fixed quality from this source");
        } else if (nativePipeline) {
          if (video.src !== url) video.src = url;
          if (nativeQualityWaiting || !hasPlayed || !video.paused) video.play().catch(() => fail("Playback was blocked"));
          nativeQualityWaiting = false;
        }
        return;
      }
      const selected = selectQualityLevel(hls.levels, qualityRef.current.height);
      if (nativePipeline) {
        const target = qualityRef.current.fixed ? hls.levels[selected]?.url[0] : url;
        const resume = nativeQualityWaiting || !video.paused || !hasPlayed;
        if (target && video.src !== target) {
          const previous = video.currentTime;
          video.src = target;
          video.addEventListener("loadedmetadata", () => {
            if (Number.isFinite(video.duration) && previous > 0 && previous < video.duration) video.currentTime = previous;
          }, { once: true });
        }
        if (target && resume) video.play().catch(() => fail("Playback was blocked"));
        nativeQualityWaiting = false;
      } else {
        hls.autoLevelCapping = qualityRef.current.height === null ? -1 : selected;
        hls.currentLevel = qualityRef.current.fixed ? selected : -1;
      }
    };
    applyQualityRef.current = applyQuality;
    const progressKey = window.megastreamDesktop ? channel.progressKey : undefined;
    if (checkpoint.current.channel !== channel) checkpoint.current = { channel, position: startAt };
    const position = progressKey ? checkpoint.current.position : 0;
    let seekApplied = position === 0, hasPlayed = false, completed = false, lastSaved = 0;
    const seek = () => {
      if (seekApplied || !Number.isFinite(video.duration) || video.duration <= 0) return;
      try {
        video.currentTime = position < video.duration ? position : 0;
        seekApplied = true;
      } catch { /* Wait for the next metadata/canplay event before seeking again. */ }
    };
    const save = () => {
      if (!progressKey || !hasPlayed || !seekApplied || completed || video.seeking) return;
      checkpoint.current.position = video.currentTime;
      setProgressError(!savePlaybackProgress(progressKey, video.currentTime, video.duration));
      lastSaved = Date.now();
    };
    const track = () => {
      if (hasPlayed && seekApplied && !video.seeking) checkpoint.current.position = video.currentTime;
      if (Date.now() - lastSaved >= 5000) save();
    };
    const ended = () => {
      completed = true;
      checkpoint.current.position = 0;
      if (progressKey) setProgressError(!clearPlaybackProgress(progressKey));
    };
    const hide = () => { if (document.visibilityState === "hidden") save(); };

    const onPlaying = () => { seek(); hasPlayed = true; setState("playing"); };
    const fail = (msg: string) => {
      if (disposed) return;
      if (!useFallback && channel.fallbackStreamUrl) {
        setFallbackFor(channel);
        return;
      }
      setError(msg);
      setState("error");
    };

    video.addEventListener("playing", onPlaying);
    video.addEventListener("loadedmetadata", seek);
    video.addEventListener("durationchange", seek);
    video.addEventListener("canplay", seek);
    video.addEventListener("timeupdate", track);
    video.addEventListener("pause", save);
    video.addEventListener("ended", ended);
    window.addEventListener("pagehide", save);
    window.addEventListener("beforeunload", save);
    document.addEventListener("visibilitychange", hide);

    // Desktop's native HLS may decode only audio for HEVC; use the MSE pipeline.
    if (isHls && (nativePipeline || Hls.isSupported())) {
      hls = new Hls({ enableWorker: true, lowLatencyMode: false, autoStartLoad: !nativePipeline,
        abrEwmaDefaultEstimate: 2_000_000, abrBandWidthFactor: 0.6, abrBandWidthUpFactor: 0.5 });
      hls.loadSource(url);
      hls.on(Hls.Events.AUDIO_TRACKS_UPDATED, updateTracks);
      hls.on(Hls.Events.SUBTITLE_TRACKS_UPDATED, updateTracks);
      hls.on(Hls.Events.AUDIO_TRACK_SWITCHED, updateTracks);
      hls.on(Hls.Events.SUBTITLE_TRACK_SWITCH, updateTracks);
      if (!nativePipeline) hls.attachMedia(video);
      else if (!qualityRef.current.fixed) {
        video.src = url;
        video.play().catch(() => fail("Playback was blocked"));
      }
      hls.on(Hls.Events.MANIFEST_PARSED, () => {
        applyQuality();
        if (!nativePipeline) video.play().catch(() => fail("Playback was blocked"));
      });
      hls.on(Hls.Events.ERROR, (_evt, data) => {
        if (!data.fatal || disposed) return;
        if (nativePipeline) {
          nativeParserFailed = true;
          if (qualityRef.current.fixed) fail("Unable to select a fixed quality from this source");
        } else if (data.type === Hls.ErrorTypes.MEDIA_ERROR && !recoveredMedia) {
          recoveredMedia = true;
          hls?.recoverMediaError();
        } else fail(`Stream error: ${data.details}`);
      });
    } else if (window.megastreamDesktop && (useFallback || channel.streamFormat === "ts" || url.split("?")[0].toLowerCase().endsWith(".ts"))) {
      import("mpegts.js").then(({ default: mpegts }) => {
        if (disposed) return;
        if (!mpegts.isSupported()) { fail("MPEG-TS playback is unavailable"); return; }
        transport = mpegts.createPlayer({ type: "mpegts", url, isLive: !/\/movie\/|\/series\//.test(url) });
        transport.on(mpegts.Events.ERROR, () => fail("Unable to load this MPEG-TS stream"));
        transport.attachMediaElement(video);
        transport.load();
        transport.play()?.catch(() => fail("Playback was blocked"));
      }).catch(() => { if (!disposed) fail("Unable to start MPEG-TS playback"); });
    } else {
      video.src = url;
      video.play().catch(() => fail("Playback was blocked"));
    }

    const onVideoError = () => { if (!hls || nativePipeline) fail("Unable to load this stream"); };
    video.addEventListener("error", onVideoError);

    return () => {
      save();
      disposed = true;
      selectTrackRef.current = () => {};
      video.removeEventListener("loadedmetadata", updateTracks);
      nativeAudio?.removeEventListener("addtrack", updateTracks);
      nativeAudio?.removeEventListener("change", updateTracks);
      video.textTracks.removeEventListener("addtrack", updateTracks);
      video.textTracks.removeEventListener("change", updateTracks);
      applyQualityRef.current = () => {};
      video.removeEventListener("playing", onPlaying);
      video.removeEventListener("loadedmetadata", seek);
      video.removeEventListener("durationchange", seek);
      video.removeEventListener("canplay", seek);
      video.removeEventListener("timeupdate", track);
      video.removeEventListener("pause", save);
      video.removeEventListener("ended", ended);
      window.removeEventListener("pagehide", save);
      window.removeEventListener("beforeunload", save);
      document.removeEventListener("visibilitychange", hide);
      video.removeEventListener("error", onVideoError);
      if (hls) hls.destroy();
      if (transport) transport.destroy();
      video.removeAttribute("src");
      video.load();
    };
  }, [channel, useFallback, attempt, startAt]);

  const retry = () => {
    setFallbackFor(null);
    setError(null);
    setState("loading");
    setAttempt(value => value + 1);
  };
  return { videoRef, state, error, retry, progressError, tracks,
    selectTrack: (kind: "audio" | "subtitles", id: number) => selectTrackRef.current(kind, id) };
}
