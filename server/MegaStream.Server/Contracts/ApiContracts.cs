namespace MegaStream.Server.Contracts;

public record RegisterRequest(string Fingerprint, string Platform, string AppVersion, string DeviceModel, string OsVersion,
    string? Manufacturer = null, string? AndroidVersion = null, string? Locale = null);
public record RegisterResponse(Guid InstallationId, string BearerToken);
public record ActivateRequest(string LicenseKey);
public record ActivationCodeResponse(string Code, DateTime ExpiresAt, string Status, string PollToken);
public record ActivationStatusResponse(string Status, string? Lease);
public record LeaseResponse(string? Lease, string Status);
public record HeartbeatRequest(string SessionId, long? MemoryBytes, string? ExitReason, string? AppVersion = null,
    DateTime? StartedAt = null, DateTime? EndedAt = null, DateTime? LastHeartbeatAt = null, string? LikelyExitReason = null);
public record DiagnosticItem(string Level, string Category, string Message, DateTime? OccurredAt = null,
    string? SessionId = null, string? Severity = null, string? StackTrace = null, string? ChannelName = null,
    string? SourceType = null, string? Container = null, string? VideoCodec = null, string? AudioCodec = null,
    long? MemoryBytes = null, string? AppVersion = null, string? DeviceVersion = null, Guid? EventId = null);
public record DiagnosticsRequest(List<DiagnosticItem> Events);
