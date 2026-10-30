import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { init } from "@noriginmedia/norigin-spatial-navigation";
import App from "./App";
import ConnectionStatus from "./components/ConnectionStatus";
import DesktopControl from "./components/DesktopControl";
import { AppearanceProvider } from "./components/Appearance";
import { LanguageProvider } from "./i18n/LanguageContext";
import { startWebosControl } from "./services/webosControl";
import { NotificationsProvider } from "./components/Notifications";
import "./index.css";
import "./components/WebosTv.css";

declare const __APP_VERSION__: string;
startWebosControl(__APP_VERSION__);
document.documentElement.dataset.platform = window.megastreamWebos && !window.megastreamDesktop ? "webos" : "desktop";

// D-pad / arrow-key spatial navigation for the TV remote.
init({
  debug: false,
  visualDebug: false,
  // webOS sends standard arrow keydown codes; Enter = 13 is handled by the lib.
});

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <LanguageProvider>
      <AppearanceProvider><NotificationsProvider><DesktopControl><App /></DesktopControl></NotificationsProvider></AppearanceProvider>
      <ConnectionStatus />
    </LanguageProvider>
  </StrictMode>,
);
