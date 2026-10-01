# Control-plane API

Use HTTPS. Registration, public verification keys, public update manifests, published APK downloads, and health probes are anonymous; other device operations require `Authorization: Bearer <installation-credential>`. Administrator cookies and signed offline leases are not device credentials. JSON uses camel-case field names; canonical request DTOs reject unknown members.

## Registration

`POST /api/v1/installations/register` is anonymous and requires exactly one `Idempotency-Key` header containing a nonempty UUID in hyphenated form.

The client generates and persists both `installationId` (a random nonempty UUID) and `credential` (32 cryptographically random bytes, canonical unpadded base64url, 43 characters) **before** calling registration. Reuse them when retrying after an uncertain network outcome. The response is `{ "installationId": "...", "registeredAt": "..." }`; it does not return a newly generated bearer token.

Required request fields:

| Field | Shape/constraint |
| --- | --- |
| `installationId` | Client-generated installation UUID, never a hardware ID. |
| `credential` | Client-generated secret described above; never log it. |
| `appVersionCode`, `appVersionName` | Positive integer code; nonempty version name up to 32 characters. |
| `packageName`, `channel` | App package and release channel; retain consistently for subsequent heartbeats. |
| `manufacturer`, `model` | Nonempty strings up to 64 characters each. |
| `androidApi`, `androidRelease` | API level at least 27; nonempty release string up to 32 characters. |
| `abi` | `arm64_v8a`, `armeabi_v7a`, `x86_64`, `x86`, or `other`. |
| `locale` | Nonempty string up to 16 characters. |
| `managedDevice` | Boolean; reporting this does not itself grant Android device-owner privileges. |

Same installation UUID plus matching credential returns the existing registration. A different credential for that UUID gives `409 installation_conflict`; reusing another installation's credential gives `409 credential_conflict`. Clearing app data/reinstalling creates a new installation lifetime.

## Activation and entitlement

| Method and path | Body | Result |
| --- | --- | --- |
| `POST /api/v1/licenses/activate` | `{"licenseKey":"<key supplied by administrator>"}` | Entitlement envelope. |
| `POST /api/v1/activation-codes/request` | `{}` | `code`, `expiresAt`, `status`, `pollToken`; retain code/token privately. |
| `POST /api/v1/activation-codes/status` | `{"code":"<issued code>","pollToken":"<issued token>"}` | Pending status plus server time/retry interval, or current entitlement after approval/consumption; expired/cancelled requests return `410`. |
| `POST /api/v1/devices/heartbeat` | Heartbeat below | Entitlement envelope, optionally an update command. |

Pending polling returns `status: pending`, `serverTime`, and `retryAfterSeconds: 5`. Expired/cancelled requests return `410 activation_expired` / `activation_cancelled`; request a new code rather than retrying the dead code. Polling uses a POST JSON body, **not** a code in a GET URL or an `X-Poll-Token` header. Approval consumes the activation assignment once; a subsequent consumed poll observes current entitlement rather than assigning a second seat.

An entitlement envelope contains `decision`, `serverTime`, `refreshAfterSeconds` (currently 60), and an optional signed `lease`. `decision` has `state`, nullable `licenseId` and `licenseRevision`, `startsAt`, `endsAt`, and, when allowed, `offlineUntil`. States are `allowed`, `unlicensed`, `verification_required`, `installation_disabled`, `revoked`, `suspended`, `expired`, or `not_started`. `unlicensed` means no license is assigned; `verification_required` covers an unresolved or unsupported license state.

HTTP 200 alone does not mean playback is allowed: inspect `decision.state`. An online denial supersedes any cached allow decision. Network failures cannot create or extend permission.

## Heartbeat

Required fields are `appSessionId` (nonempty UUID), nonnegative `sequence`, `mode` (`foreground`, `background`, `playback`), `appVersionCode`, `appVersionName`, `packageName`, `channel`, and `managedDevice`.

`memory` is optional. If supplied, it requires `javaUsedBytes`, `javaMaxBytes`, `nativeHeapBytes`, `pssBytes`, `availableSystemBytes`, and boolean `lowMemory`. Each byte count is between zero and 2^40 inclusive, and Java used bytes cannot exceed Java maximum bytes.

`recoveredExit` is optional and describes a previous process; it does not terminate the current session. It requires `reason` and `evidence`, with optional UTC `occurredAt`:

- Reasons: `clean_exit`, `os_exit`, `java_crash`, `anr`, `oom`, `low_memory_kill`, `native_crash`, `signal`, `user_requested`, `system_kill`, `unknown`.
- Evidence: `reported`, `recovered_os`, `recovered_marker`, `inferred`, `watchdog_suspected`.
- Supplied timestamps must be within 30 days before receipt through five minutes after receipt.

Example body with no secrets:

