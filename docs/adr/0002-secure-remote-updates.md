# ADR 0002: Signed application releases and dual-mode remote updates

- Status: Accepted
- Date: 2026-09-30

## Context

MegaStream currently discovers releases through GitHub and uses Android's package installer. Administrators also need a domain-hosted fallback when GitHub is unavailable and a control-panel command that updates outdated televisions.

Android intentionally prevents an ordinary application from silently replacing itself. Silent installation is available only under managed-device authority such as Device Owner. Downloading an APK from an administration server also creates a supply-chain boundary: an HTTPS response alone is not sufficient proof that the file is the intended MegaStream build.

## Decision

Represent every published APK as an immutable **Update Release**. The control plane computes and stores its SHA-256 digest and records the expected application package, monotonically increasing version code, APK signing-certificate fingerprint, ABI/channel metadata, and file size. Published artifacts are never overwritten in place.

GitHub remains the primary discovery source. `megastrem.megastation.uk` provides the fallback manifest and controlled APK download. Android accepts update URLs only from allowlisted HTTPS origins and rejects redirects to other hosts.

Before installation Android verifies:

1. the downloaded SHA-256 equals the published digest;
2. the archive package name equals the installed MegaStream package;
3. its version code equals the release and is newer than the installed version;
4. the APK signing certificate matches the installed application's accepted signing history; and
5. when supplied, the published certificate fingerprint also matches.

An administrator may create an **Update Command** for one or more outdated Installations. Commands are delivered through authenticated device check-ins and are idempotent. Download, prompt, installation, and observed-version states are reported separately; command delivery is never presented as successful installation.

Ordinary Installations download and verify automatically but require the Android package-installer confirmation screen. A Managed Installation may use a PackageInstaller session that requests no user action only after Android confirms MegaStream is Device Owner. If managed authority is absent or silent installation is rejected, the app falls back to the confirmation flow.

## Consequences

- The Plesk deployment needs persistent update-artifact storage with size limits and backup policy.
- The control plane does not claim to validate APK cryptographic signatures unless a real APK-signature parser is deployed; Android remains the final signature verifier.
- The dashboard must distinguish pending, acknowledged, downloading, downloaded, install-prompted, installed, and failed commands.
- Devices that rarely check in cannot receive an immediate command.
- An administrator cannot force silent installation on a consumer Android TV that was not provisioned as Device Owner.

## Rejected alternatives

- **Always silent installation:** rejected because it is not available to ordinary Android applications and would misrepresent platform guarantees.
- **Trusting only HTTPS:** rejected because server or storage misconfiguration could still serve the wrong APK.
- **Arbitrary administrator-provided download URLs:** rejected because they enable credential leakage, redirect abuse, and unreviewed update sources.
- **Mutable “latest.apk” without immutable metadata:** rejected because races and cache poisoning make audit and rollback unsafe.
