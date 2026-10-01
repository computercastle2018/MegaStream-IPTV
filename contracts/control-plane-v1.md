# MegaStream Control Plane Contract v1

Status: frozen for initial implementation  
Origin: `https://megastrem.megastation.uk`  
Media type: `application/json` unless otherwise stated  
Dates: RFC 3339 UTC with `Z`  
JSON names: camel case  
All responses: `Cache-Control: no-store`, except immutable APK bytes

Unknown enum values and unknown object properties are rejected. Device-authenticated endpoints derive the Installation from the bearer credential and never accept a subject Installation ID in the payload.

## Error envelope

`application/problem+json`:

```json
{"title":"Invalid request","status":400,"code":"invalid_request","traceId":"..."}
```

Secrets, submitted payload text, stack traces, provider data, and URLs are never echoed.

## Device credential

A client creates 32 cryptographically random bytes and encodes them as unpadded base64url (43 characters). Authenticated requests use:

```http
Authorization: Bearer <credential>
```

The credential is valid only at the exact control-plane origin. Redirects are not followed. The server stores a purpose-separated keyed hash for authentication and a separate SHA-256 credential binding for offline leases; it never stores the credential.

## Registration

`POST /api/v1/installations/register`

Required header: `Idempotency-Key: <UUID>`

```json
{
  "installationId":"UUID",
  "credential":"43-character base64url",
  "appVersionCode":31,
  "appVersionName":"2.1.6",
  "packageName":"com.megastream.app",
  "channel":"stable",
  "manufacturer":"Example",
  "model":"Android TV",
  "androidApi":35,
  "androidRelease":"15",
  "abi":"arm64_v8a",
  "locale":"ar-SA",
  "managedDevice":false
}
```

`abi`: `arm64_v8a | armeabi_v7a | x86_64 | x86 | other`.

Response:

```json
{"installationId":"UUID","registeredAt":"2026-09-30T00:00:00Z"}
```

The Installation ID is app-generated and identifies one app-data lifetime. It is not Android ID, serial, MAC, IMEI, or a hardware fingerprint. Repeating the same ID, credential, idempotency key, and body is idempotent. Reusing the ID with another credential returns `409`.

## Entitlement decision

```json
{
  "state":"allowed",
  "licenseId":"UUID",
  "licenseRevision":1,
  "startsAt":"2026-09-01T00:00:00Z",
  "endsAt":"2027-09-01T00:00:00Z",
  "offlineUntil":"2026-10-03T00:00:00Z"
}
```

`state`: `allowed | unlicensed | not_started | expired | suspended | revoked | installation_disabled | verification_required`.

`licenseId`, `licenseRevision`, `startsAt`, `endsAt`, and `offlineUntil` are nullable. Known-License states require the first four fields; `allowed` also requires `offlineUntil` and a valid lease. `unlicensed`, `installation_disabled`, and `verification_required` may omit every License field.

Entitlement responses use:

```json
{
  "decision": {"state":"allowed","licenseRevision":1,"startsAt":"...","endsAt":"...","offlineUntil":"..."},
  "serverTime":"2026-09-30T00:00:00Z",
  "refreshAfterSeconds":3600,
  "lease":"compact ES256 JWS or null",
  "updateCommand":null,
  "devicePolicy":null
}
```

Only `allowed` may contain `lease` and `offlineUntil`. `devicePolicy`, when present, is `{"kioskMode":"off|playback|always","allowLocalExit":true|false}`. Activation and polling responses may omit it; every successful heartbeat response includes it. An `installation_disabled` heartbeat always returns the safe effective policy `off` with local exit allowed so a disabled installation cannot remain trapped in kiosk mode. A policy is a request, not proof of Android enforcement.

An authenticated online denial immediately overrides a cached allow. A timeout, `5xx`, `429`, malformed response, or failed telemetry upload never extends or replaces a lease.

## Direct activation

`POST /api/v1/licenses/activate`

```json
{"licenseKey":"show-once enrollment key"}
```

Returns an entitlement response.

## Activation code

`POST /api/v1/activation-codes/request`

Request: `{}`

Response:

```json
{"code":"ABCDEFGH12","expiresAt":"...","status":"pending","pollToken":"secret"}
```

The code is short-lived and shown once. The server stores keyed hashes of the code and polling token.

`POST /api/v1/activation-codes/status`

```json
{"code":"ABCDEFGH12","pollToken":"secret"}
```

Returns `pending` information or the current entitlement response after approval. Consumption and seat allocation are atomic. A replay never creates another seat.

## Heartbeat/check-in

`POST /api/v1/devices/heartbeat`

