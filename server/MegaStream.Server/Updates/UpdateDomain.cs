using MegaStream.Server.Data;
using MegaStream.Server.Models;
using MegaStream.Server.Services;
using Microsoft.EntityFrameworkCore;

namespace MegaStream.Server.Updates;

public enum UpdateReleaseStatus { Draft, Published }

public sealed class UpdateRelease
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public long VersionCode { get; set; }
    public string VersionName { get; set; } = "";
    public string Channel { get; set; } = "stable";
    public string PackageName { get; set; } = "com.megastream.app";
    public string Abi { get; set; } = "universal";
    public int MinSdk { get; set; }
    public bool Mandatory { get; set; }
    public string StorageKey { get; set; } = "";
    public long SizeBytes { get; set; }
    public string Sha256 { get; set; } = "";
    // Pipeline-attested metadata, not a server-verified APK signer identity.
    public string? SigningCertificateSha256 { get; set; }
    public string? Notes { get; set; }
    public DateTime? PublishedAt { get; set; }
    public UpdateReleaseStatus Status { get; set; }
    public long Revision { get; set; } = 1;
}

public sealed class DeviceUpdateState
{
    public Guid InstallationId { get; set; }
    public Installation Installation { get; set; } = null!;
    public long VersionCode { get; set; }
    public string PackageName { get; set; } = "";
    public string Channel { get; set; } = "";
    public int Sdk { get; set; }
    public string Mode { get; set; } = "prompt";
    public string Abi { get; set; } = "other";
    public DateTime UpdatedAt { get; set; } = DateTime.UtcNow;
    public long Revision { get; set; } = 1;
}

public sealed class DeviceUpdateCommand
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public Guid InstallationId { get; set; }
    public Installation Installation { get; set; } = null!;
    public Guid ReleaseId { get; set; }
    public UpdateRelease Release { get; set; } = null!;
    public string Status { get; set; } = "pending";
    public string Mode { get; set; } = "prompt";
    public DateTime CreatedAt { get; set; } = DateTime.UtcNow;
    public DateTime UpdatedAt { get; set; } = DateTime.UtcNow;
    public DateTime? AckAt { get; set; }
    public DateTime? DownloadedAt { get; set; }
    public DateTime? InstallPromptedAt { get; set; }
    public DateTime? InstalledAt { get; set; }
    public DateTime? FailedAt { get; set; }
    public string? ErrorCode { get; set; }
    public long Revision { get; set; } = 1;
}

public static class UpdateModelConfiguration
{
    public static void Configure(ModelBuilder builder)
    {
        builder.Entity<UpdateRelease>(e =>
        {
            e.ToTable("UpdateReleases"); e.HasKey(x => x.Id);
            e.HasIndex(x => x.VersionCode).IsUnique();
            e.Property(x => x.VersionName).HasMaxLength(64).IsRequired();
            e.Property(x => x.Channel).HasMaxLength(16).IsRequired();
            e.Property(x => x.PackageName).HasMaxLength(128).IsRequired();
            e.Property(x => x.Abi).HasMaxLength(16).IsRequired();
            e.Property(x => x.StorageKey).HasMaxLength(40).IsRequired();
            e.Property(x => x.Sha256).HasMaxLength(64).IsRequired();
            e.Property(x => x.SigningCertificateSha256).HasMaxLength(64);
            e.Property(x => x.Notes).HasMaxLength(4096);
            e.Property(x => x.Status).HasConversion<string>().HasMaxLength(16);
            e.Property(x => x.Revision).IsConcurrencyToken();
        });
        builder.Entity<DeviceUpdateState>(e =>
        {
            e.ToTable("DeviceUpdateStates"); e.HasKey(x => x.InstallationId);
            e.HasOne(x => x.Installation).WithOne().HasForeignKey<DeviceUpdateState>(x => x.InstallationId).OnDelete(DeleteBehavior.Restrict);
            e.Property(x => x.PackageName).HasMaxLength(128).IsRequired();
            e.Property(x => x.Channel).HasMaxLength(16).IsRequired();
            e.Property(x => x.Mode).HasMaxLength(16).IsRequired();
            e.Property(x => x.Abi).HasMaxLength(16).IsRequired();
            e.Property(x => x.Revision).IsConcurrencyToken();
        });
        builder.Entity<DeviceUpdateCommand>(e =>
        {
            e.ToTable("DeviceUpdateCommands"); e.HasKey(x => x.Id);
            e.HasIndex(x => new { x.InstallationId, x.ReleaseId }).IsUnique();
            e.HasOne(x => x.Installation).WithMany().HasForeignKey(x => x.InstallationId).OnDelete(DeleteBehavior.Restrict);
            e.HasOne(x => x.Release).WithMany().HasForeignKey(x => x.ReleaseId).OnDelete(DeleteBehavior.Restrict);
            e.Property(x => x.Status).HasMaxLength(16).IsRequired();
            e.Property(x => x.Mode).HasMaxLength(16).IsRequired();
            e.Property(x => x.ErrorCode).HasMaxLength(128);
            e.Property(x => x.Revision).IsConcurrencyToken();
        });
    }
}

