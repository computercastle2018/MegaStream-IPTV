# MegaStream control plane

ASP.NET Core 8 server with MySQL 8.0 persistence. This directory is independent of the Android Gradle build.

- [Deployment and Plesk reverse proxy](docs/DEPLOYMENT.md)
- [REST API](docs/API.md)
- [Environment sample](.env.example)
- [Docker Compose manifest](docker-compose.yml)

## Build

Install the .NET 8 SDK, then run from `server/`:

```sh
dotnet restore MegaStream.sln
dotnet build MegaStream.sln --configuration Release --no-restore
dotnet test MegaStream.sln --configuration Release --no-build
```

The container build uses the .NET 8 SDK stage and the ASP.NET Core 8 runtime stage. It runs as a non-root user on container port 8080. Compose publishes that port only to host loopback, persists MySQL and DataProtection keys, and mounts the existing P-256 signing key and a separate RSA DataProtection encryption PFX read-only.

## Layout

- `MegaStream.Server/Program.cs`: host configuration, authentication, proxy trust, health endpoints, and explicit migration entry point.
- `MegaStream.Server/V1/`: canonical device protocol, heartbeat/entitlement adapters, and typed diagnostic validation.
- `MegaStream.Server/Services/`: licensing/activation transactions, signing, credential hashing, bootstrap, and retention.
- `MegaStream.Server/Controllers/` and `Views/`: authenticated Arabic administration.
- `MegaStream.Server/Updates/`: APK storage, publication, manifests, and device update commands; [integration notes](MegaStream.Server/Updates/INTEGRATION.md).
- `MegaStream.Server/RemoteProviders/`: encrypted provider profiles, assignments, and reports; [module reference](MegaStream.Server/RemoteProviders/README.md).
- `MegaStream.Server/Migrations/`: EF migration history, model snapshot, and deployment SQL artifacts; review these before applying upgrades.
- `MegaStream.Server.Tests/`: xUnit/ASP.NET test-host and SQLite regression tests. These do not replace MySQL/Docker deployment verification.

## Deployment checklist

1. Read the deployment guide and create a protected `.env` from `.env.example`.
2. Generate and securely retain the P-256 signing key and separate RSA PFX/password for DataProtection encryption at rest. Configure the exact trusted reverse-proxy peer IPs.
3. Back up the database, then apply EF migrations explicitly before starting the release.
4. Seed the first administrator through `ADMIN_EMAIL` and `ADMIN_PASSWORD`; there are no shipped administrator credentials.
5. Put Plesk HTTPS in front of the loopback listener and verify both container health checks.

Do not expose the app listener or MySQL directly to the Internet. Do not treat an offline lease as a device bearer credential. Offline revocation cannot be immediate: an already-issued lease remains usable offline until its signed deadline.

## Administration

Open `/Account/Login` on the public HTTPS origin and sign in with the bootstrapped administrator. The MVC/Razor administration layout is Arabic and right-to-left; it links to licenses, installations, activation requests, diagnostics, updates (`/admin/updates`), and provider subscriptions (`/admin/providers`). Device details include requested kiosk policy and its change audit. Administrative mutations use authenticated cookies and antiforgery-protected forms, not installation bearer tokens.

The dashboard's online window is a presence estimate, not proof of playback or an entitlement decision. Do not confuse a MegaStream application license with an upstream IPTV provider subscription.

## Verification limits

The deployment files require acceptance testing on the target Docker/Plesk host. A successful source build does not verify Linux volume permissions, proxy peer addresses, TLS configuration, or a MySQL schema upgrade. Follow the smoke checks in the deployment guide before directing devices to a release.