```json
{
  "appSessionId":"UUID",
  "sequence":1,
  "mode":"foreground",
  "appVersionCode":31,
  "appVersionName":"2.1.6",
  "packageName":"com.megastream.app",
  "channel":"stable",
  "managedDevice":false,
  "memory":null,
  "recoveredExit":null
}
```

`mode`: `foreground | background | playback`.

Memory:

```json
{"javaUsedBytes":0,"javaMaxBytes":0,"nativeHeapBytes":0,"pssBytes":0,"availableSystemBytes":0,"lowMemory":false}
```

Recovered exit:

```json
{"reason":"low_memory_kill","evidence":"recovered_os","occurredAt":"..."}
```

Exit reason: `clean_exit | os_exit | java_crash | anr | oom | low_memory_kill | native_crash | signal | user_requested | system_kill | unknown`.

Evidence: `reported | recovered_os | recovered_marker | inferred | watchdog_suspected`.

The response is an entitlement response and may contain an Update Command. A fresh session sequence updates Presence; diagnostic retries do not.

## Offline lease

Header: `alg=ES256`, `kid` required. Other algorithms are rejected.

Claims:

- `iss`: `https://megastrem.megastation.uk`
- `aud`: `megastream-android`
- `sub`: Installation UUID
- `lid`: License UUID
- `lrv`: License revision, positive integer
- `credentialBinding`: base64url SHA-256 of the raw device credential
- `iat`, `nbf`, `exp`: NumericDate seconds
- `licenseStartsAt`, `licenseEndsAt`: NumericDate seconds
- `jti`: UUID
- `policyVersion`: `1`
- `decision`: `allowed`

`exp = min(iat + configuredOfflineGrace, licenseEndsAt + configuredOfflineGrace)`. Initial policy allows 1–3 days and defaults to 3. Exact `now == exp` is denied. An existing lease may operate past the License end while offline until `exp`; a successful online check at or after the License end returns `expired` immediately and never issues a new lease.

## Diagnostic batch

`POST /api/v1/diagnostics/batch`

```json
{"schemaVersion":1,"events":[{"eventId":"UUID","appSessionId":"UUID","sequence":1,"occurredAt":"...","kind":"app_started","payload":{}}]}
```

One to 50 events. Common fields are required. Event IDs are idempotent per Installation. A valid authenticated event may arrive before its heartbeat; the server creates an inferred session scoped to that Installation and later refines it idempotently.

Response:

```json
{"acceptedEventIds":["UUID"],"duplicateEventIds":[],"rejected":[],"serverTime":"..."}
```

Rejected items contain `eventId` and `code`: `invalid_kind | invalid_payload | invalid_time | invalid_sequence | invalid_session | duplicate_conflict | unsafe_content | payload_too_large | unsupported_schema`. No rejected content is logged; clients map an unknown future code to a local `unknown` value.

### Event kinds and payloads

- `app_started`: `appVersionCode`, `appVersionName`.
- `playback_started`: `playbackSessionId`, optional `channelName` (max 120), `sourceType`, `streamType`, `playbackMode`.
- `playback_sample`: `playbackSessionId`, `videoCodec`, `audioCodec`, `videoDecoder`, `audioDecoder`, `width`, `height`, `droppedFrames`, `rebufferCount`, `bufferedMs`, `ttffMs`, optional `memory`.
- `playback_problem`: `playbackSessionId`, `category`, lower-case bounded identifier `code`, optional `httpStatus`, `retryAttempt`, optional `memory`.
- `memory_pressure`: `memory`, `trimLevel`.
- `crash`: restricted `exceptionType`, up to 32 restricted frames `{className,methodName,line?}`. Throwable messages are forbidden.
- `anr`: `evidence`, optional `durationMs`, up to 32 restricted frames.
- `playback_ended`: `playbackSessionId`, `reason`, `durationMs`.
- `app_ended`: `reason`, `evidence`.

Source type: `xtream_codes | m3u | stalker_portal | local | unknown`.

Stream type: `hls | dash | mpeg_ts | progressive | rtsp | smooth_streaming | unknown`.

Playback mode: `live | vod | catch_up | preview | multiview | tv_input | recording`.

Video codec: `unknown | h264 | hevc | av1 | vp9 | mpeg2 | other`.

Audio codec: `unknown | aac | ac3 | eac3 | dts | mp3 | opus | other`.

Video decoder: `hardware | software | unknown`. Audio decoder: `platform | ffmpeg | unknown`.

Problem category: `network | http | decoder | drm | source | timeout | stall | unknown`.

Trim level: `running_moderate | running_low | running_critical | background | moderate | complete | low_memory | unknown`.

Playback end: `user_stop | channel_changed | completed | error | license_blocked | background | sleep_timer | unknown`.

ANR evidence: `os_exit_reason | watchdog_suspected`.

Provider URLs, playlist content, usernames, passwords, tokens, cookies, authorization headers, raw logs, and arbitrary stack traces are forbidden.

