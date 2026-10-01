using System.Text.Json;
using MegaStream.Server.Contracts;
using MegaStream.Server.Data;
using MegaStream.Server.Models;
using MegaStream.Server.Services;
using MegaStream.Server.Updates;
using Microsoft.EntityFrameworkCore;

namespace MegaStream.Server.V1;

public sealed class V1Service(AppDbContext db, DeviceService devices, LeaseSigner signer, SecretHasher hasher,
    UpdateService updates)
{
    private const int RefreshAfterSeconds = 60;
    private const long MaximumMemoryBytes = 1L << 40;

    public async Task<V1RegisterResponse> RegisterAsync(V1RegisterRequest request, string idempotencyKey,
        CancellationToken ct = default)
    {
        ValidateRegistration(request, idempotencyKey);
        var now = DateTime.UtcNow;
        var installation = new Installation
        {
            Id = request.InstallationId,
            TokenHash = hasher.Hash("device", request.Credential),
            CredentialBinding = SecretHasher.CredentialBinding(request.Credential),
            Platform = "android", AppVersion = request.AppVersionName,
            Manufacturer = request.Manufacturer, DeviceModel = request.Model,
            OsVersion = request.AndroidRelease, Locale = request.Locale,
            CreatedAt = now, LastSeenAt = now
        };
        var metadata = new V1InstallationMetadata
        {
            InstallationId = installation.Id,
            RegistrationIdempotencyKey = Guid.Parse(idempotencyKey),
            AppVersionCode = request.AppVersionCode, AndroidApi = request.AndroidApi,
            Abi = request.Abi, ManagedDevice = request.ManagedDevice,
            PackageName = request.PackageName, Channel = request.Channel
        };
        try
        {
            await using var transaction = await db.Database.BeginTransactionAsync(DomainRules.Isolation(db), ct);
            // Also serializes SQLite writers before the existence check; MySQL's unique key handles absent-row races.
            await DomainRules.LockDevice(db, installation.Id, ct);
            var existing = await db.Installations.AsNoTracking().SingleOrDefaultAsync(x => x.Id == installation.Id, ct);
            if (existing is not null) return RegistrationReplay(existing, request.Credential);
            db.Installations.Add(installation);
            db.Set<V1InstallationMetadata>().Add(metadata);
            await db.SaveChangesAsync(ct);
            await ObserveBuild(installation.Id, metadata, ct);
            await db.SaveChangesAsync(ct);
            // Return the persisted precision, including MySQL's microsecond truncation, on the first response too.
            await db.Entry(installation).ReloadAsync(ct);
            await transaction.CommitAsync(ct);
            return new(installation.Id, DomainRules.Utc(installation.CreatedAt));
        }
        catch (DbUpdateException)
        {
            // The failed transaction is disposed before re-querying, so a concurrent winner is visible.
            db.Entry(metadata).State = EntityState.Detached;
            db.Entry(installation).State = EntityState.Detached;
            var winner = await db.Installations.AsNoTracking().SingleOrDefaultAsync(x => x.Id == installation.Id, ct);
            if (winner is not null) return RegistrationReplay(winner, request.Credential);
            if (await db.Installations.AsNoTracking().AnyAsync(x => x.TokenHash == installation.TokenHash, ct))
                throw new DomainException(409, "credential_conflict", "Credential is already bound to another installation.");
            throw;
        }
    }

    public async Task<V1EntitlementResponse> ActivateAsync(Guid installationId, string licenseKey, CancellationToken ct = default)
    {
        var keyHash = hasher.Hash("license", Secrets.NormalizeKey(licenseKey));
        await using var transaction = await db.Database.BeginTransactionAsync(DomainRules.Isolation(db), ct);
        var installation = await LockInstallation(installationId, ct);
        if (installation.Status != InstallationStatus.Active)
            throw new DomainException(403, "installation_disabled", "Installation is disabled.");
        var licenseId = await db.Licenses.Where(x => x.KeyHash == keyHash).Select(x => (Guid?)x.Id).SingleOrDefaultAsync(ct)
            ?? throw new DomainException(404, "invalid_key", "License key was not found.");
        var license = await LockLicense(licenseId, ct);
        if (DecisionState(installation, license, DateTime.UtcNow) == "allowed")
            await DomainRules.Assign(db, installation, license!, ct);
        var entitlement = EntitlementForLicense(installation, license);
        await db.SaveChangesAsync(ct);
        await transaction.CommitAsync(ct);
        return entitlement;
    }

    public Task<ActivationCodeResponse> RequestCodeAsync(Guid installationId, CancellationToken ct = default) =>
        devices.RequestCodeAsync(installationId, ct);

    public async Task<object> PollCodeAsync(Guid installationId, string code, string pollToken,
        CancellationToken ct = default)
    {
        if (string.IsNullOrWhiteSpace(code) || code.Length > 16 || string.IsNullOrWhiteSpace(pollToken) || pollToken.Length > 128)
            throw Invalid("invalid_activation", "Code and polling token are required.");
        await using var transaction = await db.Database.BeginTransactionAsync(DomainRules.Isolation(db), ct);
        var installation = await LockInstallation(installationId, ct);
        if (installation.Status != InstallationStatus.Active)
            throw new DomainException(403, "installation_disabled", "Installation is disabled.");
        var activation = await LockActivation(installationId, code.Trim().ToUpperInvariant(), pollToken, ct);
        // Consumed polling observes the installation's current license, never reassigning the original candidate.
        if (activation.Status == ActivationCodeStatus.Consumed)
        {
            var current = await EntitlementUnderLock(installation, ct);
            await db.SaveChangesAsync(ct);
            await transaction.CommitAsync(ct);
            return current;
        }
        if (activation.ExpiresAt <= DateTime.UtcNow || activation.Status == ActivationCodeStatus.Expired)
        {
            activation.Status = ActivationCodeStatus.Expired;
            await db.SaveChangesAsync(ct);
            await transaction.CommitAsync(ct);
            throw new DomainException(410, "activation_expired", "Activation code has expired.");
        }
        if (activation.Status == ActivationCodeStatus.Pending)
            return new V1ActivationPendingResponse("pending", DateTime.UtcNow);
        if (activation.Status != ActivationCodeStatus.Approved)
            throw new DomainException(410, "activation_cancelled", "Activation code has been cancelled.");
        var licenseId = activation.LicenseId ?? throw new DomainException(409, "invalid_activation", "Activation has no assigned license.");
        var license = await LockLicense(licenseId, ct);
        if (DecisionState(installation, license, DateTime.UtcNow) == "allowed")
        {
            await DomainRules.Assign(db, installation, license!, ct);
            activation.Status = ActivationCodeStatus.Consumed;
            activation.ConsumedAt = DateTime.UtcNow;
        }
        var entitlement = EntitlementForLicense(installation, license);
        await db.SaveChangesAsync(ct);
        await transaction.CommitAsync(ct);
        return entitlement;
    }

    public async Task<V1EntitlementResponse> EntitlementAsync(Guid installationId, CancellationToken ct = default)
    {
        await using var transaction = await db.Database.BeginTransactionAsync(DomainRules.Isolation(db), ct);
        var installation = await LockInstallation(installationId, ct);
        var entitlement = await EntitlementUnderLock(installation, ct);
        await db.SaveChangesAsync(ct);
        await transaction.CommitAsync(ct);
        return entitlement;
    }

    public async Task<V1EntitlementResponse> HeartbeatAsync(Guid installationId, V1HeartbeatRequest request,
        CancellationToken ct = default)
    {
        ValidateHeartbeat(request);
        await using var transaction = await db.Database.BeginTransactionAsync(DomainRules.Isolation(db), ct);
        var installation = await LockInstallation(installationId, ct);
        if (installation.Status != InstallationStatus.Active)
        {
            var disabled = (await EntitlementUnderLock(installation, ct)) with
            {
                DevicePolicy = new V1DevicePolicy("off", true)
            };
            await transaction.CommitAsync(ct);
            return disabled;
        }
        var metadata = await db.Set<V1InstallationMetadata>().SingleOrDefaultAsync(x => x.InstallationId == installationId, ct)
            ?? throw new DomainException(409, "registration_required", "Canonical registration metadata is required.");
        await db.Entry(metadata).ReloadAsync(ct);
        if (metadata.PackageName != request.PackageName || metadata.Channel != request.Channel)
            throw new DomainException(409, "build_conflict", "Heartbeat build does not match the registered installation.");
        var state = await V1SessionClaims.ClaimAsync(db, installationId, request.AppSessionId, DateTime.UtcNow, ct);
        // The installation SQL write lock covers both creation and sequence comparison for all its sessions.
        if (request.Sequence > state.LastSequence)
        {
            var now = DateTime.UtcNow;
            state.LastSequence = request.Sequence;
            state.LastAcceptedAt = now;
            state.Mode = request.Mode;
            SetMemory(state, request.Memory);
            if (request.RecoveredExit is { } exit)
            {
                state.RecoveredExitReason = exit.Reason;
                state.RecoveredExitEvidence = exit.Evidence;
                state.RecoveredExitAt = exit.OccurredAt?.UtcDateTime;
            }
            installation.LastSeenAt = now;
            installation.AppVersion = request.AppVersionName;
            metadata.AppVersionCode = request.AppVersionCode;
            metadata.ManagedDevice = request.ManagedDevice;
            var sessionId = request.AppSessionId.ToString("D");
            var session = await db.DeviceSessions.SingleOrDefaultAsync(
                x => x.InstallationId == installationId && x.ClientSessionId == sessionId, ct);
            if (session is null)
            {
                session = new DeviceSession { InstallationId = installationId, ClientSessionId = sessionId, StartedAt = now };
                db.DeviceSessions.Add(session);
            }
            else await db.Entry(session).ReloadAsync(ct);
            session.LastHeartbeatAt = now;
            session.AppVersion = request.AppVersionName;
            session.MemoryBytes = request.Memory?.PssBytes;
            // Recovered exits describe a previous process, never terminate the currently heartbeating session.
            await ObserveBuild(installationId, metadata, ct);
        }
        var entitlement = (await EntitlementUnderLock(installation, ct)) with
        {
            DevicePolicy = new V1DevicePolicy(installation.KioskMode, installation.AllowLocalExit)
        };
        await db.SaveChangesAsync(ct);
        await transaction.CommitAsync(ct);
        return entitlement;
    }

    private async Task<ActivationCode> LockActivation(Guid installationId, string code, string pollToken, CancellationToken ct)
    {
        var codeHash = hasher.Hash("code", code);
        var activationId = await db.ActivationCodes.Where(x => x.InstallationId == installationId && x.CodeHash == codeHash)
            .Select(x => (Guid?)x.Id).SingleOrDefaultAsync(ct)
            ?? throw new DomainException(404, "not_found", "Activation code not found.");
        await DomainRules.LockCode(db, activationId, ct);
        var activation = await db.ActivationCodes.SingleAsync(x => x.Id == activationId, ct);
        await db.Entry(activation).ReloadAsync(ct);
        if (!hasher.Matches("poll", pollToken, activation.PollTokenHash))
            throw new DomainException(404, "not_found", "Activation code not found.");
        return activation;
    }

    private async Task<Installation> LockInstallation(Guid installationId, CancellationToken ct)
    {
        await DomainRules.LockDevice(db, installationId, ct);
        var installation = await db.Installations.SingleOrDefaultAsync(x => x.Id == installationId, ct)
            ?? throw new DomainException(401, "invalid_device", "Device authentication failed.");
        await db.Entry(installation).ReloadAsync(ct);
        return installation;
    }

    private async Task<V1EntitlementResponse> EntitlementUnderLock(Installation installation, CancellationToken ct)
    {
        var license = installation.LicenseId is Guid licenseId ? await LockLicense(licenseId, ct) : null;
        return EntitlementForLicense(installation, license);
    }

    private async Task<License?> LockLicense(Guid licenseId, CancellationToken ct)
    {
        await DomainRules.LockLicense(db, licenseId, ct);
        var license = await db.Licenses.SingleOrDefaultAsync(x => x.Id == licenseId, ct);
        if (license is not null) await db.Entry(license).ReloadAsync(ct);
        return license;
    }

    private static string DecisionState(Installation installation, License? license, DateTime now) =>
        installation.Status != InstallationStatus.Active ? "installation_disabled"
            : license is null ? (installation.LicenseId is null ? "unlicensed" : "verification_required")
            : license.Status == LicenseStatus.Revoked ? "revoked"
            : license.Status == LicenseStatus.Suspended ? "suspended"
            : license.Status != LicenseStatus.Active ? "verification_required"
            : license.ValidUntil <= now ? "expired"
            : license.ValidFrom > now ? "not_started" : "allowed";

    private V1EntitlementResponse EntitlementForLicense(Installation installation, License? license)
    {
        var now = DateTime.UtcNow;
        var state = DecisionState(installation, license, now);
        string? lease = null;
        DateTime? offlineUntil = null;
        if (state == "allowed")
        {
            lease = signer.Sign(installation, license!);
            offlineUntil = LeaseExpiry(lease);
        }
        return new(new V1Decision(state, license?.Id, license?.Revision,
            license is null ? null : DomainRules.Utc(license.ValidFrom),
            license is null ? null : DomainRules.Utc(license.ValidUntil), offlineUntil),
            now, RefreshAfterSeconds, lease);
    }

    private V1RegisterResponse RegistrationReplay(Installation installation, string credential)
    {
        if (!hasher.Matches("device", credential, installation.TokenHash))
            throw new DomainException(409, "installation_conflict", "Installation is already registered with another credential.");
        return new(installation.Id, DomainRules.Utc(installation.CreatedAt));
    }

    private static DateTime LeaseExpiry(string lease)
    {
        var payload = lease.Split('.')[1].Replace('-', '+').Replace('_', '/');
        payload = payload.PadRight((payload.Length + 3) / 4 * 4, '=');
        using var json = JsonDocument.Parse(Convert.FromBase64String(payload));
        return DateTimeOffset.FromUnixTimeSeconds(json.RootElement.GetProperty("exp").GetInt64()).UtcDateTime;
    }

    private static void ValidateRegistration(V1RegisterRequest request, string idempotencyKey)
    {
        if (request is null || request.InstallationId == Guid.Empty ||
            !Guid.TryParseExact(idempotencyKey, "D", out var key) || key == Guid.Empty)
            throw Invalid("invalid_registration", "Installation ID and UUID Idempotency-Key are required.");
        if (!IsCanonicalCredential(request.Credential))
            throw Invalid("invalid_credential", "Credential must be a canonical base64url encoding of 32 bytes.");
        ValidateVersion(request.AppVersionCode, request.AppVersionName);
        ValidateBuild(request.PackageName, request.Channel);
        Text(request.Manufacturer, 64); Text(request.Model, 64); Text(request.AndroidRelease, 32); Text(request.Locale, 16);
        if (request.AndroidApi < 27 || request.Abi is not ("arm64_v8a" or "armeabi_v7a" or "x86_64" or "x86" or "other"))
            throw Invalid("invalid_metadata", "Android API level or ABI is invalid.");
    }

    private static bool IsCanonicalCredential(string? credential)
    {
        if (credential is null || credential.Length != 43 || credential.Any(c => !char.IsAsciiLetterOrDigit(c) && c != '-' && c != '_'))
            return false;
        try
        {
            var bytes = Convert.FromBase64String(credential.Replace('-', '+').Replace('_', '/') + "=");
            return bytes.Length == 32 && Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_') == credential;
        }
        catch (FormatException) { return false; }
    }

    private static void ValidateHeartbeat(V1HeartbeatRequest request)
    {
        if (request is null || request.AppSessionId == Guid.Empty || request.Sequence < 0 ||
            request.Mode is not ("foreground" or "background" or "playback"))
            throw Invalid("invalid_heartbeat", "Heartbeat session, sequence or mode is invalid.");
        ValidateVersion(request.AppVersionCode, request.AppVersionName);
        ValidateBuild(request.PackageName, request.Channel);
        if (request.Memory is { } memory)
        {
            var values = new[] { memory.JavaUsedBytes, memory.JavaMaxBytes, memory.NativeHeapBytes, memory.PssBytes, memory.AvailableSystemBytes };
            if (values.Any(value => value is < 0 or > MaximumMemoryBytes) || memory.JavaUsedBytes > memory.JavaMaxBytes)
                throw Invalid("invalid_memory", "Memory values are inconsistent or out of range.");
        }
        if (request.RecoveredExit is { } exit)
        {
            if (exit.Reason is not ("clean_exit" or "os_exit" or "java_crash" or "anr" or "oom" or "low_memory_kill" or
                "native_crash" or "signal" or "user_requested" or "system_kill" or "unknown") ||
                exit.Evidence is not ("reported" or "recovered_os" or "recovered_marker" or "inferred" or "watchdog_suspected"))
                throw Invalid("invalid_exit", "Recovered exit reason or evidence is invalid.");
            var now = DateTimeOffset.UtcNow;
            if (exit.OccurredAt is { } occurred &&
                (occurred.Offset != TimeSpan.Zero || occurred < now.AddDays(-30) || occurred > now.AddMinutes(5)))
                throw Invalid("invalid_time", "Recovered exit timestamp is outside the accepted UTC window.");
        }
    }

    private Task ObserveBuild(Guid installationId, V1InstallationMetadata metadata, CancellationToken ct) =>
        updates.ObserveAsync(installationId, new UpdateCheckRequest(metadata.AppVersionCode, metadata.PackageName,
            metadata.Channel, metadata.AndroidApi, metadata.ManagedDevice ? "managed" : "prompt", metadata.Abi), ct);

    private static void ValidateBuild(string packageName, string channel)
    {
        if (!((packageName == "com.megastream.app" && channel == "stable") ||
            (packageName == "com.megastream.app.beta" && channel == "beta")))
            throw Invalid("invalid_build", "Package name and channel must identify a supported build.");
    }

    private static void ValidateVersion(int versionCode, string versionName)
    {
        if (versionCode <= 0) throw Invalid("invalid_metadata", "App version code must be positive.");
        Text(versionName, 32);
    }

    private static void Text(string? text, int maximum)
    {
        if (string.IsNullOrWhiteSpace(text) || text.Length > maximum || text.Any(char.IsControl))
            throw Invalid("invalid_metadata", "Required metadata is missing, too long or contains control characters.");
        Sanitizer.ValidateDiagnostic(text);
    }

    private static void SetMemory(V1SessionState state, V1Memory? memory)
    {
        state.JavaUsedBytes = memory?.JavaUsedBytes;
        state.JavaMaxBytes = memory?.JavaMaxBytes;
        state.NativeHeapBytes = memory?.NativeHeapBytes;
        state.PssBytes = memory?.PssBytes;
        state.AvailableSystemBytes = memory?.AvailableSystemBytes;
        state.LowMemory = memory?.LowMemory;
    }

    private static DomainException Invalid(string code, string message) => new(400, code, message);
}
