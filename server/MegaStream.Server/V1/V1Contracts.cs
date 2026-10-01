using System.Text.Json;
using System.Text.Json.Serialization;

namespace MegaStream.Server.V1;

[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed record V1RegisterRequest
{
    public required Guid InstallationId { get; init; }
    public required string Credential { get; init; }
    public required int AppVersionCode { get; init; }
    public required string PackageName { get; init; }
    public required string Channel { get; init; }
    public required string AppVersionName { get; init; }
    public required string Manufacturer { get; init; }
    public required string Model { get; init; }
    public required int AndroidApi { get; init; }
    public required string AndroidRelease { get; init; }
    public required string Abi { get; init; }
    public required string Locale { get; init; }
    public required bool ManagedDevice { get; init; }
}

[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed record V1ActivateRequest
{
    public required string LicenseKey { get; init; }
}

[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed record V1PollRequest
{
    public required string Code { get; init; }
    public required string PollToken { get; init; }
}

[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed record V1HeartbeatRequest
{
    public required Guid AppSessionId { get; init; }
    public required long Sequence { get; init; }
    public required string Mode { get; init; }
    public required int AppVersionCode { get; init; }
    public required string PackageName { get; init; }
    public required string Channel { get; init; }
    public required string AppVersionName { get; init; }
    public required bool ManagedDevice { get; init; }
    public V1Memory? Memory { get; init; }
    public V1RecoveredExit? RecoveredExit { get; init; }
}

[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed record V1Memory
{
    public required long JavaUsedBytes { get; init; }
    public required long JavaMaxBytes { get; init; }
    public required long NativeHeapBytes { get; init; }
    public required long PssBytes { get; init; }
    public required long AvailableSystemBytes { get; init; }
    public required bool LowMemory { get; init; }
}

[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed record V1RecoveredExit
{
    public required string Reason { get; init; }
    public required string Evidence { get; init; }
    [JsonConverter(typeof(V1UtcTimestampConverter))]
    public DateTimeOffset? OccurredAt { get; init; }
}

public sealed class V1UtcTimestampConverter : JsonConverter<DateTimeOffset?>
{
    public override DateTimeOffset? Read(ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options)
    {
        if (reader.TokenType == JsonTokenType.Null) return null;
        if (reader.TokenType != JsonTokenType.String) throw new JsonException("Timestamp must be a UTC string.");
        var timestamp = reader.GetString()!;
        if (timestamp.Length > 40 || !(timestamp.EndsWith('Z') || timestamp.EndsWith("+00:00", StringComparison.Ordinal)) ||
            !reader.TryGetDateTimeOffset(out var parsed) || parsed.Offset != TimeSpan.Zero)
            throw new JsonException("Timestamp must have an explicit UTC offset.");
        return parsed;
    }

    public override void Write(Utf8JsonWriter writer, DateTimeOffset? timestamp, JsonSerializerOptions options)
    {
        if (timestamp is null) writer.WriteNullValue();
        else writer.WriteStringValue(timestamp.Value.UtcDateTime);
    }
}

public sealed record V1RegisterResponse(Guid InstallationId, DateTime RegisteredAt);
public sealed record V1Decision(string State, Guid? LicenseId, long? LicenseRevision, DateTime? StartsAt, DateTime? EndsAt,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] DateTime? OfflineUntil = null);
public sealed record V1DevicePolicy(string KioskMode, bool AllowLocalExit);
public sealed record V1EntitlementResponse(V1Decision Decision, DateTime ServerTime, int RefreshAfterSeconds,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] string? Lease = null,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] V1DevicePolicy? DevicePolicy = null);
public sealed record V1ActivationPendingResponse(string Status, DateTime ServerTime, int RetryAfterSeconds = 5);
