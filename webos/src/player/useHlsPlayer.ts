import { useEffect, useRef, useState } from "react";
import Hls from "hls.js";
import type { Playable } from "../types";

export type PlaybackState = "loading" | "playing" | "error";

/**
 * Attaches a stream to a <video> element.
 *
 * Strategy:
 *  - .m3u8 (HLS): prefer the platform's native HLS pipeline when available
 *    (webOS TVs play HLS natively and more efficiently than MSE). Fall back to
 *    hls.js where MSE is available.
 *  - .ts / auto: assign directly to video.src and let the webOS media pipeline
 *    handle MPEG-TS. hls.js cannot demux raw TS over MSE, so there is no fallback
 *    on a desktop browser — this path is expected to work only on-device.
 */
export function useHlsPlayer(channel: Playable | null) {
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const [state, setState] = useState<PlaybackState>("loading");
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const video = videoRef.current;
    if (!video || !channel) return;

    setState("loading");
    setError(null);

    let hls: Hls | null = null;
    const url = channel.streamUrl;
    const isHls = channel.streamFormat === "hls" || url.split("?")[0].toLowerCase().endsWith(".m3u8");
    const nativeHls = video.canPlayType("application/vnd.apple.mpegurl") !== "";

    const onPlaying = () => setState("playing");
    const fail = (msg: string) => {
      setError(msg);
      setState("error");
    };

    video.addEventListener("playing", onPlaying);

    if (isHls && nativeHls) {
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
        if (data.fatal) fail(`Stream error: ${data.details}`);
      });
    } else {
      // Raw TS or unknown — rely on the platform pipeline (works on webOS, not desktop).
      video.src = url;
      video.play().catch(() => fail("Playback was blocked"));
    }

    const onVideoError = () => fail("Unable to load this stream");
    video.addEventListener("error", onVideoError);

    return () => {
      video.removeEventListener("playing", onPlaying);
      video.removeEventListener("error", onVideoError);
      if (hls) hls.destroy();
      video.removeAttribute("src");
      video.load();
    };
  }, [channel]);

  return { videoRef, state, error };
}
