# Control-plane deployment

The control plane targets ASP.NET Core 8 and MySQL 8.0. Run Compose from `server/` using its `docker-compose.yml` manifest with Docker Engine and the Compose v2 plugin. Docker is required on the deployment host; Plesk hosts only the public TLS/reverse-proxy boundary, not the .NET process.

The supplied database image targets **MySQL 8.0**, matching the application's configured provider version family. A MySQL 8.4 LTS upgrade has not been runtime-verified here: review provider compatibility, back up, and test schema upgrades/restores before changing the image family. For a release, select reviewed security-patched .NET/MySQL images and pin their immutable digests in deployment; the checked-in major/minor tags are not immutable release pins. Retest and update those pins as part of patch maintenance.

## Security boundary

- Publish the app only on host loopback; do not expose MySQL or container port 8080 publicly.
- Serve the public subdomain exclusively over HTTPS with a valid certificate. Redirect HTTP to HTTPS at Plesk/nginx. The private nginx-to-container hop may use HTTP.
- Forward the original host and scheme. Trust only the actual proxy peer address observed by the app, not arbitrary client headers. Docker bridge NAT can make this the bridge gateway rather than `127.0.0.1`.
- Do not clear `KnownNetworks`/`KnownProxies` to trust everyone. Do not enable blanket automatic forwarded-header trust. Restrict the configured proxy allowlist and host allowlist for the deployment.
- Keep environment files, private signing PEMs, backups, and database credentials outside version control and outside the Docker build context.

## Compose configuration

From `server/`, copy `.env.example` to `.env`, restrict its permissions (`chmod 600 .env`), and populate it locally. Keep secrets out of shell history, screenshots, and shared logs. For the MySQL passwords, independently generated hexadecimal strings avoid special connection-string and Compose interpolation characters; `openssl rand -hex 32` produces suitable random material.

| Compose variable | Purpose |
| --- | --- |
| `MYSQL_PASSWORD` | Non-root `megastream` database user's password; also inserted into the app connection string. |
| `MYSQL_ROOT_PASSWORD` | Independent MySQL root password; never supplied to the app. |
| `DEVICE_SECRET_PEPPER` | Independent persistent secret used for server credential hashes; retain with database backups. |
| `PROVIDER_SECRET_KEY` | Separate external key for encrypted provider provisioning: exactly 32 random bytes encoded as base64. |
| `PROVIDER_SECRET_KEY_VERSION` | Provider encryption key version, default `1`; preserve with the key and database. |
| `ADMIN_EMAIL`, `ADMIN_PASSWORD` | First-administrator bootstrap, not routine login configuration or a password reset mechanism. |
| `ALLOWED_HOSTS` | `megastrem.megastation.uk`, no scheme/path. Compose additionally allows `localhost` for its health probe. |
| `APP_PORT` | Host loopback port, default `5080`; match Plesk's upstream. |
| `UPDATES_PUBLIC_ORIGIN` | Must be exactly `https://megastrem.megastation.uk` outside `Testing`; other origins or a trailing slash fail startup validation. |
| `TRUSTED_PROXIES` | Comma-separated, explicitly trusted proxy IP addresses as seen by Kestrel. |
| `SIGNING_KEY_HOST_PATH` | Existing host private PEM path; defaults to `./secrets/license-signing.pem`. |
| `DATA_PROTECTION_CERTIFICATE_HOST_PATH` | Separate existing RSA certificate/private-key PFX; defaults to `./secrets/data-protection.pfx`, mounted read-only. |
| `DATA_PROTECTION_CERTIFICATE_PASSWORD` | Nonempty external password for that PFX; never put its real value in the sample or image. |
| `DIAGNOSTIC_RETENTION_DAYS` | Default 30; clamped to 1–90 days for diagnostic/session retention cleanup. |
| `ONLINE_WINDOW_MINUTES` | Default 10; dashboard presence window, not a license grace period. |

Compose refuses to start with blank required database/proxy/host settings. It maps the app connection string to `MYSQL_CONNECTION_STRING`, the signing-key mount to `SIGNING_KEY_FILE`, and the persistent DataProtection directory to `DATA_PROTECTION_KEYS_PATH=/var/lib/megastream/keys`. Never set `SIGNING_KEY_PEM` in addition to the file mount unless deliberately replacing that key source.

