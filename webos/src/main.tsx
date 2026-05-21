import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { init } from "@noriginmedia/norigin-spatial-navigation";
import App from "./App";
import ConnectionStatus from "./components/ConnectionStatus";
import { LanguageProvider } from "./i18n/LanguageContext";
import "./index.css";

// D-pad / arrow-key spatial navigation for the TV remote.
init({
  debug: false,
  visualDebug: false,
  // webOS sends standard arrow keydown codes; Enter = 13 is handled by the lib.
});

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <LanguageProvider>
      <App />
      <ConnectionStatus />
    </LanguageProvider>
  </StrictMode>,
);
