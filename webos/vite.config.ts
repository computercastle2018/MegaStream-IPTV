import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const pkg = require("./package.json");

// base: "./" is REQUIRED. webOS loads the packaged app from a file:// origin,
// so all asset URLs in index.html must be relative, not absolute ("/assets/...").
// build.target: webOS TV browsers run older Chromium (webOS 6 ≈ Chromium 79).
// Targeting es2019 avoids syntax the on-device engine cannot parse.
export default defineConfig({
  plugins: [react()],
  define: { __APP_VERSION__: JSON.stringify(pkg.version) },
  base: "./",
  build: {
    target: "es2019",
    outDir: "dist",
    assetsInlineLimit: 0,
  },
});
