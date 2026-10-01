using MegaStream.Server.Data;
using MegaStream.Server.Models;
using MegaStream.Server.Services;
using Microsoft.EntityFrameworkCore;

namespace MegaStream.Server.V1;

public sealed class V1InstallationMetadata
{
    public Guid InstallationId { get; set; }
    public Guid RegistrationIdempotencyKey { get; set; }
    public int AppVersionCode { get; set; }
    public int AndroidApi { get; set; }
    public string PackageName { get; set; } = "";
    public string Channel { get; set; } = "";
    public string Abi { get; set; } = "";
    public bool ManagedDevice { get; set; }
}

public sealed class V1SessionState
{
    public Guid InstallationId { get; set; }
    public Guid AppSessionId { get; set; }
    public long LastSequence { get; set; }
    public string Mode { get; set; } = "";
    public DateTime LastAcceptedAt { get; set; }
    public long? JavaUsedBytes { get; set; }
    public long? JavaMaxBytes { get; set; }
    public long? NativeHeapBytes { get; set; }
    public long? PssBytes { get; set; }
    public long? AvailableSystemBytes { get; set; }
    public bool? LowMemory { get; set; }
    public string? RecoveredExitReason { get; set; }
    public string? RecoveredExitEvidence { get; set; }
    public DateTime? RecoveredExitAt { get; set; }
}

public static class V1SessionClaims
{
    // Caller holds the installation lock and owns the transaction; raw SQL never flushes pending EF changes.
    public static async Task<V1SessionState> ClaimAsync(AppDbContext db, Guid installationId, Guid appSessionId,
        DateTime observedAt, CancellationToken ct = default)
    {
        if (db.Database.CurrentTransaction is null)
            throw new InvalidOperationException("Session claims require an existing transaction and installation lock.");
        var clientSessionId = appSessionId.ToString("D");
        if (await db.DeviceSessions.AsNoTracking().AnyAsync(
            x => x.ClientSessionId == clientSessionId && x.InstallationId != installationId, ct))
            throw InvalidOwner();
        if (db.Database.ProviderName?.Contains("MySql", StringComparison.OrdinalIgnoreCase) == true)
            await db.Database.ExecuteSqlInterpolatedAsync($"INSERT INTO V1SessionStates (InstallationId, AppSessionId, LastSequence, Mode, LastAcceptedAt) VALUES ({installationId}, {appSessionId}, {-1L}, {""}, {observedAt}) ON DUPLICATE KEY UPDATE AppSessionId = AppSessionId", ct);
        else
            await db.Database.ExecuteSqlInterpolatedAsync($"INSERT INTO V1SessionStates (InstallationId, AppSessionId, LastSequence, Mode, LastAcceptedAt) VALUES ({installationId}, {appSessionId}, {-1L}, {""}, {observedAt}) ON CONFLICT(AppSessionId) DO NOTHING", ct);
        var claimed = await db.Set<V1SessionState>().SingleAsync(x => x.AppSessionId == appSessionId, ct);
        if (claimed.InstallationId != installationId) throw InvalidOwner();
        await db.Entry(claimed).ReloadAsync(ct);
        return claimed;
    }

    private static DomainException InvalidOwner() => new(409, "invalid_session", "Session belongs to another installation.");
}

public static class V1Persistence
{
    public static void ConfigureV1Protocol(this ModelBuilder builder)
    {
        builder.Entity<V1InstallationMetadata>(entity =>
        {
            entity.ToTable("V1InstallationMetadata");
            entity.HasKey(x => x.InstallationId);
            entity.Property(x => x.Abi).HasMaxLength(16).IsRequired();
            entity.Property(x => x.PackageName).HasMaxLength(128).IsRequired();
            entity.Property(x => x.Channel).HasMaxLength(16).IsRequired();
            entity.HasOne<Installation>().WithOne().HasForeignKey<V1InstallationMetadata>(x => x.InstallationId)
                .OnDelete(DeleteBehavior.Restrict);
        });
        builder.Entity<V1SessionState>(entity =>
        {
            entity.ToTable("V1SessionStates");
            entity.HasKey(x => new { x.InstallationId, x.AppSessionId });
            entity.HasIndex(x => x.LastAcceptedAt);
            entity.HasIndex(x => x.AppSessionId).IsUnique();
            entity.Property(x => x.Mode).HasMaxLength(16).IsRequired();
            entity.Property(x => x.RecoveredExitReason).HasMaxLength(32);
            entity.Property(x => x.RecoveredExitEvidence).HasMaxLength(32);
            entity.HasOne<Installation>().WithMany().HasForeignKey(x => x.InstallationId)
                .OnDelete(DeleteBehavior.Restrict);
        });
        V1Diagnostics.ConfigureModel(builder);
    }
}
