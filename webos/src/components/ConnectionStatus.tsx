import { useEffect, useState } from "react";
import { useLang } from "../i18n/LanguageContext";

/**
 * A fixed indicator at the bottom of the screen showing network connectivity.
 * Tracks navigator.onLine plus the browser/webOS online/offline events.
 */
export default function ConnectionStatus() {
  const { t } = useLang();
  const [online, setOnline] = useState<boolean>(() => navigator.onLine);

  useEffect(() => {
    const handleOnline = () => setOnline(true);
    const handleOffline = () => setOnline(false);
    window.addEventListener("online", handleOnline);
    window.addEventListener("offline", handleOffline);
    return () => {
      window.removeEventListener("online", handleOnline);
      window.removeEventListener("offline", handleOffline);
    };
  }, []);

  return (
    <div className={`conn-status ${online ? "online" : "offline"}`} role="status" aria-live="polite">
      <span className="conn-dot" />
      <span>{online ? t("status.online") : t("status.offline")}</span>
    </div>
  );
}
