using MegaStream.Server.Models;
using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.Identity.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore;

namespace MegaStream.Server.Data;
public class AppDbContext(DbContextOptions<AppDbContext> options) : IdentityDbContext<IdentityUser>(options)
{
    public DbSet<License> Licenses => Set<License>();
    public DbSet<Installation> Installations => Set<Installation>();
    public DbSet<ActivationCode> ActivationCodes => Set<ActivationCode>();
    public DbSet<DeviceSession> DeviceSessions => Set<DeviceSession>();
    public DbSet<DiagnosticEvent> DiagnosticEvents => Set<DiagnosticEvent>();
    public DbSet<DevicePolicyAudit> DevicePolicyAudits => Set<DevicePolicyAudit>();
    public DbSet<AdminNotification> AdminNotifications => Set<AdminNotification>();
    public DbSet<PlaybackQualityDefault> PlaybackQualityDefaults => Set<PlaybackQualityDefault>();
    protected override void OnModelCreating(ModelBuilder b)
    {
        base.OnModelCreating(b);
        MegaStream.Server.Updates.UpdateModelConfiguration.Configure(b);
        MegaStream.Server.V1.V1Persistence.ConfigureV1Protocol(b);
        MegaStream.Server.RemoteProviders.ModelConfiguration.Configure(b);
        b.Entity<License>(e => {
            e.Property(x=>x.UiStyle).HasMaxLength(7);
            e.Property(x=>x.KeyHash).HasMaxLength(64).IsRequired(); e.HasIndex(x=>x.KeyHash).IsUnique();
            e.Property(x=>x.KeyLast4).HasMaxLength(4); e.Property(x=>x.Label).HasMaxLength(128);
            e.Property(x=>x.Status).HasConversion<string>().HasMaxLength(16);
            e.HasIndex(x=>new {x.Status,x.ValidUntil});
        });
        b.Entity<Installation>(e => {
            e.Property(x=>x.PlaybackQuality).HasMaxLength(4);
            e.Property(x=>x.UiStyle).HasMaxLength(7);
            e.Property(x=>x.LocalSubscriptionsJson).HasColumnType("longtext");
            e.Property(x=>x.TokenHash).HasMaxLength(64).IsRequired(); e.HasIndex(x=>x.TokenHash).IsUnique();
            e.Property(x=>x.FingerprintHash).HasMaxLength(64); e.HasIndex(x=>x.FingerprintHash);
            e.Property(x=>x.CredentialBinding).HasMaxLength(43); e.HasIndex(x=>x.LastLeaseExpiresAt);
            e.Property(x=>x.Platform).HasMaxLength(32); e.Property(x=>x.AppVersion).HasMaxLength(64);
            e.Property(x=>x.DeviceModel).HasMaxLength(128); e.Property(x=>x.OsVersion).HasMaxLength(64);
            e.Property(x=>x.Manufacturer).HasMaxLength(128); e.Property(x=>x.Locale).HasMaxLength(32);
            e.Property(x=>x.KioskMode).HasMaxLength(16).HasDefaultValue("off");
            e.Property(x=>x.AllowLocalExit).HasDefaultValue(true).HasSentinel(true);
            e.Property(x=>x.AllowSubscriptionDetails).HasDefaultValue(true).HasSentinel(true);
            e.Property(x=>x.MacAddress).HasMaxLength(17);
            e.Property(x=>x.Status).HasConversion<string>().HasMaxLength(16);
            e.HasIndex(x=>new {x.LicenseId,x.Status}); e.HasIndex(x=>x.LastSeenAt);
            e.HasOne(x=>x.License).WithMany().HasForeignKey(x=>x.LicenseId).OnDelete(DeleteBehavior.Restrict);
        });
        b.Entity<PlaybackQualityDefault>(e => {
            e.Property(x=>x.Id).ValueGeneratedNever();
            e.Property(x=>x.Quality).HasMaxLength(4).IsRequired();
            e.HasData(new PlaybackQualityDefault { Id = 1, Quality = "1080" });
        });
        b.Entity<AdminNotification>(e => {
            e.Property(x=>x.Title).HasMaxLength(128).IsRequired();
            e.Property(x=>x.Message).HasMaxLength(2000).IsRequired();
            e.HasIndex(x=>new{x.TargetInstallationId,x.CreatedAt});
            e.HasOne(x=>x.TargetInstallation).WithMany().HasForeignKey(x=>x.TargetInstallationId).OnDelete(DeleteBehavior.Restrict);
        });
        b.Entity<DevicePolicyAudit>(e => {
            e.Property(x=>x.ActorId).HasMaxLength(128).IsRequired();
            e.Property(x=>x.OldKioskMode).HasMaxLength(16).IsRequired();
            e.Property(x=>x.NewKioskMode).HasMaxLength(16).IsRequired();
            e.HasIndex(x=>new{x.InstallationId,x.CreatedAt});
            e.HasOne(x=>x.Installation).WithMany().HasForeignKey(x=>x.InstallationId).OnDelete(DeleteBehavior.Restrict);
        });
        b.Entity<ActivationCode>(e => {
            e.Property(x=>x.CodeHash).HasMaxLength(64); e.HasIndex(x=>x.CodeHash).IsUnique(); e.Property(x=>x.CodeLast4).HasMaxLength(4);
            e.Property(x=>x.PollTokenHash).HasMaxLength(64);
            e.Property(x=>x.Status).HasConversion<string>().HasMaxLength(16); e.HasIndex(x=>new{x.Status,x.ExpiresAt});
            e.HasOne(x=>x.Installation).WithMany().HasForeignKey(x=>x.InstallationId).OnDelete(DeleteBehavior.Restrict);
            e.HasOne(x=>x.License).WithMany().HasForeignKey(x=>x.LicenseId).OnDelete(DeleteBehavior.Restrict);
        });
        b.Entity<DeviceSession>(e => {
            e.Property(x=>x.ClientSessionId).HasMaxLength(64); e.Property(x=>x.ExitReason).HasMaxLength(256);
            e.Property(x=>x.AppVersion).HasMaxLength(64);
            e.HasIndex(x=>new{x.InstallationId,x.ClientSessionId}).IsUnique(); e.HasIndex(x=>x.ClientSessionId); e.HasIndex(x=>x.LastHeartbeatAt);
            e.HasOne(x=>x.Installation).WithMany().HasForeignKey(x=>x.InstallationId).OnDelete(DeleteBehavior.Restrict);
        });
        b.Entity<DiagnosticEvent>(e => {
            e.HasIndex(x=>new{x.InstallationId,x.EventId}).IsUnique();
            e.Property(x=>x.Level).HasMaxLength(16);e.Property(x=>x.Category).HasMaxLength(64);e.Property(x=>x.Message).HasMaxLength(2048);
            e.Property(x=>x.SessionId).HasMaxLength(64); e.Property(x=>x.StackTrace).HasMaxLength(4096);
            e.Property(x=>x.ChannelName).HasMaxLength(128); e.Property(x=>x.SourceType).HasMaxLength(32);
            e.Property(x=>x.Container).HasMaxLength(32); e.Property(x=>x.VideoCodec).HasMaxLength(32); e.Property(x=>x.AudioCodec).HasMaxLength(32);
            e.Property(x=>x.AppVersion).HasMaxLength(64); e.Property(x=>x.DeviceVersion).HasMaxLength(64);
            e.HasIndex(x=>x.CreatedAt);e.HasIndex(x=>new{x.InstallationId,x.OccurredAt}); e.HasIndex(x=>new{x.Category,x.OccurredAt}); e.HasIndex(x=>x.OccurredAt);
            e.HasOne(x=>x.Installation).WithMany().HasForeignKey(x=>x.InstallationId).OnDelete(DeleteBehavior.Restrict);
        });
    }
}
