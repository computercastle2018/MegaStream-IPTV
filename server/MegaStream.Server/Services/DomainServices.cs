using System.Data;
using System.Security.Cryptography;
using System.Text;
using MegaStream.Server.Data;
using MegaStream.Server.Models;
using Microsoft.EntityFrameworkCore;

namespace MegaStream.Server.Services;

public sealed class DomainException(int statusCode, string code, string message) : Exception(message)
{
    public int StatusCode { get; } = statusCode;
    public string Code { get; } = code;
}
public record CreatedLicense(License License, string FullKey);
public interface IAdminService
{
    Task<CreatedLicense> CreateLicenseAsync(string label, DateTime validFrom, DateTime validUntil, int maxInstallations, CancellationToken ct = default);
    Task SetLicenseStatusAsync(Guid licenseId, LicenseStatus status, CancellationToken ct = default);
    Task UpdateLicenseLimitsAsync(Guid licenseId, DateTime validUntil, int maxInstallations, CancellationToken ct = default);
    Task ApproveActivationAsync(Guid activationId, Guid licenseId, CancellationToken ct = default);
    Task AssignInstallationLicenseAsync(Guid installationId, Guid licenseId, CancellationToken ct = default);
    Task RevokeInstallationAsync(Guid installationId, CancellationToken ct = default);
    Task SetOfflineGraceAsync(Guid licenseId, int offlineGraceDays, CancellationToken ct = default);
    Task SetDevicePolicyAsync(Guid installationId, string kioskMode, bool allowLocalExit, string actorId, CancellationToken ct = default);
}
public static class Secrets
{
    public static string Hash(string value) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(value)));
    public static string RandomToken() => Convert.ToBase64String(RandomNumberGenerator.GetBytes(32)).TrimEnd('=').Replace('+', '-').Replace('/', '_');
    public static string NormalizeKey(string? key)
    {
        if (string.IsNullOrWhiteSpace(key) || key.Length > 256) throw new DomainException(400, "invalid_key", "A license key is required.");
        var normalized = new string(key.Where(c => c != '-' && !char.IsWhiteSpace(c)).ToArray()).ToUpperInvariant();
        if (normalized.Length != 64 || normalized.Any(c => !Uri.IsHexDigit(c))) throw new DomainException(400, "invalid_key", "Invalid license key format.");
        return normalized;
    }
}
// No-op UPDATE acquires a database write lock until transaction commit on both MySQL and SQLite.
// Every assignment takes device -> activation (if any) -> license locks, never in reverse order.
internal static class DomainRules
{
    public static IsolationLevel Isolation(AppDbContext db) => db.Database.ProviderName?.Contains("MySql", StringComparison.OrdinalIgnoreCase) == true ? IsolationLevel.ReadCommitted : IsolationLevel.Serializable;
    public static Task<int> LockDevice(AppDbContext db, Guid id, CancellationToken ct) => db.Database.ExecuteSqlInterpolatedAsync($"UPDATE Installations SET Id = Id WHERE Id = {id}", ct);
    public static Task<int> LockLicense(AppDbContext db, Guid id, CancellationToken ct) => db.Database.ExecuteSqlInterpolatedAsync($"UPDATE Licenses SET Id = Id WHERE Id = {id}", ct);
    public static Task<int> LockCode(AppDbContext db, Guid id, CancellationToken ct) => db.Database.ExecuteSqlInterpolatedAsync($"UPDATE ActivationCodes SET Id = Id WHERE Id = {id}", ct);
    public static void ValidLicense(License license)
    {
        var now = DateTime.UtcNow;
        if (license.Status != LicenseStatus.Active || license.ValidFrom > now || license.ValidUntil <= now)
            throw new DomainException(403, "license_unavailable", "The license is not currently active.");
    }
    public static void ValidateLimits(DateTime from, DateTime until, int maximum)
    {
        from = Utc(from);
        until = Utc(until);
        // Android consumes whole Unix seconds, so distinct subsecond timestamps may still be an empty interval.
        if (from < DateTime.UnixEpoch || maximum is < 1 or > 10000 ||
            new DateTimeOffset(until).ToUnixTimeSeconds() <= new DateTimeOffset(from).ToUnixTimeSeconds())
            throw new DomainException(400, "invalid_limits", "Validity must start at or after the Unix epoch and span distinct Unix seconds; installation limits must be valid.");
    }
    public static DateTime Utc(DateTime value) => value.Kind == DateTimeKind.Local ? value.ToUniversalTime() : DateTime.SpecifyKind(value, DateTimeKind.Utc);
    public static async Task<Installation> Device(AppDbContext db, Guid id, CancellationToken ct)
    {
        var device = await db.Installations.SingleOrDefaultAsync(x => x.Id == id, ct) ?? throw new DomainException(401, "invalid_device", "Device authentication failed.");
        await db.Entry(device).ReloadAsync(ct);
        if (device.Status != InstallationStatus.Active) throw new DomainException(403, "installation_disabled", "Installation is revoked.");
        return device;
    }
    public static async Task Assign(AppDbContext db, Installation device, License license, CancellationToken ct)
    {
        ValidLicense(license);
        if (device.LicenseId == license.Id) return;
        if (device.LicenseId != null) throw new DomainException(409, "already_licensed", "Installation is already assigned to a license.");
        var now = DateTime.UtcNow;
        var active = await db.Installations.CountAsync(x => x.LicenseId == license.Id && (x.Status == InstallationStatus.Active || x.LastLeaseExpiresAt > now), ct);
        if (active >= license.MaxInstallations) throw new DomainException(409, "capacity_exceeded", "The license installation limit has been reached.");
        device.LicenseId = license.Id; device.ActivatedAt = DateTime.UtcNow;
    }
}
public sealed class AdminService(AppDbContext db, SecretHasher hasher) : IAdminService
{
    public async Task AssignInstallationLicenseAsync(Guid installationId, Guid licenseId, CancellationToken ct = default)
    {
        await using var tx = await db.Database.BeginTransactionAsync(DomainRules.Isolation(db), ct);
        await DomainRules.LockDevice(db, installationId, ct);
        var device = await db.Installations.SingleOrDefaultAsync(x => x.Id == installationId, ct)
            ?? throw new DomainException(404, "not_found", "Installation not found.");
        await db.Entry(device).ReloadAsync(ct);
        if (device.Status != InstallationStatus.Active)
            throw new DomainException(403, "installation_disabled", "Installation is revoked.");
        var codes = await db.ActivationCodes.Where(x => x.InstallationId == installationId &&
            (x.Status == ActivationCodeStatus.Pending || x.Status == ActivationCodeStatus.Approved))
            .OrderBy(x => x.Id).ToListAsync(ct);
        foreach (var code in codes)
        {
            await DomainRules.LockCode(db, code.Id, ct);
            await db.Entry(code).ReloadAsync(ct);
        }
        await DomainRules.LockLicense(db, licenseId, ct);
        var license = await db.Licenses.SingleOrDefaultAsync(x => x.Id == licenseId, ct)
            ?? throw new DomainException(404, "not_found", "License not found.");
        await db.Entry(license).ReloadAsync(ct);
        await DomainRules.Assign(db, device, license, ct);
        // Let an already-open activation screen receive its lease on the next authenticated poll.
        foreach (var code in codes.Where(x => x.ExpiresAt > DateTime.UtcNow &&
            (x.Status == ActivationCodeStatus.Pending || x.Status == ActivationCodeStatus.Approved)))
        {
            code.LicenseId = licenseId;
            code.Status = ActivationCodeStatus.Approved;
        }
        await db.SaveChangesAsync(ct);
        await tx.CommitAsync(ct);
    }
    public async Task<CreatedLicense> CreateLicenseAsync(string label, DateTime validFrom, DateTime validUntil, int maxInstallations, CancellationToken ct = default)
    {
        validFrom = DomainRules.Utc(validFrom); validUntil = DomainRules.Utc(validUntil);
        DomainRules.ValidateLimits(validFrom, validUntil, maxInstallations);
        var raw = Convert.ToHexString(RandomNumberGenerator.GetBytes(32));
        var license = new License { KeyHash = hasher.Hash("license", raw), KeyLast4 = raw[^4..], Label = Sanitizer.Clean(label, 128), ValidFrom = validFrom, ValidUntil = validUntil, MaxInstallations = maxInstallations };
        db.Licenses.Add(license); await db.SaveChangesAsync(ct);
        return new(license, string.Join("-", Enumerable.Range(0, 8).Select(i => raw.Substring(i * 8, 8))));
    }
    public async Task SetLicenseStatusAsync(Guid licenseId, LicenseStatus status, CancellationToken ct = default)
    {
        if (!Enum.IsDefined(status)) throw new DomainException(400, "invalid_status", "Invalid status.");
        await using var tx = await db.Database.BeginTransactionAsync(DomainRules.Isolation(db), ct);
        await DomainRules.LockLicense(db, licenseId, ct);
        var license = await db.Licenses.SingleOrDefaultAsync(x => x.Id == licenseId, ct) ?? throw new DomainException(404, "not_found", "License not found.");
        await db.Entry(license).ReloadAsync(ct);
        license.Status = status; license.UpdatedAt = DateTime.UtcNow; license.Revision++;
        await db.SaveChangesAsync(ct); await tx.CommitAsync(ct);
    }
    public async Task UpdateLicenseLimitsAsync(Guid licenseId, DateTime validUntil, int maxInstallations, CancellationToken ct = default)
    {
        await using var tx = await db.Database.BeginTransactionAsync(DomainRules.Isolation(db), ct);
        await DomainRules.LockLicense(db, licenseId, ct);
        var license = await db.Licenses.SingleOrDefaultAsync(x => x.Id == licenseId, ct) ?? throw new DomainException(404, "not_found", "License not found.");
        await db.Entry(license).ReloadAsync(ct);
        validUntil = DomainRules.Utc(validUntil); DomainRules.ValidateLimits(license.ValidFrom, validUntil, maxInstallations);
        var now = DateTime.UtcNow;
        var used = await db.Installations.CountAsync(x => x.LicenseId == licenseId && (x.Status == InstallationStatus.Active || x.LastLeaseExpiresAt > now), ct);
        if (maxInstallations < used) throw new DomainException(409, "capacity_in_use", "Limit cannot be lower than active installations.");
        license.ValidUntil = validUntil; license.MaxInstallations = maxInstallations; license.UpdatedAt = DateTime.UtcNow; license.Revision++;
        await db.SaveChangesAsync(ct); await tx.CommitAsync(ct);
    }
    public async Task ApproveActivationAsync(Guid activationId, Guid licenseId, CancellationToken ct = default)
    {
        await using var tx = await db.Database.BeginTransactionAsync(DomainRules.Isolation(db), ct);
        await DomainRules.LockCode(db, activationId, ct);
        var code = await db.ActivationCodes.SingleOrDefaultAsync(x => x.Id == activationId, ct) ?? throw new DomainException(404, "not_found", "Activation not found.");
        await db.Entry(code).ReloadAsync(ct);
        if (code.Status != ActivationCodeStatus.Pending || code.ExpiresAt <= DateTime.UtcNow) throw new DomainException(409, "activation_unavailable", "Activation is expired or no longer pending.");
        await DomainRules.LockLicense(db, licenseId, ct);
        var license = await db.Licenses.SingleOrDefaultAsync(x => x.Id == licenseId, ct) ?? throw new DomainException(404, "not_found", "License not found.");
        await db.Entry(license).ReloadAsync(ct);
        DomainRules.ValidLicense(license);
        code.LicenseId = licenseId; code.Status = ActivationCodeStatus.Approved;
        await db.SaveChangesAsync(ct); await tx.CommitAsync(ct);
    }
    public async Task SetOfflineGraceAsync(Guid licenseId, int offlineGraceDays, CancellationToken ct = default)
    {
        if (offlineGraceDays is < 1 or > 3) throw new DomainException(400, "invalid_grace", "Offline grace must be between one and three days.");
        await using var tx = await db.Database.BeginTransactionAsync(DomainRules.Isolation(db), ct);
        await DomainRules.LockLicense(db, licenseId, ct);
        var license = await db.Licenses.SingleOrDefaultAsync(x => x.Id == licenseId, ct) ?? throw new DomainException(404, "not_found", "License not found.");
        await db.Entry(license).ReloadAsync(ct);
        license.OfflineGraceDays = offlineGraceDays; license.UpdatedAt = DateTime.UtcNow; license.Revision++;
        await db.SaveChangesAsync(ct); await tx.CommitAsync(ct);
    }
    public async Task SetDevicePolicyAsync(Guid installationId, string kioskMode, bool allowLocalExit, string actorId, CancellationToken ct = default)
    {
        if (kioskMode is not ("off" or "playback" or "always"))
            throw new DomainException(400, "invalid_device_policy", "Kiosk mode must be off, playback or always.");
        if (string.IsNullOrWhiteSpace(actorId) || actorId.Length > 128 || actorId.Any(char.IsControl))
            throw new DomainException(400, "invalid_actor", "A bounded administrator identifier is required.");
        Sanitizer.ValidateDiagnostic(actorId);
        await using var transaction = await db.Database.BeginTransactionAsync(DomainRules.Isolation(db), ct);
        await DomainRules.LockDevice(db, installationId, ct);
        var installation = await db.Installations.SingleOrDefaultAsync(x => x.Id == installationId, ct)
            ?? throw new DomainException(404, "not_found", "Installation not found.");
        await db.Entry(installation).ReloadAsync(ct);
        db.DevicePolicyAudits.Add(new DevicePolicyAudit
        {
            InstallationId = installationId, ActorId = actorId, CreatedAt = DateTime.UtcNow,
            OldKioskMode = installation.KioskMode, NewKioskMode = kioskMode,
            OldAllowLocalExit = installation.AllowLocalExit, NewAllowLocalExit = allowLocalExit
        });
        installation.KioskMode = kioskMode;
        installation.AllowLocalExit = allowLocalExit;
        await db.SaveChangesAsync(ct);
        await transaction.CommitAsync(ct);
    }
    public async Task RevokeInstallationAsync(Guid installationId, CancellationToken ct = default)
    {
        await using var tx = await db.Database.BeginTransactionAsync(DomainRules.Isolation(db), ct);
        await DomainRules.LockDevice(db, installationId, ct);
        var device = await db.Installations.SingleOrDefaultAsync(x => x.Id == installationId, ct) ?? throw new DomainException(404, "not_found", "Installation not found.");
        await db.Entry(device).ReloadAsync(ct);
        device.Status = InstallationStatus.Revoked; device.RevokedAt = DateTime.UtcNow;
        await db.SaveChangesAsync(ct); await tx.CommitAsync(ct);
    }
}
