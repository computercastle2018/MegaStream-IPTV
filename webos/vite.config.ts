import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// base: "./" is REQUIRED. webOS loads the packaged app from a file:// origin,
// so all asset URLs in index.html must be relative, not absolute ("/assets/...").
// build.target: webOS TV browsers run older Chromium (webOS 6 ≈ Chromium 79).
// Targeting es2019 avoids syntax the on-device engine cannot parse.
export default defineConfig({
  plugins: [react()],
  base: "./",
  build: {
    target: "es2019",
    outDir: "dist",
    assetsInlineLimit: 0,
  },
});