```json
{
  "appSessionId": "93c4c0f0-f89d-485f-a01b-b783f8d08e82",
  "sequence": 1,
  "mode": "foreground",
  "appVersionCode": 1,
  "appVersionName": "1.0.0",
  "packageName": "com.megastream.app",
  "channel": "stable",
  "managedDevice": false
}
```

Use the registered package/channel and actual client version rather than copying those example values. For a session, only a strictly increasing sequence changes presence/session data. A repeated or older sequence still receives the current entitlement decision. Server receipt time, not a client-supplied heartbeat time, controls presence.

## Requested kiosk policy

Heartbeat responses for active installations include `devicePolicy` with `kioskMode` (`off`, `playback`, or `always`) and boolean `allowLocalExit`. Defaults are `off` and `true`. Disabled installations receive the safe reset policy `{"kioskMode":"off","allowLocalExit":true}` rather than their stored requested policy. It is independent of the license decision: policy delivery is not an allow decision or proof that a client applied it.

Administrators edit the requested policy on device details using an authenticated, antiforgery-protected form (`POST /Admin/SetDevicePolicy`). Each accepted change records the administrator identifier, installation, UTC timestamp, and previous/new policy values; the device page displays the latest 100 audit entries. The view labels an unmanaged installation unsupported/not enforced. Managed status is the client's report, not server verification of device-owner privileges.

This server stores and delivers a requested policy only. It does not itself lock Android navigation, prevent exit, enroll a device owner, or prove kiosk enforcement. Actual application requires a compatible managed client and device capabilities; do not promise enforcement on unmanaged devices.

## Offline lease and verification keys

`GET /.well-known/offline-lease-keys` returns a JWKS object (`keys` array). `GET /api/v1/public/signing-key` is an alias returning that same wrapper, not a bare JWK. Establish key trust through the client's pinning/update process; merely retrieving a public key does not establish trust.

Leases are compact ES256 JWTs with P-256 signatures encoded as fixed-width `r || s` (not ASN.1 DER). Header fields are `alg: ES256`, `typ: JWT`, and a public-key-thumbprint `kid`. The payload contains exactly these 14 claims, with no legacy aliases or additional members:

- `iss`: `https://megastrem.megastation.uk`; `aud`: `megastream-android`.
- `sub`: installation UUID; `lid`: license UUID; `lrv`: license revision; `credentialBinding`.
- Integer Unix seconds: `iat`, `nbf`, `exp`, `licenseStartsAt`, `licenseEndsAt`.
- `jti`: unique UUID; `policyVersion: 1`; `decision: allowed`.

The issuer/audience are compiled protocol constants, not inferred from the reverse proxy hostname. Verifiers must validate signature, trusted key, issuer/audience, installation/credential binding, policy, and time bounds; do not merely decode the payload.

`OfflineGraceDays` must be 1–3 inclusive, default 3; zero and out-of-range values are rejected. A lease is issued only while the license is active and currently valid. Its offline expiry is issuance time plus the configured grace (equivalently the earlier of issuance+grace and license-end+grace for an issuance before license end). `exp` is the sole signed offline deadline; there is no additional grace field or extra period after it. Deny at the exact expiry instant.

An online check at/after license end denies immediately. A previously issued offline lease can extend beyond license end until its signed expiry. An unreachable device cannot learn a new revocation: revocation exposure lasts through outstanding lease expiry, up to three days after issuance. Removed/revoked installations retain capacity through their last outstanding lease reservation. A failed request, restart, or clock rollback must never reset the offline deadline.

## Typed diagnostics

`POST /api/v1/diagnostics/batch` requires a device bearer credential and a JSON object with exactly `schemaVersion: 1` and `events` (1–50 items). The request limit is 262,144 bytes. Each event requires `eventId`, `appSessionId` (nonempty hyphenated UUIDs), nonnegative `sequence`, UTC `occurredAt` (`Z` or `+00:00`), `kind`, and `payload`. Timestamps must fall within 30 days before receipt through five minutes afterward. A heartbeat need not precede diagnostics: the server can claim a previously unseen session and create its inferred session record. A session already claimed by another installation is rejected as `invalid_session`.

Payloads are closed schemas: unknown/duplicate properties, raw messages, raw stack strings, URLs, and credential-bearing text are rejected. Required fields for each kind are listed below; `?` marks optional fields.

| `kind` | Payload fields |
| --- | --- |
| `app_started` | `appVersionCode`, `appVersionName` |
| `playback_started` | `playbackSessionId`, `channelName?`, `sourceType`, `streamType`, `playbackMode` |
| `playback_sample` | `playbackSessionId`, `videoCodec`, `audioCodec`, `videoDecoder`, `audioDecoder`, `width`, `height`, `droppedFrames`, `rebufferCount`, `bufferedMs`, `ttffMs`, `memory?` |
| `playback_problem` | `playbackSessionId`, `category`, `code`, `httpStatus?`, `retryAttempt`, `memory?` |
| `memory_pressure` | `memory`, `trimLevel` |
| `crash` | `exceptionType`, `frames` |
| `anr` | `evidence`, `durationMs?`, `frames` |
| `playback_ended` | `playbackSessionId`, `reason`, `durationMs` |
| `app_ended` | `reason`, `evidence` |

