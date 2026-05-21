# StreamVault — webOS (Live TV MVP)

A **standalone** webOS TV app for LG TVs. This is a separate codebase from the
Android app (`../app`, `../data`, `../domain`, `../player`) — webOS runs web
apps (HTML/JS), not Android/Kotlin, so no code is shared. The protocol/parsing
logic here is a faithful TypeScript port of the Kotlin `M3uParser` and
`XtreamUrlFactory`.

## Scope

MVP = **Live TV only**:

- Add providers: **M3U URL** and **Xtream Codes** (Xtream credentials are verified on add)
- Browse channels grouped by category, navigable with the D-pad remote
- HLS playback via the platform pipeline (with `hls.js` fallback)

Not included yet: Movies, Series, EPG/guide, DVR, parental controls, favorites.

## Stack

- **Vite + React + TypeScript** — builds to a static bundle webOS can package
- **@noriginmedia/norigin-spatial-navigation** — D-pad / arrow-key focus management
- **hls.js** — HLS playback fallback where MSE is available

## Develop

```bash
npm install
npm run dev      # http://localhost:5173 — test the flow in a desktop browser
npm run build    # type-check + production build into dist/
```

> **CORS in the browser:** during `npm run dev`, fetching playlists / Xtream
> APIs from a different origin is blocked by the browser's CORS policy. On a real
> webOS TV the app runs from a `file://` origin with `allowCrossDomain` set in
> `appinfo.json`, so cross-origin requests succeed. To test provider loading on
> desktop, use a browser with web security disabled or a CORS proxy.

## Package & deploy to a TV

Requires the **webOS TV SDK** (`ares-cli`), not bundled here:

```bash
npm install -g @webos-tools/cli   # provides ares-package / ares-install / ares-launch
```

Then:

```bash
npm run build                     # produces dist/ (already contains appinfo.json + icon.png)
npm run package                   # ares-package dist -o out  ->  out/com.streamvault.webos_0.1.0_all.ipk
ares-setup-device                 # one-time: register your TV (Developer Mode app must be running)
npm run install-tv                # ares-install the .ipk onto the TV named "tv"
npm run launch-tv                 # ares-launch the app
```

## Layout

```
public/appinfo.json      webOS app manifest (copied to dist/ root)
public/icon.png          placeholder 80x80 icon — replace before release
src/services/m3uParser.ts    TS port of Kotlin M3uParser
src/services/xtreamClient.ts Xtream Codes live API (player_api.php)
src/services/channels.ts     unifies M3U + Xtream into a Channel list
src/services/storage.ts      provider persistence (localStorage)
src/player/useHlsPlayer.ts   <video> + hls.js attachment
src/screens/                 ProviderSetup, ChannelList, Player
src/remote/keys.ts           webOS remote key codes + Back handling
```

## Known limitations / next steps

- **Raw MPEG-TS** (`.ts`) live streams rely on the on-device webOS pipeline;
  they will not play in a desktop browser (hls.js cannot demux raw TS over MSE).
  Xtream streams request the `.m3u8` HLS variant to maximize browser/TV support.
- Replace `public/icon.png` with proper branded icons (80×80 + large 130×130).
- The icon color / splash are placeholders.
- No on-screen keyboard styling tuning yet — relies on the webOS system keyboard.
