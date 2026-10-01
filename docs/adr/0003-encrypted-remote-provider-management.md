# ADR 0003: Encrypted remote IPTV provider management

- Status: Accepted
- Date: 2026-09-30

## Context

Administrators need to configure Xtream Codes, M3U, and Stalker Portal subscriptions from the control panel while preserving the application's existing local provider setup. Provider configuration contains highly sensitive URLs, usernames, passwords, tokens embedded in playlists, HTTP headers, and Stalker account MAC values.

A physical-device MAC is not a reliable Android identity: access is restricted, Wi‑Fi addresses may be randomized, and televisions may use Ethernet. A Stalker Portal MAC is often a virtual account credential rather than the television's hardware address.

## Decision

Create a separate Remote Provider module. A Remote Provider Profile stores encrypted provider configuration and non-secret administration metadata. A Provider Assignment targets an app Installation ID and selects one of three policies: optional, auto-enabled, or required.

Locally created providers remain local. Remote synchronization may add, update, disable, or remove only records that carry the matching remote Profile and Assignment identities.

Provider payloads are encrypted with AES-256-GCM using a versioned key supplied outside the database and repository. Every write uses a fresh nonce and associated data containing the profile identity, provider type, and revision. Plaintext is decrypted only while processing an authorized administration mutation or returning an assigned profile to its authenticated Installation.

The dashboard never redisplays passwords, tokens, playlist URLs, custom headers, or Stalker MAC values. It shows only whether protected values are configured and permits replacement. Audit entries contain identities, action codes, and changed field names, never values.

Assignments target Installation IDs. A Stalker provider MAC is stored inside the encrypted profile and is never used as a device identifier.

Provider secrets are forbidden from diagnostic, update, heartbeat, audit, exception, proxy, and application logs. Assignment status reports use closed safe codes rather than provider responses.

## Consequences

- Deployment requires a persistent, backed-up `PROVIDER_SECRET_KEY` distinct from lease signing, cookie protection, and credential peppers.
- Losing the provider encryption key makes managed provider payloads unrecoverable; backup and key rotation procedures are mandatory.
- Database compromise alone does not reveal provider credentials, but compromise of both the database and encryption key does.
- Android still receives plaintext configuration in process memory after authenticated TLS delivery; a rooted or modified client cannot be made fully secret.
- Required assignments constrain remote-managed records only and do not erase local providers.
- Password and endpoint changes increment profile revision so devices can update deterministically and report the applied revision.

## Rejected alternatives

- **Binding to hardware MAC:** rejected because it is unreliable, privacy-sensitive, and often unavailable on Android.
- **Storing provider configuration as ordinary database columns:** rejected because backups, queries, and accidental logging would expose credentials.
- **Reusing telemetry redaction as secret protection:** rejected because redaction is not encryption and cannot reliably identify every embedded credential.
- **Letting remote management replace all local providers:** rejected because the user explicitly requires the current local mode to remain available.