Generate `DEVICE_SECRET_PEPPER` independently with `openssl rand -base64 32` (at least 32 decoded random bytes), and preserve it across deployments. It is not the database password or signing PEM. Replacing it without a credential migration invalidates hashes derived under the old pepper, so changing it is not an ordinary restart-safe secret rotation.

Generate `PROVIDER_SECRET_KEY` separately with `openssl rand -base64 32`; keep that key and `PROVIDER_SECRET_KEY_VERSION` outside the database and source repository, but backed up securely alongside the database recovery plan. Do not reuse `DEVICE_SECRET_PEPPER`. A provider-encryption key change requires a reviewed decrypt/re-encrypt migration; simply changing a key or version cannot recover previously encrypted records. Telemetry remains credential-free even though the authorized provider-provisioning module stores encrypted credentials.

The initial administrator password must have at least 14 characters and include uppercase, lowercase, a digit, and a non-alphanumeric character. Do not use the hexadecimal database-password example unchanged for this field. After confirming the first login, remove `ADMIN_EMAIL` and `ADMIN_PASSWORD` from `.env` and recreate the app container to remove them from its runtime environment. The database retains the Identity account; changing seed values is not a password-reset procedure.

`MYSQL_PASSWORD` and `MYSQL_ROOT_PASSWORD` initialize a **new** MySQL data volume. Editing `.env` does not change passwords inside an existing database: perform a coordinated database credential rotation, then update the app configuration.

## Persistent signing key

Generate one P-256 private key on the deployment host; do not generate a replacement at every restart:

```sh
install -d -m 700 secrets
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 \
  -out secrets/license-signing.pem
openssl pkey -in secrets/license-signing.pem -pubout \
  -out secrets/license-signing-public.pem
```

The app runs as the .NET Linux image's non-root UID 1654. On a Linux Docker host, make the private key readable by that UID only, for example `sudo chown 1654:1654 secrets/license-signing.pem` and `sudo chmod 0400 secrets/license-signing.pem`. The Compose mount is read-only. Never copy the private key into an image, Android APK, or support ticket. Distribute only the public verification key through the client's pinned-key release process.

Retain an encrypted off-host backup of the private key. Plan rotation with an overlapping public-key trust window; retiring a verifier key prematurely invalidates outstanding offline leases.

## DataProtection encryption certificate

Production requires a **separate** password-protected PFX containing an RSA private key of at least 2048 bits to encrypt persisted DataProtection keys. The P-256 offline-lease signing key is not accepted for this purpose. Compose mounts the existing PFX read-only at `/run/secrets/data-protection.pfx` and sets `DATA_PROTECTION_CERTIFICATE_FILE` to that path. The PFX must be nonempty and at most 1 MiB; missing/partial configuration, an incorrect password, or an incompatible certificate prevents startup outside Development/Testing. Partial configuration is invalid even in those two environments.

Generate once on the deployment host, not on every container restart. This example uses a distinct RSA-3072 key and prompts for passwords without embedding them in command arguments or this document:

```sh
(
  umask 077
  install -d -m 700 secrets
  openssl req -new -x509 -newkey rsa:3072 -sha256 -days 3650 \
    -subj '/CN=MegaStream-DataProtection' \
    -keyout secrets/data-protection-rsa.pem \
    -out secrets/data-protection.crt
  openssl pkcs12 -export \
    -inkey secrets/data-protection-rsa.pem \
    -in secrets/data-protection.crt \
    -out secrets/data-protection.pfx
)
```

Use nonempty strong passwords when prompted; save the **PFX export password** as the external `DATA_PROTECTION_CERTIFICATE_PASSWORD` value. The temporary RSA PEM is encrypted by its own prompt password; protect it independently and never mount it into the app. This certificate encrypts application keys, not browser TLS traffic; the loader does not validate certificate expiry as a TLS certificate policy.

