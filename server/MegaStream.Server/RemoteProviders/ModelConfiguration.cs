using MegaStream.Server.Models;
using MegaStream.Server.RemoteProviders.Core;
using Microsoft.EntityFrameworkCore;

namespace MegaStream.Server.RemoteProviders;

public static class ModelConfiguration
{
    public static void Configure(ModelBuilder b)
    {
        b.Entity<RemoteProviderRevisionSequence>(e =>
        {
            e.ToTable("RemoteProviderRevisionSequence");
            e.HasKey(x => x.Id);
            e.Property(x => x.Id).ValueGeneratedNever();
            e.HasData(new RemoteProviderRevisionSequence { Id = 1, Revision = 0 });
        });
        b.Entity<RemoteProviderProfile>(e =>
        {
            e.ToTable("RemoteProviderProfiles"); e.HasKey(x => x.Id);
            e.Property(x => x.DisplayName).HasMaxLength(128).IsRequired();
            e.Property(x => x.Type).HasMaxLength(32).IsRequired();
            e.Property(x => x.Status).HasMaxLength(16).IsRequired();
            e.Property(x => x.KeyVersion).HasMaxLength(32).IsRequired();
            e.Property(x => x.EncryptedPayload).HasMaxLength(32768).IsRequired();
            e.Property(x => x.Revision).IsConcurrencyToken();
            e.HasIndex(x => x.Revision);
        });
        b.Entity<RemoteProviderAssignment>(e =>
        {
            e.ToTable("RemoteProviderAssignments"); e.HasKey(x => x.Id);
            e.Property(x => x.Policy).HasMaxLength(16).IsRequired();
            e.Property(x => x.Revision).IsConcurrencyToken();
            e.HasIndex(x => new { x.InstallationId, x.Revision });
            e.HasIndex(x => new { x.ProfileId, x.InstallationId }).IsUnique();
            e.HasAlternateKey(x => new { x.Id, x.InstallationId });
            e.HasOne<RemoteProviderProfile>().WithMany().HasForeignKey(x => x.ProfileId).OnDelete(DeleteBehavior.Restrict);
            e.HasOne<Installation>().WithMany().HasForeignKey(x => x.InstallationId).OnDelete(DeleteBehavior.Restrict);
        });
        b.Entity<ProviderAssignmentReport>(e =>
        {
            e.ToTable("RemoteProviderReports"); e.HasKey(x => x.AssignmentId);
            e.Property(x => x.State).HasMaxLength(32).IsRequired();
            e.Property(x => x.SafeErrorCode).HasMaxLength(64);
            e.HasIndex(x => x.InstallationId);
            e.HasOne<RemoteProviderAssignment>().WithOne().HasForeignKey<ProviderAssignmentReport>(x => new { x.AssignmentId, x.InstallationId })
                .HasPrincipalKey<RemoteProviderAssignment>(x => new { x.Id, x.InstallationId }).OnDelete(DeleteBehavior.Restrict);
        });
    }
}
