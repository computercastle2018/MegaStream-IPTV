using System.Text.Json.Serialization;

namespace MegaStream.Server.RemoteProviders.Core;

public static class RemoteProviderValues
{
    public const string XtreamCodes = "XTREAM_CODES", M3u = "M3U", StalkerPortal = "STALKER_PORTAL";
}

// Ordinary classes deliberately avoid generated ToString methods exposing credentials.
[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed class RemoteProviderConfiguration
{
    public string? ServerUrl { get; set; }
    public string? EpgUrl { get; set; }
    public string? HttpUserAgent { get; set; }
    public Dictionary<string, string>? HttpHeaders { get; set; }
    public string EpgSyncMode { get; set; } = "background";
    public XtreamConfiguration? Xtream { get; set; }
    public M3uConfiguration? M3u { get; set; }
    public StalkerConfiguration? Stalker { get; set; }
}
[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed class XtreamConfiguration
{
    public string Username { get; set; } = "";
    public string Password { get; set; } = "";
    public bool FastSyncEnabled { get; set; }
    public string LiveSyncMode { get; set; } = "auto";
}
[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed class M3uConfiguration
{
    public string M3uUrl { get; set; } = "";
    public bool VodClassificationEnabled { get; set; }
}
[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed class StalkerConfiguration
{
    public string PortalUrl { get; set; } = "";
    public string StalkerMacAddress { get; set; } = "";
    public string? DeviceProfile { get; set; }
    public string? Timezone { get; set; }
    public string? Locale { get; set; }
}
[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed class ReplaceRemoteProviderProfile
{
    public string DisplayName { get; set; } = "";
    public string Type { get; set; } = "";
    public RemoteProviderConfiguration Configuration { get; set; } = new();
}
[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed class AssignRemoteProvider
{
    public string Policy { get; set; } = "optional";
    public bool Enabled { get; set; } = true;
}
[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed class ReportRemoteProvider
{
    public long ProfileRevision { get; set; }
    public string State { get; set; } = "";
    public string? SafeErrorCode { get; set; }
    public DateTime? ProviderReportedExpiresAt { get; set; }
    public int? ProviderReportedMaxConnections { get; set; }
}
[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed class RemoteProviderProfileMetadata
{
    public Guid Id { get; set; }
    public string DisplayName { get; set; } = "";
    public string Type { get; set; } = "";
    public string Status { get; set; } = "";
    public long Revision { get; set; }
    public DateTime CreatedAt { get; set; }
    public DateTime UpdatedAt { get; set; }
    public DateTime? DeletedAt { get; set; }
}
[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed class RemoteProviderDeviceItem
{
    public Guid AssignmentId { get; set; }
    public Guid ProfileId { get; set; }
    public long Revision { get; set; }
    public long ProfileRevision { get; set; }
    public string Policy { get; set; } = "";
    public bool Enabled { get; set; }
    public bool Revoked { get; set; }
    public DateTime? RevokedAt { get; set; }
    public string? DisplayName { get; set; }
    public string? Type { get; set; }
    public RemoteProviderConfiguration? Configuration { get; set; }
}
[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed class RemoteProviderDeviceResult
{
    public long Revision { get; set; }
    public IReadOnlyList<RemoteProviderDeviceItem> Items { get; set; } = Array.Empty<RemoteProviderDeviceItem>();
}
public interface IRemoteProviderService
{
    Task<RemoteProviderProfileMetadata> CreateProfileAsync(ReplaceRemoteProviderProfile request, CancellationToken ct = default,
        Guid? installationId = null, string policy = "auto_enabled");
    Task<RemoteProviderProfileMetadata> UpdateProfileAsync(Guid profileId, ReplaceRemoteProviderProfile request, CancellationToken ct = default);
    Task RevokeProfileAsync(Guid profileId, CancellationToken ct = default);
    Task<IReadOnlyList<RemoteProviderProfileMetadata>> ListProfilesAsync(CancellationToken ct = default);
    Task<RemoteProviderProfileMetadata> GetProfileAsync(Guid profileId, CancellationToken ct = default);
    Task<RemoteProviderAssignment> AssignAsync(Guid profileId, Guid installationId, AssignRemoteProvider request, CancellationToken ct = default);
    Task RevokeAssignmentAsync(Guid assignmentId, CancellationToken ct = default);
    Task<IReadOnlyList<RemoteProviderAssignment>> ListAssignmentsAsync(Guid installationId, CancellationToken ct = default);
    Task<IReadOnlyList<ProviderAssignmentReport>> ListReportsAsync(Guid installationId, CancellationToken ct = default);
    Task<RemoteProviderDeviceResult> GetDeviceAsync(Guid installationId, long afterRevision = 0, CancellationToken ct = default);
    Task ReportAsync(Guid installationId, Guid assignmentId, ReportRemoteProvider request, CancellationToken ct = default);
}
