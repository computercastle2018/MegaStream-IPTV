# MegaStream Desktop 3.0.24

- Apply the control-panel design automatically without restarting the application.
- Check managed appearance every five seconds and when the window regains focus, independently of subscription downloads.
- Preserve device identity, licenses, subscriptions, favorites and local appearance preferences during normal upgrades.
- Includes Classic, Modern and Studio layouts, category filters, catalog refresh and playback retry controls.

## Downloads

- `mac-arm64`: Apple Silicon (M1/M2/M3/M4 or later).
- `mac-x64`: Intel Macs.
- Replace the existing application without deleting its application data.

The control-panel update is deployed with application and database backups. Existing duplicate device records are not merged or deleted.

Validation: 498 server tests; 9 desktop/admin checks; packaged Apple Silicon UI checks including automatic managed-design changes; ZIP integrity and source checks for both Mac architectures.

The packages are not signed with an Apple Developer ID and are not notarized. Intel packaging was verified, but Intel hardware was not available for a runtime test. This desktop-only release does not replace the latest Android/webOS release or its download links.