`memory` uses the named byte counters and `lowMemory` fields described for heartbeat. Structured `frames` has at most 32 entries, each containing identifier-only `className`, `methodName`, and optional nonnegative `line`; it is not a free-form stack trace. `playback_problem` code/category combinations and other enum fields use explicit allowlists in `MegaStream.Server/V1/V1Diagnostics.cs`—do not send arbitrary exception strings as codes. For example, `decoder_init_failed` belongs to category `decoder`, and `dns_failure` belongs to `network`.

A valid envelope returns `acceptedEventIds`, `duplicateEventIds`, `rejected` (`eventId`, `code`), and `serverTime`. Valid new events can be accepted while invalid siblings appear in `rejected`; inspect that receipt instead of treating every HTTP 200 as full acceptance. Identical events are deduplicated per installation/event ID; reusing an ID with changed content is rejected as `duplicate_conflict`. Preserve IDs and content across transport retries. Invalid envelope/schema/count produces HTTP 400; disabled installations receive HTTP 403. Event rejection codes include `invalid_payload`, `invalid_session`, `invalid_sequence`, `invalid_time`, `invalid_kind`, `payload_too_large`, `unsafe_content`, and `duplicate_conflict`; correct the event rather than retrying it unchanged. An unsupported batch schema uses `unsupported_schema`.

## Application updates

| Method and path | Access | Contract |
| --- | --- | --- |
| `GET /api/v1/public/updates/latest?channel=stable&abi=arm64_v8a` | Anonymous | Published release manifest, or `204` when none matches. |
| `GET /updates/files/{releaseId}/release.apk` | Anonymous | Published immutable APK; range requests and SHA-256 ETag supported. Drafts are not downloadable. |
| `POST /api/v1/updates/check` | Device bearer | Body: `versionCode`, `packageName`, `channel`, `sdk`, optional `mode` (default `prompt`) and `abi` (default `other`). Returns `latest` and `pending`, either nullable. |
| `GET /api/v1/updates/pending` | Device bearer | Pending command, or no command. |
| `POST /api/v1/updates/commands/{commandId}/status` | Device bearer | Body: `status`, optional `errorCode`; accepted reports return `204`. |

Supported package/channel pairs are `com.megastream.app`/`stable` and `com.megastream.app.beta`/`beta`. Device ABIs are `arm64_v8a`, `armeabi_v7a`, `x86_64`, `x86`, `other`; release ABIs also support `universal` instead of `other`. The public latest query prioritizes an exact ABI match before a universal release, then version code. Clients still check package, SDK, version, file hash, and Android signing identity before installation.

Release manifests contain `releaseId`, `versionCode`, `versionName`, `packageName`, `minSdk`, `mandatory`, `releaseUrl`, `downloadUrl`, `sha256`, optional `signingCertificateSha256`, `sizeBytes`, `notes`, and `publishedAt`. APK URLs derive from the configured HTTPS public origin, never a client-supplied host header. Outside `Testing`, the origin must be exactly `https://megastrem.megastation.uk`; it is not an arbitrary deployment hostname. Only immutable published APK responses are publicly cacheable; device and manifest responses are not permission to cache credentials.

Commands progress `pending` → `ack` → `downloaded` → `installPrompted` → `installed`, or terminate at `failed`. Managed mode may advance directly from `downloaded` to `installed`. Repeated/older nonterminal progress is idempotent; invalid forward jumps give `409 invalid_transition`, and changing a terminal command gives `409 terminal_command`.

Administrators upload/publish/send from `/admin/updates`. Uploads are capped at 200 MiB by default; storage validates a ZIP with one bounded root `AndroidManifest.xml` and computes SHA-256. That check does **not** verify APK signatures or prove that manually supplied release metadata matches the manifest. `signingCertificateSha256`, when supplied, is pipeline-attested metadata, not server-verified signer identity. Validate release artifacts in a trusted build/release pipeline before publishing.

`prompt` is the normal user-confirmed installation mode. A `managed` command requires a target reported as managed, but the server cannot confer device-owner rights; the Android client must actually hold the necessary management privileges. `mandatory` is policy metadata, not a mechanism to bypass Android installation permissions.

## Encrypted provider provisioning

Provider configuration is intentionally separate from licensing and telemetry. Profiles can contain authorized provider connection credentials, encrypted at rest with AES-256-GCM under `PROVIDER_SECRET_KEY`; the key/version remain outside the database. An assigned installation receives decrypted configuration over authenticated HTTPS. This is not a public API, and responses must never be cached or logged.