public sealed record ReleaseUpload(long VersionCode, string VersionName, string Channel, string PackageName,
    int MinSdk, bool Mandatory, string? SigningCertificateSha256, string? Notes, string Abi = "universal");
public sealed record UpdateCheckRequest(long VersionCode, string PackageName, string Channel, int Sdk,
    string Mode = "prompt", string Abi = "other");
public sealed record ReleaseManifest(Guid ReleaseId, long VersionCode, string VersionName, string PackageName,
    int MinSdk, bool Mandatory, string ReleaseUrl, string DownloadUrl, string Sha256,
    string? SigningCertificateSha256, long SizeBytes, string? Notes, DateTime PublishedAt);
public sealed record PendingUpdate(Guid CommandId, Guid ReleaseId, long VersionCode, string VersionName,
    bool Mandatory, string InstallMode, string DownloadUrl, string Sha256, string? SigningCertificateSha256,
    long SizeBytes, string? Notes);
public sealed record UpdateCheckResult(ReleaseManifest? Latest, PendingUpdate? Pending);
public sealed record OutdatedDevice(Guid InstallationId, long VersionCode, string PackageName, string Channel,
    int Sdk, string Mode, string Abi);

public sealed class UpdateService(AppDbContext db, ApkStorage storage)
{
    public async Task<UpdateRelease> UploadAsync(ReleaseUpload metadata, string filename, Stream content, CancellationToken ct = default)
    {
        ArgumentNullException.ThrowIfNull(metadata);
        ValidateIdentity(metadata.PackageName, metadata.Channel);
        if (metadata.VersionCode <= 0 || metadata.MinSdk <= 0 || string.IsNullOrWhiteSpace(metadata.VersionName) ||
            metadata.VersionName.Length > 64 || metadata.VersionName.Any(char.IsControl) ||
            metadata.Notes?.Length > 4096 || !ReleaseAbi(metadata.Abi)) throw Invalid("Invalid release metadata.");
        var certificate = metadata.SigningCertificateSha256;
        if (!string.IsNullOrEmpty(certificate) && (certificate.Length != 64 || certificate.Any(c => !Uri.IsHexDigit(c))))
            throw Invalid("Signing certificate must be a 64-character SHA-256 hex value, or absent.");
        if (await db.Set<UpdateRelease>().AnyAsync(x => x.VersionCode == metadata.VersionCode, ct)) throw DuplicateVersion();
        var release = new UpdateRelease
        {
            VersionCode = metadata.VersionCode, VersionName = Sanitizer.Clean(metadata.VersionName, 64),
            Channel = metadata.Channel, PackageName = metadata.PackageName, MinSdk = metadata.MinSdk,
            Mandatory = metadata.Mandatory, Abi = metadata.Abi,
            SigningCertificateSha256 = string.IsNullOrEmpty(certificate) ? null : certificate.ToLowerInvariant(),
            Notes = metadata.Notes is null ? null : Sanitizer.Clean(metadata.Notes, 4096)
        };
        var artifact = await storage.StoreAsync(release.Id, filename, content, ct);
        release.StorageKey = artifact.StorageKey; release.SizeBytes = artifact.SizeBytes; release.Sha256 = artifact.Sha256;
        db.Set<UpdateRelease>().Add(release);
        try { await db.SaveChangesAsync(ct); }
        catch (Exception error)
        {
            db.Entry(release).State = EntityState.Detached;
            storage.Delete(artifact.StorageKey);
            if (error is DbUpdateException && await db.Set<UpdateRelease>().AsNoTracking()
                .AnyAsync(x => x.VersionCode == metadata.VersionCode, CancellationToken.None)) throw DuplicateVersion();
            throw;
        }
        return release;
    }

