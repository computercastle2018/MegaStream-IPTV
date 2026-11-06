# Remote providers

This server module manages provider profiles assigned to installation GUIDs. It does not modify locally configured client providers, device activation, device identity, or license limits. The Stalker MAC is a provider-side virtual configuration field, not a physical device identifier. The server stores and delivers configuration; it does not fetch provider URLs or synchronize provider content.

## Host integration

Import `MegaStream.Server.RemoteProviders` for the registration extension. The host must register `AddRemoteProviders(builder.Configuration)` on its service collection, call `MegaStream.Server.RemoteProviders.ModelConfiguration.Configure(modelBuilder)` from its EF model setup, and map MVC controllers. The module uses the existing `AppDbContext`, `DeviceAuthenticationHandler.SchemeName`, `Admin` authorization policy, antiforgery services, and DomainException-to-ProblemDetails middleware. The existing global response middleware must remain enabled: it applies `Cache-Control: no-store` even to authentication, authorization, request-validation, and exception responses. Controller response-cache annotations also disable storage for successful MVC responses.

Apply an EF migration containing the remote provider tables, indexes, foreign keys, and the `RemoteProviderRevisionSequence` seed row (`Id = 1`, `Revision = 0`) before serving these routes. Model configuration alone does not migrate an existing database. The sequence is a required database serialization point; a missing row produces a safe storage-unavailable failure, not an automatically initialized cursor. The report CLR type is `ProviderAssignmentReport`; its table remains `RemoteProviderReports`.

## Encryption configuration

`PROVIDER_SECRET_KEY` must be base64 encoding of exactly 32 random bytes. `PROVIDER_SECRET_KEY_VERSION` must be a positive integer string and defaults to `1`. Registration validates both in every environment; there is no development fallback key. Keep the key out of source control and logs, persist it securely, and back it up independently of the database.

The core uses AES-256-GCM with a fresh nonce. Profile ID, type, and revision are authenticated associated data. Configuration URLs, credentials, user agent, headers, and type-specific fields live inside the encrypted payload. Only one configured key/version is supported: changing either does not rotate existing ciphertext. Plan a controlled re-encryption migration before changing keys; unavailable or mismatched ciphertext fails with `provider_configuration_unavailable` (503).

For a Docker Compose service, pass the values into its runtime environment:

```yaml
environment:
  PROVIDER_SECRET_KEY: ${PROVIDER_SECRET_KEY:?required}
  PROVIDER_SECRET_KEY_VERSION: ${PROVIDER_SECRET_KEY_VERSION:-1}
```

Supply these values through external secret management and the deployment's runtime environment. Use the same persisted key and version across replicas and restarts. Never bake the key into an image or print it in logs. This snippet is a deployment handoff, not a change to Docker configuration files.

## HTTP routes

Devices authenticate with their existing bearer token; the `NameIdentifier` claim supplies the installation GUID.

- `GET /api/v1/providers/assignments?afterRevision=0` returns `serverRevision`, `assignments`, and `tombstones`. Each active assignment includes `assignmentId`, `profileId`, `assignmentRevision`, `profileRevision`, `policy`, lowercase `type`, `displayName`, and flat type-specific `configuration`. Save `serverRevision` only after processing the batch; report the applied `profileRevision`, not the assignment cursor. Revoked or administrator-disabled assignments are tombstones containing `assignmentId`, `assignmentRevision`, and `revokedAt`, without configuration. A cursor ahead of the server returns `revision_ahead` (409); request a full synchronization with zero.
- `POST /api/v1/providers/assignments/{assignmentId}/status` accepts `profileRevision`, `state`, optional `safeErrorCode`, optional `providerReportedExpiresAt`, and optional `providerReportedMaxConnections`. Successful reports return 204. Ownership, active installation, current profile revision, and assignment policy are checked by the core. Unknown JSON members are rejected.
- `POST /api/v1/providers/redeem` accepts only `{ "code": "..." }` in JSON, authenticates the device bearer, and requires a currently active installation and license. Codes are 24 hexadecimal characters (96 random bits); surrounding whitespace and letter case are normalized, with a 64-character input limit. A successful redemption returns 204 with no configuration or secrets. Fetch the subscription through the assignments endpoint, which now also requires an active license. Invalid, expired, revoked-profile, and other-device-used codes return the same `invalid_subscription_code` (400). Redemption attempts are limited to six per minute per authenticated device, or per IP before authentication, in addition to the global limit.

Unredeemed subscription codes expire after 24 hours. Consumption and an `auto_enabled` provider assignment commit in one serialized database transaction. The same licensed device may retry a consumed code without changing the assignment, even after the code's original expiry; retries never restore an administratively revoked assignment. Only a purpose-separated HMAC is persisted, using the existing persistent `DEVICE_SECRET_PEPPER`. This is separate from TV-displayed license activation codes and does not create a license.

Report states: `received`, `applied`, `disabled_by_user`, `syncing`, `active`, `expired`, `error`. Safe error codes: `invalid_configuration`, `authentication_failed`, `subscription_expired`, `network_unavailable`, `dns_failure`, `tls_failure`, `connection_timeout`, `http_401`, `http_403`, `http_404`, `http_429`, `server_error`, `parse_failed`, `sync_failed`, `unsupported`, `user_disabled`, `unknown`. Never send raw error messages or provider responses. Expiry must be UTC with a year from 1970 through 9998. Maximum connections ranges from 0 through 100000; zero is accepted as a provider claim. These fields are untrusted reported metadata, not licensing authority.

