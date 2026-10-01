namespace MegaStream.Server.RemoteProviders.Core;

public sealed class RemoteProviderProfile
{
    public Guid Id { get; set; }
    public string DisplayName { get; set; } = "";
    public string Type { get; set; } = "";
    public string Status { get; set; } = "active";
    public long Revision { get; set; }
    public byte[] EncryptedPayload { get; set; } = Array.Empty<byte>();
    public string KeyVersion { get; set; } = "";
    public DateTime CreatedAt { get; set; }
    public DateTime UpdatedAt { get; set; }
    public DateTime? DeletedAt { get; set; }
}
public sealed class RemoteProviderAssignment
{
    public Guid Id { get; set; }
    public Guid ProfileId { get; set; }
    public Guid InstallationId { get; set; }
    public string Policy { get; set; } = "optional";
    public bool Enabled { get; set; }
    public long Revision { get; set; }
    public DateTime AssignedAt { get; set; }
    public DateTime? RevokedAt { get; set; }
}
public sealed class ProviderAssignmentReport
{
    public Guid AssignmentId { get; set; }
    public Guid InstallationId { get; set; }
    public long ProfileRevision { get; set; }
    public string State { get; set; } = "";
    public DateTime LastReportedAt { get; set; }
    public string? SafeErrorCode { get; set; }
    public DateTime? ProviderReportedExpiresAt { get; set; }
    public int? ProviderReportedMaxConnections { get; set; }
}
public sealed class RemoteProviderRevisionSequence
{
    public int Id { get; set; } = 1;
    public long Revision { get; set; }
}
