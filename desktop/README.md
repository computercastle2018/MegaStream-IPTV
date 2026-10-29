# MegaStream Desktop

Native desktop packages reuse the webOS client: M3U and Xtream accounts, live
channels, movies, series, Arabic/English, keyboard and mouse navigation.
Desktop adds native video controls and MPEG-TS playback. Account credentials
are encrypted using the operating system credential service, separate from TV data.

Desktop 3.0.24 registers macOS/Windows devices with the existing control plane.
Administrators can assign licenses and M3U/Xtream subscriptions to each device.
Registration identity and signed offline leases survive application upgrades;
the app synchronizes every 30 seconds and can refresh manually. Managed appearance
is checked every five seconds and on window focus without reloading the catalog. Revocation
removes cached authorization. Playback requires a verified, valid license.
The license screen shows device ID, validity dates, activation key/code controls,
and server synchronization. Subscription editing follows the admin visibility policy.
Remote subscriptions are administrator-owned; unsupported Stalker/custom-header
assignments are reported as unsupported rather than silently treated as active.
Notifications, other remote commands and automatic desktop updates are not implemented.
Codec support depends on Electron and the media source; not every MKV/audio
codec is supported. Packaging is unsigned and not notarized.

## Build

Install dependencies with `npm ci` in both `webos` and `desktop`, then run
`npm run build`, `npm test`, and `npm start` in `desktop`.
`npm run package` produces Mac Apple Silicon/Intel and Windows x64 ZIPs.
Windows users extract the whole archive and run `MegaStream.exe`; Mac users
extract and move `MegaStream.app` to Applications. This does not replace an
existing Android/webOS installation or migrate its account data.

Builds are not signed with a trusted publisher certificate. Sign/notarize on
the appropriate platform before public distribution. Windows runtime testing
must be performed on Windows; a cross-build is not a runtime verification.

## Runtime Smoke Test

With Playwright available, set `PLAYWRIGHT_MODULE` to its installed module,
`TEST_TS_FILE` to a locally generated H.264/AAC MPEG-TS test video and run
`node smoke.cjs`. The test uses an isolated temporary profile, a signed local
control-plane fixture and a local HTTP playlist, never a real subscription.
`smoke-main.cjs` is a test-only entrypoint excluded from distribution packages.
Server integration tests cover real authenticated endpoints and SQLite transactions.

Dependency audit currently reports issues in packaging/development tools
(`electron-builder` download dependencies and Vite/esbuild dev server).
They are not shipped as runtime dependencies; use the packaged app rather
than exposing a development server to untrusted networks.
