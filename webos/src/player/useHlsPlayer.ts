import { useEffect, useRef, useState } from "react";
import Hls from "hls.js";
import type { Playable } from "../types";

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
export function useHlsPlayer(channel: Playable | null) {
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const [state, setState] = useState<PlaybackState>("loading");
  const [error, setError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);
  const [fallbackFor, setFallbackFor] = useState<Playable | null>(null);
  const useFallback = fallbackFor === channel && Boolean(channel?.fallbackStreamUrl);

  useEffect(() => {
    const video = videoRef.current;
    if (!video || !channel) return;

    setState("loading");
    setError(null);

    let hls: Hls | null = null;
    let transport: ReturnType<typeof import("mpegts.js").default.createPlayer> | null = null;
    let disposed = false;
    let recoveredMedia = false;
    const url = useFallback ? channel.fallbackStreamUrl! : channel.streamUrl;
    const isHls = !useFallback && (channel.streamFormat === "hls" || url.split("?")[0].toLowerCase().endsWith(".m3u8"));
    const nativeHls = video.canPlayType("application/vnd.apple.mpegurl") !== "";

    const onPlaying = () => setState("playing");
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
      disposed = true;
      video.removeEventListener("playing", onPlaying);
      video.removeEventListener("error", onVideoError);
      if (hls) hls.destroy();
      if (transport) transport.destroy();
      video.removeAttribute("src");
      video.load();
    };
  }, [channel, useFallback, attempt]);

  const retry = () => {
    setFallbackFor(null);
    setError(null);
    setState("loading");
    setAttempt(value => value + 1);
  };
  return { videoRef, state, error, retry };
}
