using System.Security.Cryptography;
using MegaStream.Server.Contracts;
using MegaStream.Server.Data;
using MegaStream.Server.Models;
using Microsoft.EntityFrameworkCore;

namespace MegaStream.Server.Services;

public sealed class DeviceService(AppDbContext db, LeaseSigner signer, SecretHasher hasher)
{
    public async Task<RegisterResponse> RegisterAsync(RegisterRequest request, CancellationToken ct = default)
    {
        if (request is null || !Guid.TryParseExact(request.Fingerprint, "D", out var fingerprintId) || fingerprintId == Guid.Empty)
            throw new DomainException(400, "invalid_fingerprint", "A random app-install UUID is required; hardware identifiers are not accepted.");
        var fingerprint = fingerprintId.ToString("D");
        var token = Secrets.RandomToken();
        var device = new Installation
        {
            TokenHash = hasher.Hash("device", token),
            CredentialBinding = SecretHasher.CredentialBinding(token),
            FingerprintHash = Secrets.Hash(fingerprint),
            Platform = Required(request.Platform, 32),
            AppVersion = Required(request.AppVersion, 64),
            DeviceModel = Required(request.DeviceModel, 128),
            OsVersion = Required(request.AndroidVersion ?? request.OsVersion, 64),
            Manufacturer = Sanitizer.Clean(request.Manufacturer, 128),
            Locale = Sanitizer.Clean(request.Locale, 32)
        };
        db.Installations.Add(device); await db.SaveChangesAsync(ct);
        return new(device.Id, token);
    }
    public async Task<LeaseResponse> ActivateAsync(Guid installationId, string licenseKey, CancellationToken ct = default)
    {
        var normalizedKey = Secrets.NormalizeKey(licenseKey);
        var hash = hasher.Hash("license", normalizedKey);
        await using var tx = await db.Database.BeginTransactionAsync(DomainRules.Isolation(db), ct);
        await DomainRules.LockDevice(db, installationId, ct);
        var device = await DomainRules.Device(db, installationId, ct);
        var licenseId = await db.Licenses.Where(x => x.KeyHash == hash).Select(x => (Guid?)x.Id).SingleOrDefaultAsync(ct)
            ?? throw new DomainException(404, "invalid_key", "License key was not found.");
        await DomainRules.LockLicense(db, licenseId, ct);
        var license = await db.Licenses.SingleAsync(x => x.Id == licenseId, ct);
        await db.Entry(license).ReloadAsync(ct);
        if (!hasher.Matches("license", normalizedKey, license.KeyHash)) throw new DomainException(404, "invalid_key", "License key was not found.");
        await DomainRules.Assign(db, device, license, ct);
        device.LastSeenAt = DateTime.UtcNow;
        var lease = signer.Sign(device, license);
        await db.SaveChangesAsync(ct); await tx.CommitAsync(ct);
        return new(lease, "active");
    }
    public async Task<ActivationCodeResponse> RequestCodeAsync(Guid installationId, CancellationToken ct = default)
    {
        await using var tx = await db.Database.BeginTransactionAsync(DomainRules.Isolation(db), ct);
        await DomainRules.LockDevice(db, installationId, ct);
        var device = await DomainRules.Device(db, installationId, ct);
        if (device.LicenseId != null) throw new DomainException(409, "already_licensed", "Installation already has a license.");
        var prior = await db.ActivationCodes.Where(x => x.InstallationId == installationId && (x.Status == ActivationCodeStatus.Pending || x.Status == ActivationCodeStatus.Approved)).ToListAsync(ct);
        foreach (var previousCode in prior)
            previousCode.Status = previousCode.ExpiresAt <= DateTime.UtcNow ? ActivationCodeStatus.Expired : ActivationCodeStatus.Cancelled;
        var pollToken = Secrets.RandomToken();
        var rawCode = Convert.ToHexString(RandomNumberGenerator.GetBytes(6));
        var code = new ActivationCode { InstallationId = installationId, CodeHash = hasher.Hash("code", rawCode), CodeLast4 = rawCode[^4..], PollTokenHash = hasher.Hash("poll", pollToken), ExpiresAt = DateTime.UtcNow.AddMinutes(10) };
        db.ActivationCodes.Add(code); await db.SaveChangesAsync(ct); await tx.CommitAsync(ct);
        return new(rawCode, code.ExpiresAt, "pending", pollToken);
    }
    public async Task<ActivationStatusResponse> PollCodeAsync(Guid installationId, string code, string pollToken, CancellationToken ct = default)
    {
        if (string.IsNullOrWhiteSpace(code) || code.Length > 16 || string.IsNullOrWhiteSpace(pollToken) || pollToken.Length > 128)
            throw new DomainException(400, "invalid_activation", "Code and polling token are required.");
        code = code.Trim().ToUpperInvariant();
        await using var tx = await db.Database.BeginTransactionAsync(DomainRules.Isolation(db), ct);
        await DomainRules.LockDevice(db, installationId, ct);
        var device = await DomainRules.Device(db, installationId, ct);
        var codeHash = hasher.Hash("code", code);
        var activationId = await db.ActivationCodes.Where(x => x.CodeHash == codeHash && x.InstallationId == installationId).Select(x => (Guid?)x.Id).SingleOrDefaultAsync(ct)
            ?? throw new DomainException(404, "not_found", "Activation code not found.");
        await DomainRules.LockCode(db, activationId, ct);
        var activation = await db.ActivationCodes.SingleAsync(x => x.Id == activationId, ct);
        await db.Entry(activation).ReloadAsync(ct);
        if (!hasher.Matches("poll", pollToken, activation.PollTokenHash) || !hasher.Matches("code", code, activation.CodeHash))
            throw new DomainException(404, "not_found", "Activation code not found.");
        // Consumed responses never mint a second lease; the device resumes with heartbeat.
        if (activation.Status == ActivationCodeStatus.Consumed) return new("consumed", null);
        if (activation.ExpiresAt <= DateTime.UtcNow) activation.Status = ActivationCodeStatus.Expired;
        string? lease = null;
        if (activation.Status == ActivationCodeStatus.Approved)
        {
            var licenseId = activation.LicenseId ?? throw new DomainException(409, "invalid_activation", "Activation has no assigned license.");
            await DomainRules.LockLicense(db, licenseId, ct);
            var license = await db.Licenses.SingleAsync(x => x.Id == licenseId, ct);
            await db.Entry(license).ReloadAsync(ct);
            await DomainRules.Assign(db, device, license, ct);
            lease = signer.Sign(device, license);
            activation.Status = ActivationCodeStatus.Consumed; activation.ConsumedAt = DateTime.UtcNow;
        }
        await db.SaveChangesAsync(ct); await tx.CommitAsync(ct);
        return new(lease is not null ? "approved" : activation.Status.ToString().ToLowerInvariant(), lease);
    }
    public async Task<LeaseResponse> HeartbeatAsync(Guid installationId, HeartbeatRequest request, CancellationToken ct = default)
    {
        if (request is null) throw new DomainException(400, "invalid_body", "Heartbeat body is required.");
        var sessionId = Required(request.SessionId, 64);
        ValidateMemory(request.MemoryBytes);
        var exit = Sanitizer.CleanDiagnostic(request.ExitReason ?? request.LikelyExitReason, 256);
        var appVersion = Sanitizer.Clean(request.AppVersion, 64);
        var now = DateTime.UtcNow;
        var started = ValidateTimestamp(request.StartedAt, now, 30);
        var ended = request.EndedAt is null ? (DateTime?)null : ValidateTimestamp(request.EndedAt, now, 30);
        await using var tx = await db.Database.BeginTransactionAsync(DomainRules.Isolation(db), ct);
        await DomainRules.LockDevice(db, installationId, ct);
        var device = await DomainRules.Device(db, installationId, ct);
        device.LastSeenAt = now;
        if (appVersion.Length > 0) device.AppVersion = appVersion;
        var session = await db.DeviceSessions.SingleOrDefaultAsync(x => x.InstallationId == installationId && x.ClientSessionId == sessionId, ct);
        if (session is null) { session = new DeviceSession { InstallationId = installationId, ClientSessionId = sessionId, StartedAt = started }; db.DeviceSessions.Add(session); }
        if (ended < session.StartedAt) throw new DomainException(400, "invalid_time", "Session end cannot precede start.");
        session.LastHeartbeatAt = now; session.MemoryBytes = request.MemoryBytes; session.AppVersion = device.AppVersion;
        if (exit.Length > 0) session.ExitReason = exit;
        if (ended.HasValue || exit.Length > 0) session.EndedAt = ended ?? now;
        string? lease = null; string status = "unlicensed";
        if (device.LicenseId is Guid licenseId)
        {
            await DomainRules.LockLicense(db, licenseId, ct);
            var license = await db.Licenses.SingleAsync(x => x.Id == licenseId, ct);
            await db.Entry(license).ReloadAsync(ct);
            status = license.Status.ToString().ToLowerInvariant();
            if (license.ValidUntil <= now) status = "expired";
            else if (license.ValidFrom > now) status = "not_yet_valid";
            else if (license.Status == LicenseStatus.Active) lease = signer.Sign(device, license);
        }
        await db.SaveChangesAsync(ct); await tx.CommitAsync(ct);
        return new(lease, status);
    }
    public async Task<LeaseResponse> GetEntitlementAsync(Guid installationId, CancellationToken ct = default)
    {
        await using var tx = await db.Database.BeginTransactionAsync(DomainRules.Isolation(db), ct);
        await DomainRules.LockDevice(db, installationId, ct);
        var device = await db.Installations.SingleOrDefaultAsync(x => x.Id == installationId, ct) ?? throw new DomainException(401, "invalid_device", "Device authentication failed.");
        await db.Entry(device).ReloadAsync(ct);
        if (device.Status != InstallationStatus.Active) return new(null, "installation_disabled");
        if (device.LicenseId is not Guid licenseId) return new(null, "unlicensed");
        await DomainRules.LockLicense(db, licenseId, ct);
        var license = await db.Licenses.SingleAsync(x => x.Id == licenseId, ct);
        await db.Entry(license).ReloadAsync(ct);
        var now = DateTime.UtcNow;
        var status = license.Status.ToString().ToLowerInvariant();
        string? lease = null;
        if (license.ValidUntil <= now) status = "expired";
        else if (license.ValidFrom > now) status = "not_yet_valid";
        else if (license.Status == LicenseStatus.Active) lease = signer.Sign(device, license);
        await db.SaveChangesAsync(ct); await tx.CommitAsync(ct);
        return new(lease, status);
    }
    public async Task DiagnosticsAsync(Guid installationId, DiagnosticsRequest request, CancellationToken ct = default)
    {
        if (request?.Events is null || request.Events.Count is < 1 or > 50 || request.Events.Any(x => x is null))
            throw new DomainException(400, "invalid_batch", "Provide between 1 and 50 diagnostic events.");
        var now = DateTime.UtcNow;
        // Materialize all validated events before changing the context: rejected batches are atomic.
        var rows = request.Events.Select(item =>
        {
            ValidateMemory(item.MemoryBytes);
            return new DiagnosticEvent
            {
                InstallationId = installationId,
                EventId = item.EventId ?? Guid.NewGuid(),
                CreatedAt = now,
                OccurredAt = ValidateTimestamp(item.OccurredAt, now, 30),
                Level = DiagnosticRequired(item.Severity ?? item.Level, 16),
                Category = DiagnosticRequired(item.Category, 64),
                Message = DiagnosticRequired(item.Message, 2048),
                SessionId = DiagnosticText(item.SessionId, 64),
                StackTrace = DiagnosticText(item.StackTrace, 4096),
                ChannelName = DiagnosticText(item.ChannelName, 128),
                SourceType = DiagnosticText(item.SourceType, 32),
                Container = DiagnosticText(item.Container, 32),
                VideoCodec = DiagnosticText(item.VideoCodec, 32),
                AudioCodec = DiagnosticText(item.AudioCodec, 32),
                MemoryBytes = item.MemoryBytes,
                AppVersion = DiagnosticText(item.AppVersion, 64),
                DeviceVersion = DiagnosticText(item.DeviceVersion, 64)
            };
        }).ToList();
        await using var tx = await db.Database.BeginTransactionAsync(DomainRules.Isolation(db), ct);
        await DomainRules.LockDevice(db, installationId, ct); await DomainRules.Device(db, installationId, ct);
        var sessions = rows.Select(x => x.SessionId).Where(x => x.Length > 0).Distinct().ToArray();
        if (sessions.Length > 0)
        {
            var owned = await db.DeviceSessions.CountAsync(x => x.InstallationId == installationId && sessions.Contains(x.ClientSessionId), ct);
            if (owned != sessions.Length) throw new DomainException(400, "invalid_session", "A diagnostic session does not belong to this installation.");
        }
        var eventIds = rows.Select(x => x.EventId).ToArray();
        if (eventIds.Any(x => x == Guid.Empty)) throw new DomainException(400, "invalid_event", "Event UUID cannot be empty.");
        var existing = await db.DiagnosticEvents.Where(x => x.InstallationId == installationId && eventIds.Contains(x.EventId)).Select(x => x.EventId).ToListAsync(ct);
        db.DiagnosticEvents.AddRange(rows.DistinctBy(x => x.EventId).Where(x => !existing.Contains(x.EventId)));
        await db.SaveChangesAsync(ct); await tx.CommitAsync(ct);
    }
    private static string Required(string? value, int maximum)
    {
        if (string.IsNullOrWhiteSpace(value) || value.Length > maximum) throw new DomainException(400, "invalid_metadata", "Required metadata is missing or too long.");
        return Sanitizer.Clean(value, maximum);
    }
    private static string DiagnosticRequired(string? value, int maximum)
    {
        if (string.IsNullOrWhiteSpace(value)) throw new DomainException(400, "invalid_diagnostic", "Required diagnostic field is missing.");
        return DiagnosticText(value, maximum);
    }
    private static string DiagnosticText(string? value, int maximum)
    {
        Sanitizer.ValidateDiagnostic(value);
        return Sanitizer.CleanDiagnostic(value, maximum);
    }
    private static void ValidateMemory(long? value)
    {
        if (value is < 0 or > 1099511627776) throw new DomainException(400, "invalid_memory", "Memory must be between zero and one terabyte.");
    }
    private static DateTime ValidateTimestamp(DateTime? value, DateTime now, int pastDays)
    {
        if (value is null) return now;
        var utc = DomainRules.Utc(value.Value);
        if (utc < now.AddDays(-pastDays) || utc > now.AddMinutes(5)) throw new DomainException(400, "invalid_time", "Timestamp is outside the accepted UTC window.");
        return utc;
    }
}
