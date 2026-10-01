namespace MegaStream.Server.Models;

public enum LicenseStatus { Active, Suspended, Revoked }
public enum InstallationStatus { Active, Revoked }
public enum ActivationCodeStatus { Pending, Approved, Expired, Consumed, Cancelled }

public class License
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public string KeyHash { get; set; } = "";
    public string KeyLast4 { get; set; } = "";
    public string Label { get; set; } = "";
    public LicenseStatus Status { get; set; } = LicenseStatus.Active;
    public DateTime CreatedAt { get; set; } = DateTime.UtcNow;
    public DateTime UpdatedAt { get; set; } = DateTime.UtcNow;
    public DateTime ValidFrom { get; set; }
    public DateTime ValidUntil { get; set; }
    public int MaxInstallations { get; set; }
    public int OfflineGraceDays { get; set; } = 3;
    public long Revision { get; set; } = 1;
}
public class Installation
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public string TokenHash { get; set; } = "";
    public string CredentialBinding { get; set; } = "";
    public string FingerprintHash { get; set; } = "";
    public string Platform { get; set; } = "";
    public string AppVersion { get; set; } = "";
    public string DeviceModel { get; set; } = "";
    public string OsVersion { get; set; } = "";
    public string Manufacturer { get; set; } = "";
    public string Locale { get; set; } = "";
    public string KioskMode { get; set; } = "off";
    public bool AllowLocalExit { get; set; } = true;
    public InstallationStatus Status { get; set; } = InstallationStatus.Active;
    public Guid? LicenseId { get; set; }
    public License? License { get; set; }
    public DateTime CreatedAt { get; set; } = DateTime.UtcNow;
    public DateTime LastSeenAt { get; set; } = DateTime.UtcNow;
    public DateTime? ActivatedAt { get; set; }
    public DateTime? RevokedAt { get; set; }
    public DateTime? LastLeaseExpiresAt { get; set; }
    public string? LocalSubscriptionsJson { get; set; }
    public DateTime? LocalSubscriptionsReportedAt { get; set; }
}
public class DevicePolicyAudit
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public Guid InstallationId { get; set; }
    public Installation Installation { get; set; } = null!;
    public string ActorId { get; set; } = "";
    public string OldKioskMode { get; set; } = "off";
    public string NewKioskMode { get; set; } = "off";
    public bool OldAllowLocalExit { get; set; }
    public bool NewAllowLocalExit { get; set; }
    public DateTime CreatedAt { get; set; } = DateTime.UtcNow;
}
public class ActivationCode
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public string CodeHash { get; set; } = "";
    public string CodeLast4 { get; set; } = "";
    public string PollTokenHash { get; set; } = "";
    public Guid InstallationId { get; set; }
    public Installation Installation { get; set; } = null!;
    public Guid? LicenseId { get; set; }
    public License? License { get; set; }
    public ActivationCodeStatus Status { get; set; } = ActivationCodeStatus.Pending;
    public DateTime CreatedAt { get; set; } = DateTime.UtcNow;
    public DateTime ExpiresAt { get; set; }
    public DateTime? ConsumedAt { get; set; }
}
public class DeviceSession
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public Guid InstallationId { get; set; }
    public Installation Installation { get; set; } = null!;
    public string ClientSessionId { get; set; } = "";
    public string AppVersion { get; set; } = "";
    public DateTime StartedAt { get; set; } = DateTime.UtcNow;
    public DateTime LastHeartbeatAt { get; set; } = DateTime.UtcNow;
    public DateTime? EndedAt { get; set; }
    public long? MemoryBytes { get; set; }
    public string? ExitReason { get; set; }
}
public class DiagnosticEvent
{
    public long Id { get; set; }
    public Guid EventId { get; set; } = Guid.NewGuid();
    public Guid InstallationId { get; set; }
    public Installation Installation { get; set; } = null!;
    public DateTime CreatedAt { get; set; } = DateTime.UtcNow;
    public string Level { get; set; } = "";
    public string Category { get; set; } = "";
    public string Message { get; set; } = "";
    public DateTime OccurredAt { get; set; }
    public string SessionId { get; set; } = "";
    public string StackTrace { get; set; } = "";
    public string ChannelName { get; set; } = "";
    public string SourceType { get; set; } = "";
    public string Container { get; set; } = "";
    public string VideoCodec { get; set; } = "";
    public string AudioCodec { get; set; } = "";
    public long? MemoryBytes { get; set; }
    public string AppVersion { get; set; } = "";
    public string DeviceVersion { get; set; } = "";
}
