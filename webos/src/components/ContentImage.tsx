import { useState } from "react";
import { Tv } from "lucide-react";

export default function ContentImage({ src, name, logo = false }: { src?: string; name: string; logo?: boolean }) {
  const [failedSrc, setFailedSrc] = useState<string | null>(null);
  if (!src || failedSrc === src) {
    return <span className={logo ? "logo logo-fallback" : "poster-fallback"} data-image-fallback aria-hidden="true">
      {logo ? <Tv size={26} /> : name.charAt(0).toUpperCase()}
    </span>;
  }
  return <img className={logo ? "logo" : undefined} src={src} alt="" loading="lazy" decoding="async"
    referrerPolicy="no-referrer" onError={() => setFailedSrc(src)} />;
}