On the Linux Docker host, give only app UID 1654 read access to the PFX (`sudo chown 1654:1654 secrets/data-protection.pfx` then `sudo chmod 0400 secrets/data-protection.pfx`). Keep its password out of shell history and shared Compose/log output. On Linux/Windows the application imports the PFX with `EphemeralKeySet`. On macOS it uses `DefaultKeySet` because .NET rejects ephemeral PFX imports there; without `PersistKeySet`, the [.NET 8.0.20 implementation](https://github.com/dotnet/runtime/blob/v8.0.20/src/libraries/System.Security.Cryptography/src/System/Security/Cryptography/X509Certificates/AppleCertificatePal.ImportExport.macOS.cs) creates a framework-managed temporary keychain. This is not a memory-only macOS guarantee or a fallback to the persistent login keychain. The application never requests `PersistKeySet` and keeps the certificate alive through the host lifetime before disposal. Persisted DataProtection key files still belong on the named key volume and must be certificate-encrypted outside Development/Testing on every OS.

Back up the PFX **and its password** together with the DataProtection key volume, retaining their access separation. Only one certificate is configured: replacing it does not automatically preserve the ability to decrypt old key files. Plan and test an explicit decrypt/re-protect migration or a reviewed multi-certificate transition before replacement, retain the old certificate/password for recovery, and account for existing cookies. Do not delete the old certificate or simply regenerate it during an upgrade. Existing plaintext DataProtection files from an earlier deployment are not automatically retroactively encrypted by enabling this setting; inspect and migrate/re-protect that key ring through a reviewed procedure.

## First deployment and schema upgrades

Run from `server/` after filling `.env` and provisioning both the signing PEM and separate DataProtection PFX:

```sh
docker compose config --quiet
docker compose build app
docker compose up -d db
# One-shot: applies EF migrations, then exits without seeding or serving requests.
docker compose run --rm app --migrate-only
# Continue only if migration exits successfully.
docker compose up -d app
```

Compose's app dependency waits for database health before the migration container starts. `--migrate-only` is handled by the application entry point; the runtime image does not need the SDK or EF CLI. It still requires normal connection/signing/pepper/DataProtection configuration, including the DataProtection PFX and its password. The first normal startup seeds an administrator **only when the Identity user table is empty**. Missing initial seed values fail production startup; existing users are not replaced by changed seed values.

For upgrades, take a restorable database backup, stop the app (`docker compose stop app`), build the reviewed release, and run the same one-shot migration before starting the app again. Run exactly one migration process; do not race migrations from several replicas. Review the migration source before applying it to production. If migration fails, leave the app stopped and investigate; do not bypass it with schema auto-creation.

For SQL review, `MegaStream.Server/Migrations/Deploy.sql` is the combined migration-history-guarded script covering `Initial` and `AddDevicePolicy`. `MegaStream.Server/Migrations/Initial.sql` is the historical baseline only, **not** a complete current deployment script. The recommended `--migrate-only` command applies all pending EF migrations, including the policy columns and audit table. Script generation/source review does not prove successful execution against the target MySQL instance; test the migration and recovery procedure before production.

Normal Compose startup fixes `DB_MIGRATE=false`. The separate `DB_MIGRATE=true` setting exists for deliberate migration-at-startup but continues to seed and run the web app; it is **not** a one-shot deployment job. Production uses EF migrations, not `EnsureCreated`.

## Plesk reverse proxy

Configure the `megastrem.megastation.uk` subdomain, provision its certificate, enable HTTPS redirection, and route requests to the app's loopback port. The offline JWT issuer and production update origin are locked protocol values; this is not an arbitrary-host deployment sample. Use the Plesk Docker proxy-rule UI or an equivalent nginx vhost route. Plesk-generated locations differ by hosting configuration; avoid adding a duplicate `location /` inside an existing one.

The resulting proxy route must be equivalent to:

```nginx
location / {
    # Allow multipart overhead above the app's 200 MiB APK file cap.
    client_max_body_size 210m;
    proxy_pass http://127.0.0.1:5080;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Forwarded-For $remote_addr;
    proxy_set_header X-Forwarded-Host $host;
}
```

This overwrites untrusted incoming forwarding headers at the edge. If a CDN or another proxy sits before Plesk, define and test that separate trust boundary rather than forwarding a client-supplied chain blindly. Preserve the public HTTPS scheme through the complete chain.

Validate the generated nginx configuration before reloading it. Check that public HTTP redirects to HTTPS, HTTPS admin login does not loop, and arbitrary externally supplied `X-Forwarded-Proto` cannot downgrade the application's security decisions. The public API must not be called over plaintext HTTP.

## Deployment smoke checks

Run these from `server/` after configuration, migrations, and startup:

```sh
# Validates interpolation without printing the expanded secret-bearing configuration.
docker compose config --quiet
docker compose ps
# The health probe is deliberately HTTP on loopback; it must return 200, not a redirect.
curl --fail --location --max-redirs 0 --header 'Host: localhost' \
  http://127.0.0.1:5080/health/ready
```

Use your configured port if different from 5080. Verify the public HTTPS site separately from the local health endpoint. Create a test license and installation, test activation and renewal, then revoke and confirm the next online check denies access. Disconnect a test device before revocation to verify the documented offline deadline rather than expecting instant offline revocation.

| Symptom | Check |
| --- | --- |
| Compose refuses interpolation | Fill required `.env` values; use `docker compose config --quiet` without publishing its expanded output. |
| Signing file cannot be mounted/read | The host file must already exist, be P-256 private PEM, and be readable by UID 1654. A directory created in place of the file is not valid. |
| MySQL remains unhealthy | Inspect local database logs and volume initialization; changing `.env` does not rotate an existing database password. |
| App readiness fails | Inspect local app logs, database connectivity, completed migrations, signing-key access, and writable persistent-volume permissions. Do not share unredacted logs/configuration. |
| HTTPS login fails or redirects repeatedly | Verify the observed proxy peer is explicitly trusted and Plesk replaces `X-Forwarded-Proto` with `https`. |
| APK upload receives a proxy size rejection | Review Plesk/nginx request limits and any proxy before it; include multipart overhead above the file limit. |

## Optional host-verified container hardening

Docker is unavailable in the authoring environment, so these are **deployment-host verification recommendations**, not tested settings or changes already enabled in the manifest. Consider a local Compose override for the app with `read_only: true`, `cap_drop: [ALL]`, `security_opt: [no-new-privileges:true]`, and a writable `/tmp` tmpfs. Keep the DataProtection and APK volumes writable by UID 1654, and both the signing PEM and DataProtection PFX read-only. Do not apply the app's settings blindly to MySQL, which has separate filesystem/initialization requirements.

Multipart APK uploads can spool files to temporary storage. For the 200 MiB file limit, budget at least 256 MiB of usable temporary space for one upload and preferably 512 MiB or more; a 64 MiB `/tmp` tmpfs is insufficient. Account for simultaneous uploads, framework overhead, proxy buffering, and the container's total memory limit. Verify non-root write permissions and a near-limit upload before enabling read-only-root/tmpfs restrictions. A tmpfs budget is not a guarantee of sufficient physical/container memory.

## Backups and recovery

Back up MySQL consistently, the DataProtection key volume **and its RSA PFX/password**, APK storage volume, P-256 signing private key, `PROVIDER_SECRET_KEY` and its version, `DEVICE_SECRET_PEPPER`, and deployment configuration as one recoverable system. Encrypt backups and test restores into an isolated deployment. Losing either DataProtection keys or their decryption certificate/password can invalidate administrative cookies; lost signing keys interrupt lease renewal and rotation. Production encrypts newly persisted DataProtection keys using the configured certificate, but filesystem/volume access controls, encrypted storage, and protected backups remain required.

Do not use `docker compose down -v` during upgrades: it removes named persistent volumes. Keep database backups before every schema upgrade. An older image is not necessarily compatible with a migrated database; rollback requires a reviewed schema plan or a tested database restore, not just an image tag change.

## Offline revocation

An unreachable device cannot learn that an administrator revoked its license or disabled its installation. A successful online denial must override cached access; an offline device may continue until its already-issued signed offline deadline. Do not promise immediate offline revocation. Removing a binding must retain its seat until any outstanding offline reservation expires, otherwise a removed offline installation and a replacement may both consume the same license capacity.
