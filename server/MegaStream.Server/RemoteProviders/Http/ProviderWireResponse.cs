using System.Text.Json.Serialization;
using MegaStream.Server.RemoteProviders.Core;

namespace MegaStream.Server.RemoteProviders.Http;

// Ordinary classes avoid generated ToString methods traversing secret-bearing configuration.
public sealed class ProviderWireResponse
{
    public long ServerRevision { get; init; }
    public IReadOnlyList<ProviderWireAssignment> Assignments { get; init; } = Array.Empty<ProviderWireAssignment>();
    public IReadOnlyList<ProviderWireTombstone> Tombstones { get; init; } = Array.Empty<ProviderWireTombstone>();

    public static ProviderWireResponse From(RemoteProviderDeviceResult batch) => new()
    {
        ServerRevision = batch.Revision,
        Assignments = batch.Items.Where(item => !item.Revoked).Select(ProviderWireAssignment.From).ToArray(),
        Tombstones = batch.Items.Where(item => item.Revoked).Select(item => new ProviderWireTombstone
        {
            AssignmentId = item.AssignmentId, AssignmentRevision = item.Revision,
            // Database providers can discard Kind even though stored timestamps are UTC.
            RevokedAt = item.RevokedAt.HasValue ? DateTime.SpecifyKind(item.RevokedAt.Value, DateTimeKind.Utc) : null
        }).ToArray()
    };
}

public sealed class ProviderWireTombstone
{
    public Guid AssignmentId { get; init; }
    public long AssignmentRevision { get; init; }
    public DateTime? RevokedAt { get; init; }
}

public sealed class ProviderWireAssignment
{
    public Guid AssignmentId { get; init; }
    public Guid ProfileId { get; init; }
    public long AssignmentRevision { get; init; }
    public long ProfileRevision { get; init; }
    public string Policy { get; init; } = "";
    public string Type { get; init; } = "";
    public string? DisplayName { get; init; }
    public ProviderWireConfiguration Configuration { get; init; } = new();

    internal static ProviderWireAssignment From(RemoteProviderDeviceItem assignment) => new()
    {
        AssignmentId = assignment.AssignmentId, ProfileId = assignment.ProfileId,
        AssignmentRevision = assignment.Revision, ProfileRevision = assignment.ProfileRevision,
        Policy = assignment.Policy, Type = assignment.Type!.ToLowerInvariant(), DisplayName = assignment.DisplayName,
        Configuration = ProviderWireConfiguration.From(assignment.Configuration!)
    };
}

public sealed class ProviderWireConfiguration
{
    public string? ServerUrl { get; init; }
    public string? EpgUrl { get; init; }
    public string? HttpUserAgent { get; init; }
    public Dictionary<string, string>? HttpHeaders { get; init; }
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] public string? Username { get; init; }
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] public string? Password { get; init; }
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] public string? EpgSyncMode { get; init; }
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] public bool? FastSyncEnabled { get; init; }
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] public string? LiveSyncMode { get; init; }
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] public string? M3uUrl { get; init; }
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] public bool? VodClassificationEnabled { get; init; }
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] public string? PortalUrl { get; init; }
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] public string? StalkerMacAddress { get; init; }
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] public string? DeviceProfile { get; init; }
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] public string? Timezone { get; init; }
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] public string? Locale { get; init; }

    internal static ProviderWireConfiguration From(RemoteProviderConfiguration configuration) => new()
    {
        ServerUrl = configuration.ServerUrl, EpgUrl = configuration.EpgUrl,
        HttpUserAgent = configuration.HttpUserAgent, HttpHeaders = configuration.HttpHeaders,
        Username = configuration.Xtream?.Username, Password = configuration.Xtream?.Password,
        EpgSyncMode = configuration.Stalker is null ? configuration.EpgSyncMode : null,
        FastSyncEnabled = configuration.Xtream?.FastSyncEnabled, LiveSyncMode = configuration.Xtream?.LiveSyncMode,
        M3uUrl = configuration.M3u?.M3uUrl, VodClassificationEnabled = configuration.M3u?.VodClassificationEnabled,
        PortalUrl = configuration.Stalker?.PortalUrl, StalkerMacAddress = configuration.Stalker?.StalkerMacAddress,
        DeviceProfile = configuration.Stalker?.DeviceProfile, Timezone = configuration.Stalker?.Timezone,
        Locale = configuration.Stalker?.Locale
    };
}
