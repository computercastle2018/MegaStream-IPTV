# Update management integration

This module requires host integration; the instructions below do not modify core files.

## Host wiring

- Import `MegaStream.Server.Updates` and call `builder.Services.AddUpdateManagement(builder.Configuration)` before building the host. Keep `AddControllersWithViews`, `MapControllers`, authentication, authorization and antiforgery services enabled.
- In `AppDbContext.OnModelCreating`, after the base call, invoke `UpdateModelConfiguration.Configure(b)` using that method's model-builder variable. The module uses `db.Set<T>()`; `DbSet<UpdateRelease>` and `DbSet<DeviceUpdateCommand>` properties are optional. Generate, review and apply an EF migration before enabling the endpoints.
- The admin page is `/admin/updates`, rendered explicitly from `~/Updates/Views/Index.cshtml`. It uses the host's existing `Admin` policy (Identity application-cookie authentication), not an assumed role. All admin mutations require an antiforgery token; use the supplied forms.
- Device routes are under `/api/v1/updates` and require the `Device` authentication scheme. Identity is the authenticated installation GUID in `ClaimTypes.NameIdentifier`, never a caller-supplied installation ID.
- To deliver commands through the existing heartbeat, authenticate the installation and resolve `UpdateService`. When complete update telemetry is available, await `ObserveAsync(installationId, updateCheckRequest, cancellationToken)` using an `UpdateCheckRequest`; this method opens no transaction, so the adapter may call it inside the heartbeat's existing transaction. Do not invent missing package/SDK/ABI information for older clients: preserve unknown update state until a complete check supplies telemetry. Then await `GetPendingAsync(installationId, cancellationToken)` and add that flat safe DTO as `updateCommand` in the heartbeat response. Preserve existing lease fields; pending retrieval is not installation acknowledgement. Clients may alternatively poll `GET /api/v1/updates/pending`.
- Exempt successful canonical APK responses (`/updates/files/{releaseId}/release.apk`, including range and conditional responses) from the core no-store `OnStarting` callback. Otherwise that callback overwrites the controller's immutable cache header. Keep no-store for API/admin responses and unsuccessful downloads.

## Required configuration

```json
{
  "Updates": {
    "PublicOrigin": "https://megastrem.megastation.uk",
    "StoragePath": "/var/lib/megastream/apks",
    "MaxUploadBytes": 209715200
  }
}
```

`PublicOrigin` must be exactly `https://megastrem.megastation.uk` outside the `Testing` environment. Testing may explicitly configure a different HTTPS scheme/host/optional port origin, without credentials, application path, query or fragment. Download URLs are built by the server; no client-supplied URL is accepted. `StoragePath` must be a persistent, absolute, physical directory outside `wwwroot` and the configured web root. Storage rejects symlink ancestors: use the resolved physical path. Grant the server account access and **never** expose the directory through a static-file provider, reverse-proxy alias or public hosting mount. Back up artifacts together with database records. `MaxUploadBytes` must be positive and at most 209715200 (200 MiB); a lower value is enforced by storage. Configuration is validated at startup.

For Docker Compose, mount a persistent named volume on the server service, for example `megastream_apks:/var/lib/megastream/apks`, and declare `megastream_apks` under top-level `volumes`. Provision its directory ownership and read/write/traverse permissions for the server service UID/GID, not world-writable access. Keep the same volume across container replacement.

Upload alone overrides the host's small request limit: total request 210763776 bytes (201 MiB), multipart section 209715200 bytes (200 MiB). Configure nginx/Plesk for this route with `client_max_body_size 202m;` (and sufficient upstream timeout); any IIS/proxy body limit must also allow the total request size. Keep smaller limits on unrelated API routes. Oversized uploads may receive HTTP 413 before controller execution; lowering the storage cap may reject smaller files as well.

## API and trust boundary

- `POST /api/v1/updates/check`: JSON `{ "versionCode": 1, "packageName": "com.megastream.app", "channel": "stable", "sdk": 28, "mode": "prompt", "abi": "arm64_v8a" }`. All release and device `versionCode` values must be positive.
- `GET /api/v1/updates/pending`: retrieve the authenticated installation's pending command.
- `POST /api/v1/updates/commands/{commandId}/status`: JSON `{ "status": "ack", "errorCode": null }`; accepted reports return HTTP 204. Failure example: `{ "status": "failed", "errorCode": "hash_mismatch" }`. For failure reports, null becomes `unknown`; a provided error code must contain 1–128 ASCII letters/digits, underscores, dots or hyphens. It is sanitized before storage; never send diagnostic text, URLs or credentials. Unknown/non-owned commands return HTTP 404, invalid transitions HTTP 409; repeated or stale successful statuses are idempotent. Known revoked installations receive HTTP 403 (`installation_disabled`).
- `GET /api/v1/public/updates/latest?channel=stable&abi=arm64_v8a`: anonymous manifest, or HTTP 204 if no compatible release exists. Supported channels: `stable`, `beta`; ABIs: `arm64_v8a`, `armeabi_v7a`, `x86_64`, `x86`, `other`.
- `GET /updates/files/{releaseId}/release.apk`: anonymous **published-only** immutable APK stream, range-enabled, with SHA-256 ETag and a generated `release-{versionCode}.apk` attachment name. Published artifacts are intentionally public; no bearer token is required. The controller sets `Cache-Control: public,max-age=31536000,immutable`; the host must preserve it through the `OnStarting` exemption described above. Drafts remain inaccessible. Device check/pending/report still require authenticated installation claims.
- The admin page supports upload, separate publication, and sending to one or multiple installation GUIDs. `/admin/updates/releases/{releaseId}/outdated` is an authenticated JSON report.

Artifacts are immutable after upload. The server computes the artifact SHA-256; the optional supplied signing-certificate SHA-256 is **expected metadata attested by a trusted build pipeline/administrator**, not a certificate extracted from or cryptographically verified against the APK. Package name, version, ABI and minimum SDK metadata likewise require trusted pipeline attestation; archive validation does not establish those values. Only `com.megastream.app` and `com.megastream.app.beta` packages are supported. Selecting `universal` ABI explicitly attests multi-ABI compatibility. Do not accept untrusted release submissions.

Android clients must verify the downloaded bytes' SHA-256 and, if `signingCertificateSha256` is supplied, verify that expected signing-certificate hash. Android's package manager must always validate package/signature compatibility, including when this optional field is absent. `prompt` requires normal user-mediated installation. `managed` is an explicit managed-install request only: neither a request field nor the server infers or grants Android device-owner capability. The client must confirm its real provisioning/capability and fail safely if unavailable.