    public async Task<UpdateRelease> PublishAsync(Guid releaseId, CancellationToken ct = default)
    {
        await using var tx = await db.Database.BeginTransactionAsync(DomainRules.Isolation(db), ct);
        await db.Database.ExecuteSqlInterpolatedAsync($"UPDATE UpdateReleases SET Id = Id WHERE Id = {releaseId}", ct);
        var release = await db.Set<UpdateRelease>().SingleOrDefaultAsync(x => x.Id == releaseId, ct) ?? throw NotFound();
        await db.Entry(release).ReloadAsync(ct);
        if (release.Status == UpdateReleaseStatus.Draft)
        {
            release.Status = UpdateReleaseStatus.Published; release.PublishedAt = DateTime.UtcNow; release.Revision++;
            await SaveAsync(ct);
        }
        await tx.CommitAsync(ct);
        return release;
    }

    public async Task<UpdateCheckResult> CheckAsync(Guid installationId, UpdateCheckRequest request, CancellationToken ct = default)
    {
        ValidateCheck(request);
        await using var tx = await db.Database.BeginTransactionAsync(DomainRules.Isolation(db), ct);
        await DomainRules.LockDevice(db, installationId, ct);
        await ObserveAsync(installationId, request, ct);
        var latest = await db.Set<UpdateRelease>().AsNoTracking().Where(x => x.Status == UpdateReleaseStatus.Published &&
            x.VersionCode > request.VersionCode && x.PackageName == request.PackageName && x.Channel == request.Channel &&
            x.MinSdk <= request.Sdk && (x.Abi == "universal" || x.Abi == request.Abi))
            .OrderByDescending(x => x.Abi == request.Abi).ThenByDescending(x => x.VersionCode).FirstOrDefaultAsync(ct);
        var pending = await PendingCoreAsync(installationId, ct);
        await tx.CommitAsync(ct);
        return new(latest is null ? null : Manifest(latest), pending);
    }

    // Called inside the adapter's transaction: deliberately does not start or commit one.
    public async Task ObserveAsync(Guid installationId, UpdateCheckRequest request, CancellationToken ct = default)
    {
        ValidateCheck(request);
        await ActiveDeviceAsync(installationId, ct);
        var state = await StateAsync(installationId, ct);
        state.VersionCode = request.VersionCode; state.PackageName = request.PackageName; state.Channel = request.Channel;
        state.Sdk = request.Sdk; state.Mode = request.Mode; state.Abi = request.Abi;
        state.UpdatedAt = DateTime.UtcNow; state.Revision++;
        await SaveAsync(ct);
    }

    public async Task ObserveAsync(Guid installationId, int appVersionCode, bool managedDevice, CancellationToken ct = default)
    {
        if (appVersionCode <= 0) throw Invalid("Version code must be positive.");
        await ActiveDeviceAsync(installationId, ct);
        var state = await StateAsync(installationId, ct);
        state.VersionCode = appVersionCode; state.Mode = managedDevice ? "managed" : "prompt";
        state.UpdatedAt = DateTime.UtcNow; state.Revision++;
        await SaveAsync(ct);
    }

    public async Task<PendingUpdate?> GetPendingAsync(Guid installationId, CancellationToken ct = default)
    {
        await ActiveDeviceAsync(installationId, ct);
        return await PendingCoreAsync(installationId, ct);
    }

    public async Task<IReadOnlyList<DeviceUpdateCommand>> SendAsync(Guid releaseId, IReadOnlyList<Guid> installationIds, string mode, CancellationToken ct = default)
    {
        ValidateMode(mode);
        if (installationIds is null || installationIds.Count is < 1 or > 500 || installationIds.Contains(Guid.Empty))
            throw Invalid("Select between 1 and 500 installations.");
        var ids = installationIds.Distinct().OrderBy(x => x).ToArray();
        await using var tx = await db.Database.BeginTransactionAsync(DomainRules.Isolation(db), ct);
        foreach (var id in ids) await DomainRules.LockDevice(db, id, ct);
        var release = await GetPublishedAsync(releaseId, ct);
        var states = await db.Set<DeviceUpdateState>().AsNoTracking().Where(x => ids.Contains(x.InstallationId) &&
            x.Installation.Status == InstallationStatus.Active).ToListAsync(ct);
        var commands = await db.Set<DeviceUpdateCommand>().Where(x => x.ReleaseId == releaseId && ids.Contains(x.InstallationId)).ToListAsync(ct);
        if (commands.Any(x => x.Mode != mode))
            throw new DomainException(409, "command_mode_conflict", "An existing command has a different installation mode.");
        if (states.Count != ids.Length || states.Any(x => !commands.Any(c => c.InstallationId == x.InstallationId) &&
            (!Compatible(release, x) || (mode == "managed" && x.Mode != "managed"))))
            throw Invalid("All new targets must be active, outdated and compatible with the requested installation mode.");
        foreach (var id in ids)
        {
            if (commands.Any(x => x.InstallationId == id)) continue;
            var command = new DeviceUpdateCommand { InstallationId = id, ReleaseId = releaseId, Mode = mode };
            db.Set<DeviceUpdateCommand>().Add(command); commands.Add(command);
        }
        await SaveAsync(ct); await tx.CommitAsync(ct);
        return commands;
    }