Configuration uses lowercase wire types `xtream_codes`, `m3u`, and `stalker_portal`. Xtream and M3U share `epgSyncMode` (`upfront`, `background`, `skip`). Xtream adds `username`, `password`, `fastSyncEnabled`, and `liveSyncMode` (`auto`, `category_by_category`, `stream_all`); M3U adds `m3uUrl` and `vodClassificationEnabled`. Stalker adds `portalUrl`, `stalkerMacAddress`, and optional `deviceProfile`, `timezone`, and `locale`. Type-specific properties are flat, not nested. Blank HTTP headers become JSON null.

Device request bodies are capped at 8192 bytes. Invalid requests use the host's safe ProblemDetails responses; stale/revoked assignments produce conflicts rather than accepting an outdated status.

## Admin pages

Start at `/admin/providers`. Create, details/full replacement, and revoke routes use the `Admin` policy. Per-installation assignment management is at `/admin/providers/installations/{installationId}`. Policies are `optional`, `auto_enabled`, and `required`; required assignments cannot be disabled. Every mutating form includes an antiforgery token and is protected by automatic antiforgery validation.

An active profile's details page at `/admin/providers/{profileId}` has a subscription-code generation form. Its CSRF-protected `POST /admin/providers/{profileId}/redemption-codes` displays a newly generated code only in that response, with its UTC expiry and 24-hour validity. A later GET cannot recover the plaintext. Do not put codes in URLs, logs, or TempData. Apply migration `20261030055417_AddProviderRedemptionCodes` before using this feature: with the intended database connection configured, run `dotnet ef database update --project server/MegaStream.Server` from the repository root, or use the deployed application's existing `dotnet MegaStream.Server.dll --migrate-only` entrypoint. These commands are deployment handoff instructions, not an authorization to deploy.

### Save reported local subscriptions

After `POST /api/v1/devices/subscriptions` stores a snapshot, it automatically imports new or changed connections only for an active, currently licensed device whose `LastSeenAt` and report time are within the admin's existing `ONLINE_WINDOW_MINUTES` window (default ten minutes). Reports from offline or unlicensed devices remain stored but are not promoted. The installation page `/admin/providers/installations/{installationId}` retains the CSRF-protected button `حفظ اشتراكات الجهاز في المكتبة`, posting to `/admin/providers/installations/{installationId}/import-local-subscriptions`. The button is disabled with an explanation when the device/license/report is ineligible; the server also rejects bypassed posts. Missing, stale, or malformed reports produce a clear message without importing. A fresh empty report imports nothing. Neither route sends a push command or claims a live device response.

Clients must report supported local types (`xtream_codes`, `m3u`, `stalker_portal`) and the optional typed `credentials` configuration, not just summaries. Entries without valid encrypted credentials are skipped and counted. The server reuses the local credential decoder and profile cipher, saving encrypted `RemoteProviderProfiles` without returning credentials, changing licenses, or creating assignments. Only safe counts and report time are included in the manual result page; no secret-bearing TempData is used. The index shows all saved profiles and all current reported subscription states from eligible connected/licensed devices, not just active provider subscriptions.

Apply migrations `20261030060810_AddLocalSubscriptionImportSource` and `20261030062227_AddLocalSubscriptionSourceState` before importing. Nullable source installation/local-ID provenance and its unique combined index prevent duplicate imports across clicks and reports. A purpose-separated source-content HMAC detects identical names, typed credentials and reported status/metadata without persisting plaintext credentials. Identical reports do not change profile ciphertext, revision or update time; the report timestamp still refreshes. Changed source data updates the same profile and advances existing assignment revisions. Administrator-revoked profiles are never restored. Administrator edits survive identical source reports, but a subsequently changed source snapshot refreshes its corresponding imported profile. Removed local entries do not delete saved library profiles.

Safe source names are retained exactly. Existing library names are compared case-insensitively; collisions receive the first available ` (2)`, ` (3)`, etc. Suffixing respects the 128-character metadata limit without splitting a surrogate pair. Imports serialize with other provider writes, including admin edits and other-device imports, and lock the installation report snapshot. Different devices and unrelated profiles are never merged by name. Apply migrations with the newly built server's existing `dotnet MegaStream.Server.dll --migrate-only` entrypoint, using the intended database configuration. Requesting a new report remotely would require a separate client-supported command; clients currently submit their own snapshots.

Views use explicit `~/RemoteProviders/Views/` paths and reuse `~/Views/Shared/_Layout.cshtml` for the Arabic right-to-left admin interface. They show metadata and masked configuration presence only; list/detail paths never decrypt. Display names are visible metadata: do not enter credentials or URLs there. Create/edit posts are complete replacements, not patches. Secret fields are blank on entry and on validation failure; blank secrets do not retain old values. The controller reads form fields explicitly without binding secret-bearing DTOs, clears ModelState, and does not place configuration in views, TempData, or logs. Validation failures show generic instructions to re-enter the complete configuration; nonvalidation domain errors retain their safe status/code.

Admin requests are capped at 32768 bytes and 40 form values, with 64-character keys and a 16384-character individual value limit. Field-specific limits and core validation also apply. File uploads and duplicate configuration fields are rejected. HTTP headers are entered one `Name: value` pair per line, at most 20 entries with names up to 64 and values up to 512 characters. Provider URLs remain encrypted even when they contain query-string credentials.

## Regression tests

Run from the repository root:

```sh
dotnet test server/MegaStream.Server.Tests/MegaStream.Server.Tests.csproj --filter FullyQualifiedName~RemoteProviders
```

The SQLite regression suite does not establish MySQL migration compatibility or Docker runtime behavior; verify those separately in the target deployment.