## Update discovery

`GET /api/v1/public/updates/latest?channel=stable&abi=arm64_v8a`

`channel`: `stable | beta`. `abi` accepts the device ABI values above. The server selects an exact ABI first, then `universal`, and never returns a foreign ABI. Returns `204` when no published release exists.

Response:

```json
{
  "releaseId":"string",
  "versionCode":32,
  "versionName":"2.1.7",
  "packageName":"com.megastream.app",
  "minSdk":27,
  "mandatory":false,
  "releaseUrl":"https://megastrem.megastation.uk/...",
  "downloadUrl":"https://megastrem.megastation.uk/...",
  "sha256":"64 hex characters",
  "signingCertificateSha256":"64 hex characters or null",
  "sizeBytes":123,
  "notes":"...",
  "publishedAt":"..."
}
```

URLs must use the exact control-plane HTTPS origin, port 443/default, with no userinfo or fragment. APK downloads are public immutable bytes and never receive the device bearer credential.

## Remote update command

```json
{
  "commandId":"UUID",
  "releaseId":"string",
  "versionCode":32,
  "versionName":"2.1.7",
  "mandatory":false,
  "installMode":"prompt",
  "downloadUrl":"https://megastrem.megastation.uk/...",
  "sha256":"64 hex characters",
  "signingCertificateSha256":null,
  "sizeBytes":123,
  "notes":"..."
}
```

`installMode`: `prompt | managed`. Managed installation is attempted only when Android confirms MegaStream is Device Owner; otherwise the app falls back to the prompt flow.

`POST /api/v1/updates/commands/{commandId}/status`

```json
{"status":"downloading","errorCode":null,"observedVersionCode":31}
```

Status: `pending | acknowledged | downloading | downloaded | install_prompted | installed | failed`. Delivery is not installation success; only observing the target installed version produces `installed`.

## Managed IPTV provider assignments

Managed provider data is separate from License and Diagnostics. An assignment targets an Installation ID, never a hardware MAC. A Stalker MAC is an encrypted provider credential only.

`GET /api/v1/providers/assignments?afterRevision=<non-negative integer>`

Response:

```json
{
  "serverRevision":12,
  "assignments":[
    {
      "assignmentId":"UUID",
      "profileId":"UUID",
      "assignmentRevision":7,
      "profileRevision":3,
      "policy":"optional",
      "type":"xtream_codes",
      "displayName":"My subscription",
      "configuration":{}
    }
  ],
  "tombstones":[{"assignmentId":"UUID","assignmentRevision":8,"revokedAt":"..."}]
}
```

`policy`: `optional | auto_enabled | required`. Optional assignments remain disabled until the user opts in; auto-enabled assignments can be disabled by the user; required assignments are controlled by the administrator. Locally created providers remain local and cannot be changed or deleted by this API.

Common configuration fields: `serverUrl`, optional `epgUrl`, `httpUserAgent`, and `httpHeaders`. `httpHeaders` is a nullable JSON object with at most 20 string entries; names must be valid HTTP tokens (max 64) and values are capped at 512 characters with CR/LF/NUL rejected. All values are treated as secrets.

Xtream Codes configuration adds `username`, `password`, `epgSyncMode` (`upfront | background | skip`), `fastSyncEnabled`, and `liveSyncMode` (`auto | category_by_category | stream_all`).

M3U configuration adds `m3uUrl`, `epgSyncMode`, and `vodClassificationEnabled`.

Stalker configuration adds `portalUrl`, `stalkerMacAddress`, optional `deviceProfile`, `timezone`, and `locale`. The MAC belongs to the portal account and is never read from the Android hardware.

Secret-bearing DTOs must not implement a plaintext-producing `toString`, must never enter logs or telemetry, and are returned only to the authenticated assigned Installation.

`POST /api/v1/providers/assignments/{assignmentId}/status`

```json
{
  "profileRevision":3,
  "state":"active",
  "safeErrorCode":null,
  "providerReportedExpiresAt":"...",
  "providerReportedMaxConnections":1
}
```

State: `received | applied | disabled_by_user | syncing | active | expired | error`.

Safe error code: `invalid_configuration | authentication_failed | subscription_expired | network_unavailable | dns_failure | tls_failure | connection_timeout | http_401 | http_403 | http_404 | http_429 | server_error | parse_failed | sync_failed | unsupported | user_disabled | unknown`. Error codes never contain provider responses. Provider-reported expiration and maximum connections are upstream subscription facts, not MegaStream License facts.

## Public signing keys

Canonical: `GET /.well-known/offline-lease-keys`.

The app trusts only bundled/pinned signing keys or a keyset authenticated by a bundled trust root. HTTPS retrieval alone may not replace the trust root.