    public async Task<DeviceUpdateCommand> ReportAsync(Guid installationId, Guid commandId, string status, string? errorCode, CancellationToken ct = default)
    {
        await using var tx = await db.Database.BeginTransactionAsync(DomainRules.Isolation(db), ct);
        await DomainRules.LockDevice(db, installationId, ct);
        await ActiveDeviceAsync(installationId, ct);
        var command = await db.Set<DeviceUpdateCommand>().SingleOrDefaultAsync(x => x.Id == commandId && x.InstallationId == installationId, ct)
            ?? throw NotFound();
        await db.Entry(command).ReloadAsync(ct);
        if (Rank(status) < 0 && status != "failed") throw Invalid("Invalid update status.");
        if (status == command.Status || (status != "failed" && command.Status != "failed" && Rank(status) <= Rank(command.Status)))
        { await tx.CommitAsync(ct); return command; }
        if (command.Status is "installed" or "failed") throw new DomainException(409, "terminal_command", "The command is terminal.");
        if (status != "failed" && Rank(status) != Rank(command.Status) + 1 &&
            !(command.Mode == "managed" && command.Status == "downloaded" && status == "installed"))
            throw new DomainException(409, "invalid_transition", "The command must progress sequentially.");
        if (status == "failed")
        {
            errorCode ??= "unknown";
            if (errorCode.Length is < 1 or > 128 || errorCode.Any(c => !(char.IsAsciiLetterOrDigit(c) || c is '_' or '.' or '-')))
                throw Invalid("Error code must be a bounded identifier, not diagnostic text.");
        }
        command.Status = status; command.UpdatedAt = DateTime.UtcNow; command.Revision++;
        switch (status)
        {
            case "ack": command.AckAt = command.UpdatedAt; break;
            case "downloaded": command.DownloadedAt = command.UpdatedAt; break;
            case "installPrompted": command.InstallPromptedAt = command.UpdatedAt; break;
            case "installed": command.InstalledAt = command.UpdatedAt; break;
            case "failed": command.FailedAt = command.UpdatedAt; command.ErrorCode = Sanitizer.Clean(errorCode, 128); break;
        }
        await SaveAsync(ct); await tx.CommitAsync(ct);
        return command;
    }

    public async Task<IReadOnlyList<UpdateRelease>> ListAsync(CancellationToken ct = default) =>
        await db.Set<UpdateRelease>().AsNoTracking().OrderByDescending(x => x.VersionCode).ToListAsync(ct);

    public async Task<IReadOnlyList<OutdatedDevice>> OutdatedAsync(Guid releaseId, CancellationToken ct = default)
    {
        var release = await GetPublishedAsync(releaseId, ct);
        var states = await db.Set<DeviceUpdateState>().AsNoTracking().Where(x => x.Installation.Status == InstallationStatus.Active &&
            x.VersionCode < release.VersionCode && x.PackageName == release.PackageName && x.Channel == release.Channel &&
            x.Sdk >= release.MinSdk && (release.Abi == "universal" || x.Abi == release.Abi)).ToListAsync(ct);
        return states.Select(x => new OutdatedDevice(x.InstallationId, x.VersionCode, x.PackageName, x.Channel, x.Sdk, x.Mode, x.Abi)).ToArray();
    }

    public async Task<ReleaseManifest?> GetLatestAsync(string channel, string abi, CancellationToken ct = default)
    {
        if (channel is not ("stable" or "beta") || !DeviceAbi(abi)) throw Invalid("Invalid channel or ABI.");
        var release = await db.Set<UpdateRelease>().AsNoTracking().Where(x => x.Status == UpdateReleaseStatus.Published &&
            x.Channel == channel && (x.Abi == "universal" || x.Abi == abi))
            .OrderByDescending(x => x.Abi == abi).ThenByDescending(x => x.VersionCode).FirstOrDefaultAsync(ct);
        return release is null ? null : Manifest(release);
    }

    public async Task<UpdateRelease> GetPublishedAsync(Guid releaseId, CancellationToken ct = default) =>
        await db.Set<UpdateRelease>().AsNoTracking().SingleOrDefaultAsync(x => x.Id == releaseId && x.Status == UpdateReleaseStatus.Published, ct)
        ?? throw NotFound();

