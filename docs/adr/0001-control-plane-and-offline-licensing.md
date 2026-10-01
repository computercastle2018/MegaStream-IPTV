# ADR 0001: Central control plane with signed offline licensing

- Status: Accepted
- Date: 2026-09-30

## Context

MegaStream must report device presence and sanitized operational failures, allow administrators to manage time-bounded application licenses, and continue to operate temporarily when a television loses network access. The Android app already supports provider credentials and provider-reported expiration/connection limits; those concepts must not become application licensing concepts.

Android TV devices can be offline or have unreliable clocks. A shared secret embedded in the APK cannot safely prove an offline grant because it can be extracted and used to mint grants. Immediate revocation is also impossible while a device is offline.

The target deployment is a Plesk-hosted subdomain backed by MySQL. The initial operational scale does not justify distributed services or a message broker.

## Decision

Build one ASP.NET Core modular monolith with three deep modules:

1. **Entitlements** — enrollment, installation capacity, license state, activation codes, and signed offline leases.
2. **Diagnostics** — bounded typed event ingestion, sanitization, retention, and playback-problem aggregation.
3. **Operations** — read-only dashboard projections and administration commands with audit entries.

Use MySQL/InnoDB for transactional state. The administration interface uses server-rendered MVC/Razor pages with authenticated administrator cookies. Android device endpoints use independent per-installation credentials and never share the IPTV networking client.

Offline leases are signed asymmetrically with ECDSA P-256. The server holds the private key; Android embeds or trust-pins public verification keys. A lease is bound to an Installation and License, includes a fixed deadline, and is never accepted as an HTTP authentication token.

A successful online denial overrides a cached allow immediately. Network failures may preserve an existing lease but never extend it. Offline grace is limited to three days from the last successful validation and cannot restart from launch, reboot, failed telemetry, or clock rollback.

Telemetry uses a closed schema and is sanitized before local persistence and again on the server. Provider URLs, playlists, usernames, passwords, tokens, and license secrets are forbidden fields.

## Consequences

- License allocation and activation-code consumption require transactions and concurrency-safe capacity checks.
- Removing an offline Installation cannot safely recycle its seat before its last offline lease deadline.
- Revocation is immediate online but may have up to the configured offline grace exposure.
- The app must gate every owned playback entry point, including the full player, previews, split screen, TV input, recording, Cast/external launches, and already-running sessions.
- Production diagnostics must distinguish Java crashes, ANRs, low-memory kills, native exits, graceful ends, and unknown inferred ends.
- Deployment must persist Identity data-protection keys, MySQL data, the device-secret pepper, and lease-signing key outside the application container.
- A modular monolith keeps deployment simple for Plesk while preserving internal seams for later extraction if scale demonstrates a real need.

## Rejected alternatives

- **Shared HMAC offline license:** rejected because the signing secret would be recoverable from the APK.
- **License check only in UI navigation:** rejected because TV Input, preview, split-screen, recording, and other playback paths can bypass it.
- **Generic raw crash-log upload:** rejected because it risks provider credential and URL disclosure and is difficult to aggregate safely.
- **Microservices and message broker:** rejected as operational complexity without current scale evidence.
- **Freeing a seat immediately when an offline device is removed:** rejected because the removed device may still hold a valid offline lease, allowing the installation limit to be exceeded.