| Method and path | Contract |
| --- | --- |
| `GET /api/v1/providers/assignments?afterRevision=0` | Device bearer required. Returns `serverRevision`, `assignments`, and `tombstones` changed after the requested revision. Use zero for a full synchronization. |
| `POST /api/v1/providers/assignments/{assignmentId}/status` | Device bearer required. Report current profile revision/status; returns `204`. |

An assignment has `assignmentId`, `profileId`, `assignmentRevision`, `profileRevision`, `policy`, `displayName`, `type`, and `configuration`. Separate tombstones contain `assignmentId`, `assignmentRevision`, and nullable `revokedAt`, with no usable configuration. Apply tombstones locally rather than treating an empty delta as deletion. Save `serverRevision` only after processing the complete response. `409 revision_ahead` requires a full synchronization from zero.

Device wire types are lowercase `xtream_codes`, `m3u`, and `stalker_portal` (administrative/domain profile types are uppercase). The device `configuration` is flat, not nested under a type-specific object. Common fields are `serverUrl`, `epgUrl`, `httpUserAgent`, and `httpHeaders`. Xtream fields include `username`, `password`, `epgSyncMode`, `fastSyncEnabled`, and `liveSyncMode`; M3U fields include `m3uUrl`, `epgSyncMode`, and `vodClassificationEnabled`; Stalker fields include `portalUrl`, `stalkerMacAddress`, `deviceProfile`, `timezone`, and `locale`. Inapplicable type-specific fields are omitted. These values may be credentials: do not paste full assignment responses into troubleshooting reports.

Status bodies contain required positive `profileRevision` and `state`, with optional `safeErrorCode`, UTC `providerReportedExpiresAt`, and `providerReportedMaxConnections`. States: `received`, `applied`, `disabled_by_user`, `syncing`, `active`, `expired`, `error`. Safe error codes are restricted to `invalid_configuration`, `authentication_failed`, `subscription_expired`, `network_unavailable`, `dns_failure`, `tls_failure`, `connection_timeout`, `http_401`, `http_403`, `http_404`, `http_429`, `server_error`, `parse_failed`, `sync_failed`, `unsupported`, `user_disabled`, `unknown` (or null), never raw provider error text.

Policies are `optional`, `auto_enabled`, and `required`; a required assignment cannot be reported as `disabled_by_user`. Stale/revoked assignment reports receive `409 stale_provider_revision`; another installation's assignment is not accessible. Provider-reported expiration and connection limits are **not** MegaStream application-license validity or installation capacity.

Administrators replace complete configurations; they cannot retrieve the stored plaintext through profile-metadata views. A lost/wrong encryption key or version returns `503 provider_configuration_unavailable`; do not "fix" that by discarding encrypted database records or generating a replacement key without a recovery plan.

## Errors and health

Domain errors use `application/problem+json` with HTTP `status`, a safe `title`, `code`, and `traceId`. Model-binding failures use `400 invalid_request`. Do not log full request bodies while investigating failures.

- `400`: fix invalid schema/fields; unchanged retries will not help.
- `401`: device authentication failed; an offline lease or admin cookie is not a replacement credential.
- `403`: operation denied, including disabled-device telemetry/update/provisioning operations.
- `409`: state/capacity/credential conflict; reconcile state rather than repeatedly submitting secrets.
- `429`: back off with jitter. The limiter allows 120 requests/minute per partition with no queue. Protected device operations use the authenticated installation UUID; protected non-API administrator routes use the authenticated administrator ID. Anonymous endpoints (including registration and login) always use the observed IP, even if a bearer token is attached; unauthenticated/other requests also fall back to IP. Thus authenticated devices behind one NAT do not share a device bucket, while anonymous requests from that NAT do share an IP bucket. Raw bearer-token strings are never partition keys. Health probes are exempt.
- `500`/transport failure: retry conservatively. Preserve only an already verified, unexpired offline lease; never restart its deadline.

`GET /health/live` reports process liveness. `GET /health/ready` runs a database query and returns `200` on success or `503` on failure; it is not proof that every migration was applied. Health routes bypass production HTTPS redirection and request-rate limiting for container probes. Public device calls still require HTTPS.

## Privacy boundaries

Never send provider URLs, IPTV playlists, usernames/passwords, authorization headers, cookies, or secrets in telemetry. Sanitize on the client **before** persistence and upload. Do not upload full network requests, raw exception messages, stack traces, or provider response bodies.

This telemetry rule is separate from the authorized encrypted provider-provisioning module. Credentials deliberately provisioned through that module must not be copied into diagnostics, session metadata, or logs. Never ship the private P-256 signing key in an APK.