    private async Task<DeviceUpdateState> StateAsync(Guid id, CancellationToken ct)
    {
        var state = await db.Set<DeviceUpdateState>().SingleOrDefaultAsync(x => x.InstallationId == id, ct);
        if (state is not null) { await db.Entry(state).ReloadAsync(ct); return state; }
        state = new DeviceUpdateState { InstallationId = id };
        db.Set<DeviceUpdateState>().Add(state);
        return state;
    }

    private async Task ActiveDeviceAsync(Guid id, CancellationToken ct)
    {
        var installation = await db.Installations.AsNoTracking().SingleOrDefaultAsync(x => x.Id == id, ct) ?? throw NotFound();
        if (installation.Status != InstallationStatus.Active)
            throw new DomainException(403, "installation_disabled", "Installation is revoked.");
    }

    private async Task<PendingUpdate?> PendingCoreAsync(Guid id, CancellationToken ct)
    {
        var state = await db.Set<DeviceUpdateState>().AsNoTracking().SingleOrDefaultAsync(x => x.InstallationId == id, ct);
        if (state is null) return null;
        var commands = await db.Set<DeviceUpdateCommand>().AsNoTracking().Include(x => x.Release)
            .Where(x => x.InstallationId == id && x.Status != "installed" && x.Status != "failed")
            .OrderBy(x => x.CreatedAt).ThenBy(x => x.Id).ToListAsync(ct);
        var command = commands.FirstOrDefault(x => Compatible(x.Release, state) && (x.Mode != "managed" || state.Mode == "managed"));
        if (command is null) return null;
        var release = command.Release;
        return new(command.Id, release.Id, release.VersionCode, release.VersionName, release.Mandatory, command.Mode,
            storage.GetDownloadUrl(release.Id), release.Sha256, release.SigningCertificateSha256, release.SizeBytes, release.Notes);
    }

    private ReleaseManifest Manifest(UpdateRelease release) => new(release.Id, release.VersionCode, release.VersionName,
        release.PackageName, release.MinSdk, release.Mandatory, storage.GetDownloadUrl(release.Id), storage.GetDownloadUrl(release.Id),
        release.Sha256, release.SigningCertificateSha256, release.SizeBytes, release.Notes, DateTime.SpecifyKind(release.PublishedAt!.Value, DateTimeKind.Utc));
    private static bool Compatible(UpdateRelease release, DeviceUpdateState state) => release.Status == UpdateReleaseStatus.Published &&
        state.VersionCode < release.VersionCode && state.PackageName == release.PackageName && state.Channel == release.Channel &&
        state.Sdk >= release.MinSdk && (release.Abi == "universal" || release.Abi == state.Abi);
    private static void ValidateCheck(UpdateCheckRequest request)
    {
        ArgumentNullException.ThrowIfNull(request);
        ValidateIdentity(request.PackageName, request.Channel); ValidateMode(request.Mode);
        if (request.VersionCode <= 0 || request.Sdk <= 0 || !DeviceAbi(request.Abi)) throw Invalid("Invalid device update metadata.");
    }
    private static void ValidateIdentity(string package, string channel)
    {
        if (!((channel == "stable" && package == "com.megastream.app") || (channel == "beta" && package == "com.megastream.app.beta")))
            throw Invalid("Package name and channel must match a supported build identity.");
    }
    private static bool ReleaseAbi(string abi) => abi is "universal" or "arm64_v8a" or "armeabi_v7a" or "x86_64" or "x86";
    private static bool DeviceAbi(string abi) => abi is "other" or "arm64_v8a" or "armeabi_v7a" or "x86_64" or "x86";
    private static void ValidateMode(string mode) { if (mode is not ("prompt" or "managed")) throw Invalid("Invalid installation mode."); }
    private static int Rank(string status) => status switch { "pending" => 0, "ack" => 1, "downloaded" => 2, "installPrompted" => 3, "installed" => 4, _ => -1 };
    private async Task SaveAsync(CancellationToken ct)
    {
        try { await db.SaveChangesAsync(ct); }
        catch (DbUpdateConcurrencyException) { throw new DomainException(409, "update_conflict", "Update state changed; retry the request."); }
    }
    private static DomainException Invalid(string message) => new(400, "invalid_update", message);
    private static DomainException NotFound() => new(404, "not_found", "Update resource not found.");
    private static DomainException DuplicateVersion() => new(409, "duplicate_version", "Version code already exists.");
}
