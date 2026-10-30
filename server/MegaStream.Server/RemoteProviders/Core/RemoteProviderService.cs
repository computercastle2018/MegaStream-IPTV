using System.Data;
using System.Data.Common;
using System.Security.Cryptography;
using System.ComponentModel.DataAnnotations;
using System.Text.Json;
using MegaStream.Server.Data;
using MegaStream.Server.Models;
using MegaStream.Server.Services;
using Microsoft.EntityFrameworkCore;

namespace MegaStream.Server.RemoteProviders.Core;

public sealed class RemoteProviderService(AppDbContext db, ProviderPayloadCipher cipher, SecretHasher hasher,
    LocalSubscriptionCredentialsStore localCredentials) : IRemoteProviderService
{
    public Task<ImportLocalSubscriptionsResult> ImportLocalSubscriptionsAsync(Guid installationId, DateTime reportedSince, CancellationToken ct = default) =>
        Serialized(async _ =>
        {
            await LicensedInstallation(installationId, ct);
            var device = await db.Installations.AsNoTracking().SingleAsync(x => x.Id == installationId, ct);
            if (device.LastSeenAt < reportedSince)
                throw new DomainException(409, "subscription_device_offline", "An online licensed device is required.");
            if (device.LocalSubscriptionsJson is null || device.LocalSubscriptionsReportedAt is not { } reportedAt)
                throw new DomainException(409, "subscription_report_missing", "No local subscription report is available.");
            if (reportedAt < reportedSince)
                throw new DomainException(409, "subscription_report_stale", "The local subscription report is too old.");
            try
            {
                var summaries = LocalSubscriptionCredentialsStore.Summaries(device.LocalSubscriptionsJson);
                var credentialIds = LocalSubscriptionCredentialsStore.CredentialIds(device.LocalSubscriptionsJson);
                if (summaries.Count > 100 || summaries.Where(x => x is not null).Select(x => x!.LocalId).Distinct().Count() != summaries.Count)
                    throw new JsonException();
                var saved = await db.Set<RemoteProviderProfile>().Where(x => x.SourceInstallationId == installationId)
                    .ToDictionaryAsync(x => x.SourceLocalId!.Value, ct);
                var names = await db.Set<RemoteProviderProfile>().AsNoTracking().Select(x => new { x.Id, x.DisplayName })
                    .ToDictionaryAsync(x => x.Id, x => x.DisplayName, ct);
                var created = 0; var updated = 0; var alreadySaved = 0; var unavailable = 0;
                foreach (var summary in summaries)
                {
                    if (summary is null || !Validator.TryValidateObject(summary, new ValidationContext(summary), null, true) ||
                        !credentialIds.Contains(summary.LocalId)) { unavailable++; continue; }
                    try
                    {
                        var request = new ReplaceRemoteProviderProfile
                        {
                            DisplayName = summary.Name,
                            Type = LocalSubscriptionCredentialsStore.ProviderType(summary.Type),
                            Configuration = await localCredentials.RevealAsync(installationId, summary.LocalId, ct)
                        };
                        ConfigurationValidator.Validate(request);
                        request.Configuration.HttpHeaders = request.Configuration.HttpHeaders?.OrderBy(x => x.Key, StringComparer.Ordinal)
                            .ToDictionary(x => x.Key, x => x.Value);
                        var hash = hasher.Hash("provider-source-content", JsonSerializer.Serialize(new
                        {
                            summary.Name, summary.Type, summary.Enabled, summary.Status, summary.StartedAt, summary.ExpiresAt,
                            summary.MaxConnections, request.Configuration
                        }));
                        if (saved.TryGetValue(summary.LocalId, out var profile))
                        {
                            if (profile.SourceContentHash == hash || profile.DeletedAt is not null || profile.Status != "active")
                            { alreadySaved++; continue; }
                            var otherNames = names.Where(x => x.Key != profile.Id).Select(x => x.Value).ToHashSet(StringComparer.OrdinalIgnoreCase);
                            profile.DisplayName = AvailableSourceName(request.DisplayName, otherNames);
                            profile.Type = request.Type; profile.Revision = await NextRevision(ct);
                            profile.UpdatedAt = DateTime.UtcNow; profile.KeyVersion = cipher.KeyVersion;
                            profile.EncryptedPayload = cipher.Encrypt(profile.Id, profile.Type, profile.Revision, request.Configuration);
                            var assignments = await db.Set<RemoteProviderAssignment>().Where(x => x.ProfileId == profile.Id && x.RevokedAt == null).ToListAsync(ct);
                            foreach (var assignment in assignments) assignment.Revision = await NextRevision(ct);
                            updated++;
                        }
                        else
                        {
                            request.DisplayName = AvailableSourceName(request.DisplayName, names.Values.ToHashSet(StringComparer.OrdinalIgnoreCase));
                            profile = AddProfile(request, await NextRevision(ct));
                            profile.SourceInstallationId = installationId; profile.SourceLocalId = summary.LocalId;
                            saved.Add(summary.LocalId, profile);
                            created++;
                        }
                        names[profile.Id] = profile.DisplayName;
                        profile.SourceContentHash = hash; profile.SourceStatus = summary.Status; profile.SourceEnabled = summary.Enabled;
                    }
                    catch (DomainException error) when (error.Code is "subscription_credentials_unavailable" or
                        "provider_configuration_unavailable" or "invalid_provider_configuration" or "invalid_metadata")
                    { unavailable++; }
                }
                return new ImportLocalSubscriptionsResult(created, updated, alreadySaved, unavailable, DomainRules.Utc(reportedAt));
            }
            catch (JsonException)
            {
                throw new DomainException(409, "subscription_report_invalid", "The local subscription report is unavailable or invalid.");
            }
        }, false, ct);

    private static string AvailableSourceName(string source, HashSet<string> names)
    {
        var candidate = source;
        for (var number = 2; !names.Add(candidate); number++)
        {
            var suffix = $" ({number})";
            var length = Math.Min(source.Length, 128 - suffix.Length);
            if (length > 0 && char.IsHighSurrogate(source[length - 1])) length--;
            candidate = source[..length] + suffix;
        }
        return candidate;
    }

    public Task<GeneratedProviderCode> CreateRedemptionCodeAsync(Guid profileId, CancellationToken ct = default) =>
        Serialized(async _ =>
        {
            Active(await Profile(profileId, ct));
            var code = Convert.ToHexString(RandomNumberGenerator.GetBytes(12));
            var expiresAt = DateTime.UtcNow.AddHours(24);
            db.Set<ProviderRedemptionCode>().Add(new ProviderRedemptionCode
            {
                CodeHash = hasher.Hash("provider-redemption", code), ProfileId = profileId, ExpiresAt = expiresAt
            });
            return new GeneratedProviderCode { Code = code, ExpiresAt = expiresAt };
        }, false, ct);

    public async Task RedeemAsync(Guid installationId, string code, CancellationToken ct = default)
    {
        if (code is null || code.Length > 64) throw InvalidRedemption();
        var normalized = code.Trim().ToUpperInvariant();
        if (normalized.Length != 24 || normalized.Any(c => !char.IsAsciiHexDigit(c))) throw InvalidRedemption();
        var hash = hasher.Hash("provider-redemption", normalized);
        await Serialized(async _ =>
        {
            await LicensedInstallation(installationId, ct);
            var redemption = await db.Set<ProviderRedemptionCode>().SingleOrDefaultAsync(x => x.CodeHash == hash, ct);
            if (redemption is null) throw InvalidRedemption();
            if (redemption.RedeemedInstallationId is { } owner)
            {
                if (owner != installationId) throw InvalidRedemption();
                return true;
            }
            var profile = await db.Set<RemoteProviderProfile>().AsNoTracking().SingleOrDefaultAsync(x => x.Id == redemption.ProfileId, ct);
            if (redemption.ExpiresAt <= DateTime.UtcNow || profile is null || profile.Status != "active" || profile.DeletedAt is not null)
                throw InvalidRedemption();
            await Assign(redemption.ProfileId, installationId, new AssignRemoteProvider { Policy = "auto_enabled" }, await NextRevision(ct), ct);
            redemption.RedeemedInstallationId = installationId;
            redemption.RedeemedAt = DateTime.UtcNow;
            return true;
        }, false, ct);
    }

    public Task<RemoteProviderProfileMetadata> CreateProfileAsync(ReplaceRemoteProviderProfile request, CancellationToken ct = default,
        Guid? installationId = null, string policy = "auto_enabled")
    {
        ConfigurationValidator.Validate(request);
        if (installationId.HasValue && (installationId == Guid.Empty || policy is not ("optional" or "auto_enabled")))
            throw new DomainException(400, "invalid_provider_policy", "Provider assignment policy is invalid.");
        return Serialized(async revision =>
        {
            if (installationId.HasValue) await ActiveInstallation(installationId.Value, ct);
            var profile = AddProfile(request, revision);
            if (installationId.HasValue)
                db.Set<RemoteProviderAssignment>().Add(new RemoteProviderAssignment
                {
                    Id = Guid.NewGuid(), ProfileId = profile.Id, InstallationId = installationId.Value,
                    Policy = policy, Enabled = true, AssignedAt = profile.CreatedAt, Revision = revision
                });
            return Metadata(profile);
        }, true, ct);
    }

    private RemoteProviderProfile AddProfile(ReplaceRemoteProviderProfile request, long revision)
    {
        var now = DateTime.UtcNow;
        var profile = new RemoteProviderProfile { Id = Guid.NewGuid(), DisplayName = request.DisplayName, Type = request.Type,
            Revision = revision, CreatedAt = now, UpdatedAt = now, KeyVersion = cipher.KeyVersion };
        profile.EncryptedPayload = cipher.Encrypt(profile.Id, profile.Type, revision, request.Configuration);
        db.Set<RemoteProviderProfile>().Add(profile);
        return profile;
    }

    public Task<RemoteProviderProfileMetadata> UpdateProfileAsync(Guid profileId, ReplaceRemoteProviderProfile request, CancellationToken ct = default)
    {
        ConfigurationValidator.Validate(request);
        return Serialized(async revision =>
        {
            var profile = await Profile(profileId, ct);
            Active(profile);
            // Complete replacement never reads or retains previous credentials.
            profile.DisplayName = request.DisplayName; profile.Type = request.Type;
            profile.Revision = revision; profile.UpdatedAt = DateTime.UtcNow; profile.KeyVersion = cipher.KeyVersion;
            profile.EncryptedPayload = cipher.Encrypt(profile.Id, profile.Type, revision, request.Configuration);
            var assignments = await db.Set<RemoteProviderAssignment>().Where(x => x.ProfileId == profileId && x.RevokedAt == null).ToListAsync(ct);
            foreach (var assignment in assignments) assignment.Revision = await NextRevision(ct);
            return Metadata(profile);
        }, true, ct);
    }

    public async Task RevokeProfileAsync(Guid profileId, CancellationToken ct = default)
    {
        await Serialized(async revision =>
        {
            var profile = await Profile(profileId, ct);
            if (profile.DeletedAt is not null) return true;
            profile.Status = "revoked"; profile.DeletedAt = profile.UpdatedAt = DateTime.UtcNow; profile.Revision = revision;
            // A tombstone never needs ciphertext; erase it rather than retaining an obsolete AAD binding.
            profile.EncryptedPayload = Array.Empty<byte>();
            var assignments = await db.Set<RemoteProviderAssignment>().Where(x => x.ProfileId == profileId && x.RevokedAt == null).ToListAsync(ct);
            foreach (var assignment in assignments)
            {
                assignment.RevokedAt = profile.DeletedAt; assignment.Enabled = false; assignment.Revision = await NextRevision(ct);
            }
            return true;
        }, true, ct);
    }

    public async Task<IReadOnlyList<RemoteProviderProfileMetadata>> ListProfilesAsync(CancellationToken ct = default) =>
        await SafeRead(async () => (IReadOnlyList<RemoteProviderProfileMetadata>)await db.Set<RemoteProviderProfile>().AsNoTracking()
            .OrderBy(x => x.DisplayName).Select(x => new RemoteProviderProfileMetadata { Id = x.Id, DisplayName = x.DisplayName,
                Type = x.Type, Status = x.Status, Revision = x.Revision, CreatedAt = x.CreatedAt, UpdatedAt = x.UpdatedAt, DeletedAt = x.DeletedAt,
                SourceStatus = x.SourceStatus, SourceEnabled = x.SourceEnabled }).ToListAsync(ct));

    public Task<RemoteProviderProfileMetadata> GetProfileAsync(Guid profileId, CancellationToken ct = default) => SafeRead(async () =>
        await db.Set<RemoteProviderProfile>().AsNoTracking().Where(x => x.Id == profileId)
            .Select(x => new RemoteProviderProfileMetadata { Id = x.Id, DisplayName = x.DisplayName, Type = x.Type, Status = x.Status,
                Revision = x.Revision, CreatedAt = x.CreatedAt, UpdatedAt = x.UpdatedAt, DeletedAt = x.DeletedAt,
                SourceStatus = x.SourceStatus, SourceEnabled = x.SourceEnabled }).SingleOrDefaultAsync(ct) ?? throw NotFound());

    public Task<RemoteProviderConfiguration> RevealConfigurationAsync(Guid profileId, CancellationToken ct = default) => SafeRead(async () =>
    {
        var profile = await db.Set<RemoteProviderProfile>().AsNoTracking().SingleOrDefaultAsync(x => x.Id == profileId, ct) ?? throw NotFound();
        Active(profile);
        return cipher.Decrypt(profile);
    });

    public Task<RemoteProviderAssignment> AssignAsync(Guid profileId, Guid installationId, AssignRemoteProvider request, CancellationToken ct = default)
    {
        if (request is null || request.Policy is not ("optional" or "auto_enabled" or "required") || (request.Policy == "required" && !request.Enabled))
            throw new DomainException(400, "invalid_provider_policy", "Provider assignment policy is invalid.");
        return Serialized(revision => Assign(profileId, installationId, request, revision, ct), true, ct);
    }

    private async Task<RemoteProviderAssignment> Assign(Guid profileId, Guid installationId, AssignRemoteProvider request, long revision, CancellationToken ct)
    {
        await ActiveInstallation(installationId, ct);
        Active(await Profile(profileId, ct));
        var assignment = await db.Set<RemoteProviderAssignment>().SingleOrDefaultAsync(x => x.ProfileId == profileId && x.InstallationId == installationId, ct);
        if (assignment is null)
        {
            assignment = new RemoteProviderAssignment { Id = Guid.NewGuid(), ProfileId = profileId, InstallationId = installationId };
            db.Set<RemoteProviderAssignment>().Add(assignment);
        }
        assignment.Policy = request.Policy; assignment.Enabled = request.Enabled; assignment.RevokedAt = null;
        assignment.AssignedAt = DateTime.UtcNow; assignment.Revision = revision;
        return assignment;
    }

    public async Task RevokeAssignmentAsync(Guid assignmentId, CancellationToken ct = default)
    {
        await Serialized(async revision =>
        {
            var assignment = await db.Set<RemoteProviderAssignment>().SingleOrDefaultAsync(x => x.Id == assignmentId, ct) ?? throw NotFound();
            if (assignment.RevokedAt is null)
            {
                assignment.RevokedAt = DateTime.UtcNow; assignment.Enabled = false; assignment.Revision = revision;
            }
            return true;
        }, true, ct);
    }

    public Task<IReadOnlyList<RemoteProviderAssignment>> ListAssignmentsAsync(Guid installationId, CancellationToken ct = default) =>
        SafeRead(async () => (IReadOnlyList<RemoteProviderAssignment>)await db.Set<RemoteProviderAssignment>().AsNoTracking()
            .Where(x => x.InstallationId == installationId).OrderBy(x => x.Revision).ToListAsync(ct));
    public Task<IReadOnlyList<ProviderAssignmentReport>> ListReportsAsync(Guid installationId, CancellationToken ct = default) =>
        SafeRead(async () => (IReadOnlyList<ProviderAssignmentReport>)await db.Set<ProviderAssignmentReport>().AsNoTracking()
            .Where(x => x.InstallationId == installationId).ToListAsync(ct));

    public Task<RemoteProviderDeviceResult> GetDeviceAsync(Guid installationId, long afterRevision = 0, CancellationToken ct = default)
    {
        if (afterRevision < 0) throw new DomainException(400, "invalid_revision", "Revision is invalid.");
        return Serialized(async watermark =>
        {
            await LicensedInstallation(installationId, ct);
            if (afterRevision > watermark) throw new DomainException(409, "revision_ahead", "Revision is ahead of the server; perform a full synchronization.");
            var rows = await (from assignment in db.Set<RemoteProviderAssignment>().AsNoTracking()
                join profile in db.Set<RemoteProviderProfile>().AsNoTracking() on assignment.ProfileId equals profile.Id
                where assignment.InstallationId == installationId && assignment.Revision > afterRevision
                orderby assignment.Revision
                select new { Assignment = assignment, Profile = profile }).ToListAsync(ct);
            var items = rows.Select(row =>
            {
                var a = row.Assignment; var p = row.Profile;
                var revoked = !a.Enabled || a.RevokedAt is not null || p.DeletedAt is not null || p.Status != "active";
                return new RemoteProviderDeviceItem { AssignmentId = a.Id, ProfileId = p.Id, Revision = a.Revision,
                    ProfileRevision = p.Revision, Policy = a.Policy, Enabled = !revoked && a.Enabled, Revoked = revoked,
                    RevokedAt = revoked ? a.RevokedAt ?? p.DeletedAt ?? a.AssignedAt : null,
                    DisplayName = revoked ? null : p.DisplayName, Type = revoked ? null : p.Type,
                    Configuration = revoked ? null : cipher.Decrypt(p) };
            }).ToList();
            return new RemoteProviderDeviceResult { Revision = watermark, Items = items };
        }, false, ct);
    }

    public async Task ReportAsync(Guid installationId, Guid assignmentId, ReportRemoteProvider request, CancellationToken ct = default)
    {
        ValidateReport(request);
        await Serialized(async _ =>
        {
            await ActiveInstallation(installationId, ct);
            var assignment = await db.Set<RemoteProviderAssignment>().SingleOrDefaultAsync(x => x.Id == assignmentId && x.InstallationId == installationId, ct) ?? throw NotFound();
            var profile = await Profile(assignment.ProfileId, ct);
            if (!assignment.Enabled || assignment.RevokedAt is not null || profile.DeletedAt is not null || profile.Status != "active" || profile.Revision != request.ProfileRevision)
                throw new DomainException(409, "stale_provider_revision", "Provider assignment or revision is no longer current.");
            if (assignment.Policy == "required" && request.State == "disabled_by_user")
                throw new DomainException(409, "provider_required", "A required provider cannot be disabled by the user.");
            var report = await db.Set<ProviderAssignmentReport>().SingleOrDefaultAsync(x => x.AssignmentId == assignmentId, ct);
            if (report is not null && report.ProfileRevision == request.ProfileRevision && report.State == request.State && report.SafeErrorCode == request.SafeErrorCode &&
                report.ProviderReportedExpiresAt == request.ProviderReportedExpiresAt && report.ProviderReportedMaxConnections == request.ProviderReportedMaxConnections) return true;
            if (report is null)
            {
                report = new ProviderAssignmentReport { AssignmentId = assignmentId, InstallationId = installationId };
                db.Set<ProviderAssignmentReport>().Add(report);
            }
            report.ProfileRevision = request.ProfileRevision; report.State = request.State; report.SafeErrorCode = request.SafeErrorCode;
            report.ProviderReportedExpiresAt = request.ProviderReportedExpiresAt; report.ProviderReportedMaxConnections = request.ProviderReportedMaxConnections;
            report.LastReportedAt = DateTime.UtcNow;
            return true;
        }, false, ct);
    }

    private static void ValidateReport(ReportRemoteProvider request)
    {
        if (request is null || request.ProfileRevision < 1 || request.State is not ("received" or "applied" or "disabled_by_user" or "syncing" or "active" or "expired" or "error") ||
            request.SafeErrorCode is not (null or "invalid_configuration" or "authentication_failed" or "subscription_expired" or "network_unavailable" or "dns_failure" or "tls_failure" or "connection_timeout" or "http_401" or "http_403" or "http_404" or "http_429" or "server_error" or "parse_failed" or "sync_failed" or "unsupported" or "user_disabled" or "unknown") ||
            request.ProviderReportedMaxConnections is < 0 or > 100000 ||
            (request.ProviderReportedExpiresAt is DateTime expires && (expires.Year < 1970 || expires.Year > 9998 || expires.Kind != DateTimeKind.Utc)))
            throw new DomainException(400, "invalid_provider_report", "Provider report is invalid.");
    }

    private async Task LicensedInstallation(Guid id, CancellationToken ct)
    {
        await ActiveInstallation(id, ct);
        var licenseId = await db.Installations.AsNoTracking().Where(x => x.Id == id).Select(x => x.LicenseId).SingleAsync(ct);
        if (licenseId is null) throw new DomainException(403, "license_required", "An active license is required.");
        await DomainRules.LockLicense(db, licenseId.Value, ct);
        var license = await db.Licenses.AsNoTracking().SingleAsync(x => x.Id == licenseId.Value, ct);
        DomainRules.ValidLicense(license);
    }

    private static DomainException InvalidRedemption() => new(400, "invalid_subscription_code", "Subscription code is invalid or unavailable.");

    private async Task ActiveInstallation(Guid id, CancellationToken ct)
    {
        // Keep a concurrent installation revocation from overtaking this authorized operation.
        await db.Installations.Where(x => x.Id == id).ExecuteUpdateAsync(s => s.SetProperty(x => x.Status, x => x.Status), ct);
        var status = await db.Installations.AsNoTracking().Where(x => x.Id == id).Select(x => (InstallationStatus?)x.Status).SingleOrDefaultAsync(ct);
        if (status is null) throw new DomainException(401, "invalid_device", "Installation authentication failed.");
        if (status != InstallationStatus.Active) throw new DomainException(403, "installation_disabled", "Installation is disabled.");
    }
    private async Task<RemoteProviderProfile> Profile(Guid id, CancellationToken ct) =>
        await db.Set<RemoteProviderProfile>().SingleOrDefaultAsync(x => x.Id == id, ct) ?? throw NotFound();
    private static void Active(RemoteProviderProfile profile)
    {
        if (profile.Status != "active" || profile.DeletedAt is not null) throw new DomainException(409, "provider_revoked", "Provider profile is revoked.");
    }
    private static RemoteProviderProfileMetadata Metadata(RemoteProviderProfile x) => new() { Id = x.Id, DisplayName = x.DisplayName,
        Type = x.Type, Status = x.Status, Revision = x.Revision, CreatedAt = x.CreatedAt, UpdatedAt = x.UpdatedAt, DeletedAt = x.DeletedAt,
        SourceStatus = x.SourceStatus, SourceEnabled = x.SourceEnabled };
    private static DomainException NotFound() => new(404, "provider_not_found", "Provider resource was not found.");
    private static DomainException StorageUnavailable() => new(503, "provider_storage_unavailable", "Provider storage is temporarily unavailable.");

    private async Task<long> NextRevision(CancellationToken ct)
    {
        var changed = await db.Set<RemoteProviderRevisionSequence>().Where(x => x.Id == 1 && x.Revision < long.MaxValue)
            .ExecuteUpdateAsync(s => s.SetProperty(x => x.Revision, x => x.Revision + 1), ct);
        if (changed != 1) throw StorageUnavailable();
        return await db.Set<RemoteProviderRevisionSequence>().AsNoTracking().Where(x => x.Id == 1).Select(x => x.Revision).SingleAsync(ct);
    }

    // All provider writes, including reports, acquire this relational lock before reading state.
    // GET also holds it until its watermark and decrypted snapshot have been assembled. In particular,
    // a lower revision cannot commit after GET has returned a higher watermark.
    private async Task<T> Serialized<T>(Func<long, Task<T>> action, bool increment, CancellationToken ct)
    {
        for (var attempt = 0; ; attempt++)
        {
            try
            {
                DetachProviderEntities();
                var isolation = db.Database.ProviderName?.Contains("MySql", StringComparison.OrdinalIgnoreCase) == true ? IsolationLevel.ReadCommitted : IsolationLevel.Serializable;
                await using var transaction = await db.Database.BeginTransactionAsync(isolation, ct);
                long revision;
                if (increment) revision = await NextRevision(ct);
                else
                {
                    await db.Set<RemoteProviderRevisionSequence>().Where(x => x.Id == 1)
                        .ExecuteUpdateAsync(s => s.SetProperty(x => x.Revision, x => x.Revision), ct);
                    revision = await db.Set<RemoteProviderRevisionSequence>().AsNoTracking().Where(x => x.Id == 1)
                        .Select(x => (long?)x.Revision).SingleOrDefaultAsync(ct) ?? throw StorageUnavailable();
                }
                var result = await action(revision);
                await db.SaveChangesAsync(ct);
                await transaction.CommitAsync(ct);
                DetachProviderEntities();
                return result;
            }
            catch (Exception ex) when (ex is DbUpdateException or DbException)
            {
                DetachProviderEntities();
                if (attempt >= 2 || !Retryable(ex)) throw StorageUnavailable();
                await Task.Delay(25 * (attempt + 1), ct);
            }
            catch
            {
                DetachProviderEntities();
                throw;
            }
        }
    }
    private void DetachProviderEntities()
    {
        foreach (var entry in db.ChangeTracker.Entries().Where(e => e.Entity is RemoteProviderProfile or RemoteProviderAssignment or ProviderAssignmentReport or RemoteProviderRevisionSequence or ProviderRedemptionCode).ToList())
            entry.State = EntityState.Detached;
    }
    private static bool Retryable(Exception ex)
    {
        if (ex is DbUpdateConcurrencyException) return true;
        for (Exception? current = ex; current is not null; current = current.InnerException)
        {
            // Avoid parsing provider messages, which may contain sensitive parameter values.
            if (current.GetType().GetProperty("SqliteErrorCode")?.GetValue(current) is int sqlite && sqlite is 5 or 6) return true;
            if (current.GetType().GetProperty("Number")?.GetValue(current) is int mysql && mysql is 1205 or 1213) return true;
        }
        return false;
    }
    private static async Task<T> SafeRead<T>(Func<Task<T>> action)
    {
        try { return await action(); }
        catch (Exception ex) when (ex is DbUpdateException or DbException) { throw StorageUnavailable(); }
    }
}
