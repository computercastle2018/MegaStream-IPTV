import { useEffect, useRef, useState } from "react";
import Hls from "hls.js";
import type { Playable } from "../types";
import { clearPlaybackProgress, savePlaybackProgress } from "../services/playbackProgress";

export type PlaybackState = "loading" | "playing" | "error";

/**
 * Attaches a stream to a <video> element.
 *
 * Strategy:
 *  - .m3u8 (HLS): prefer native HLS on webOS; desktop uses hls.js/MSE because
 *    its native HLS path can play HEVC streams as audio only.
 *  - Raw TS: use mpegts.js on desktop, the native pipeline on webOS.
 *  - Xtream live sources include a TS alternative, tried once after HLS fails.
 */
export function useHlsPlayer(channel: Playable | null, startAt = 0) {
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const [state, setState] = useState<PlaybackState>("loading");
  const [error, setError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);
  const [fallbackFor, setFallbackFor] = useState<Playable | null>(null);
  const [progressError, setProgressError] = useState(false);
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
    if (isHls && nativeHls && !window.megastreamDesktop) {
      video.src = url;
      video.play().catch(() => fail("Playback was blocked"));
    } else if (isHls && Hls.isSupported()) {
      hls = new Hls({ enableWorker: true, lowLatencyMode: false });
      hls.loadSource(url);
      hls.attachMedia(video);
      hls.on(Hls.Events.MANIFEST_PARSED, () => {
        video.play().catch(() => fail("Playback was blocked"));
      });
      hls.on(Hls.Events.ERROR, (_evt, data) => {
        if (!data.fatal || disposed) return;
        if (data.type === Hls.ErrorTypes.MEDIA_ERROR && !recoveredMedia) {
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

    const onVideoError = () => { if (!hls) fail("Unable to load this stream"); };
    video.addEventListener("error", onVideoError);

    return () => {
      save();
      disposed = true;
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
  return { videoRef, state, error, retry, progressError };
}
