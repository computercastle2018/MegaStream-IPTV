using System.Text.Json;
using System.Text.Json.Nodes;
using MegaStream.Server.Models;
using MegaStream.Server.Services;
using MegaStream.Server.V1;
using Microsoft.EntityFrameworkCore;
using Xunit;

namespace MegaStream.Server.Tests;

public sealed class V1DiagnosticsTests
{
    private const string Memory = "{\"javaUsedBytes\":0,\"javaMaxBytes\":1099511627776,\"nativeHeapBytes\":0,\"pssBytes\":0,\"availableSystemBytes\":0,\"lowMemory\":false}";
    private const string PlaybackId = "8c06536b-7bdc-4cee-aed6-4585975f0a61";

    public static TheoryData<string, string> ValidPayloads => new()
    {
        { "app_started", "{\"appVersionCode\":1,\"appVersionName\":\"1.0\"}" },
        { "playback_started", $$"""{"playbackSessionId":"{{PlaybackId}}","channelName":"News","sourceType":"m3u","streamType":"hls","playbackMode":"live"}""" },
        { "playback_sample", $$"""{"playbackSessionId":"{{PlaybackId}}","videoCodec":"hevc","audioCodec":"aac","videoDecoder":"hardware","audioDecoder":"platform","width":1920,"height":1080,"droppedFrames":0,"rebufferCount":0,"bufferedMs":0,"ttffMs":0,"memory":{{Memory}}}""" },
        { "playback_problem", $$"""{"playbackSessionId":"{{PlaybackId}}","category":"http","code":"http_403","httpStatus":403,"retryAttempt":0}""" },
        { "memory_pressure", $$"""{"memory":{{Memory}},"trimLevel":"running_low"}""" },
        { "crash", "{\"exceptionType\":\"java.lang.IllegalStateException\",\"frames\":[{\"className\":\"app.Player$Worker\",\"methodName\":\"<init>\",\"line\":1}]}" },
        { "anr", "{\"evidence\":\"watchdog_suspected\",\"frames\":[],\"durationMs\":0}" },
        { "playback_ended", $$"""{"playbackSessionId":"{{PlaybackId}}","reason":"user_stop","durationMs":0}""" },
        { "app_ended", "{\"reason\":\"oom\",\"evidence\":\"recovered_os\"}" }
    };

    [Theory]
    [MemberData(nameof(ValidPayloads))]
    public void All_kinds_accept_their_closed_typed_payload(string kind, string payload)
    {
        var result = V1Diagnostics.ValidateEvent(Event(kind, payload));
        Assert.Equal(kind, result.Kind);
        using var parsed = JsonDocument.Parse(result.PayloadJson);
        Assert.Equal(JsonValueKind.Object, parsed.RootElement.ValueKind);
        Assert.DoesNotContain("eventId", result.PayloadJson);
    }

    [Theory]
    [InlineData("app_started", "{\"appVersionCode\":1,\"appVersionName\":\"x\",\"message\":\"raw\"}")]
    [InlineData("app_started", "{\"appVersionCode\":1,\"appVersionCode\":2,\"appVersionName\":\"x\"}")]
    [InlineData("app_started", "{\"appVersionCode\":0,\"appVersionName\":\"x\"}")]
    [InlineData("app_started", "{\"appVersionCode\":1.5,\"appVersionName\":\"x\"}")]
    [InlineData("app_started", "{\"appVersionCode\":1}")]
    [InlineData("app_started", "{\"appVersionCode\":1,\"appVersionName\":null}")]
    [InlineData("crash", "{\"exceptionType\":\"Exception: failed\",\"frames\":[]}")]
    [InlineData("crash", "{\"exceptionType\":\"Exception\",\"frames\":[{\"className\":\"Player\",\"methodName\":\"run\",\"fileName\":\"private\"}]}")]
    [InlineData("anr", "{\"evidence\":\"reported\",\"frames\":[]}")]
    public void Malformed_nested_payloads_are_rejected(string kind, string payload) =>
        Assert.Throws<DomainException>(() => V1Diagnostics.ValidateEvent(Event(kind, payload)));

    [Theory]
    [InlineData("https://provider.invalid/private")]
    [InlineData("%68%74%74%70%73%3A%2F%2Fx.io")]
    [InlineData("password=hidden")]
    [InlineData("Bearer secret")]
    public void Sensitive_values_are_rejected_not_redacted_into_storage(string value)
    {
        var payload = JsonSerializer.Serialize(new { appVersionCode = 1, appVersionName = value });
        var error = Assert.Throws<DomainException>(() => V1Diagnostics.ValidateEvent(Event("app_started", payload)));
        Assert.DoesNotContain(value, error.Message);
    }

    [Theory]
    [InlineData("network", "dns_failure", true)]
    [InlineData("timeout", "connection_timeout", true)]
    [InlineData("network", "connection_timeout", true)]
    [InlineData("stall", "buffer_underrun", true)]
    [InlineData("unknown", "unknown", true)]
    [InlineData("network", "invented_code", false)]
    [InlineData("network", "http_403", false)]
    [InlineData("http", "unknown", false)]
    [InlineData("http", "http_500", false)]
    public void Problem_codes_require_allowlisted_category_compatibility(string category, string code, bool valid)
    {
        var payload = JsonSerializer.Serialize(new { playbackSessionId = PlaybackId, category, code, retryAttempt = 0 });
        var item = Event("playback_problem", payload);
        if (valid) Assert.Equal("playback_problem", V1Diagnostics.ValidateEvent(item).Kind);
        else Assert.Throws<DomainException>(() => V1Diagnostics.ValidateEvent(item));
    }

    [Theory]
    [InlineData("sequence", "-1")]
    [InlineData("sequence", "9223372036854775808")]
    [InlineData("sequence", "\"1\"")]
    [InlineData("occurredAt", "\"2026-01-01T12:00:00\"")]
    [InlineData("occurredAt", "\"2026-01-01T12:00:00+01:00\"")]
    [InlineData("eventId", "\"not-a-uuid\"")]
    [InlineData("appSessionId", "\"00000000-0000-0000-0000-000000000000\"")]
    [InlineData("unknown", "true")]
    public void Event_envelope_rejects_invalid_values(string field, string value)
    {
        var node = JsonNode.Parse(Event("app_started", "{\"appVersionCode\":1,\"appVersionName\":\"1\"}").GetRawText())!;
        node[field] = JsonNode.Parse(value);
        Assert.Throws<DomainException>(() => V1Diagnostics.ValidateEvent(Parse(node.ToJsonString())));
    }

    [Fact]
    public void Memory_and_frames_enforce_nested_types_and_bounds()
    {
        foreach (var memory in new[] { Memory.Replace("false", "\"false\""), Memory.Replace("\"javaUsedBytes\":0", "\"javaUsedBytes\":-1"), Memory.Replace("\"pssBytes\":0,", ""), Memory.Replace("{", "{\"url\":\"x\",") })
            Assert.Throws<DomainException>(() => V1Diagnostics.ValidateEvent(Event("memory_pressure", $$"""{"memory":{{memory}},"trimLevel":"unknown"}""")));
        var frame = "{\"className\":\"Player\",\"methodName\":\"run\"}";
        var frames = string.Join(',', Enumerable.Repeat(frame, 33));
        Assert.Throws<DomainException>(() => V1Diagnostics.ValidateEvent(Event("crash", $$"""{"exceptionType":"Exception","frames":[{{frames}}]}""")));
    }

    [Fact]
    public async Task Partial_batch_stores_only_valid_events_and_retries_are_stable()
    {
        await using var fixture = await TestDatabase.CreateAsync();
        var installation = new Installation { TokenHash = "diagnostics-test" };
        var session = Guid.NewGuid();
        fixture.Db.Installations.Add(installation);
        fixture.Db.DeviceSessions.Add(new DeviceSession { InstallationId = installation.Id, ClientSessionId = session.ToString("D") });
        await fixture.Db.SaveChangesAsync();
        var id = Guid.NewGuid();
        var valid = Event("app_started", "{\"appVersionCode\":1,\"appVersionName\":\"1.0\"}", id, session);
        var invalid = Event("crash", "{\"exceptionType\":\"Exception\",\"frames\":[],\"message\":\"private\"}", session: session);
        var service = new V1Diagnostics(fixture.Db);
        var response = await service.BatchAsync(installation.Id, Batch(valid, invalid, valid));
        Assert.Equal(new[] { id }, response.AcceptedEventIds);
        Assert.Equal(new[] { id }, response.DuplicateEventIds);
        Assert.Single(response.Rejected);
        var receipt = await fixture.Db.Set<V1DiagnosticReceipt>().SingleAsync();
        Assert.Equal(session, receipt.AppSessionId);
        Assert.DoesNotContain("private", receipt.PayloadJson);
        var core = await fixture.Db.DiagnosticEvents.SingleAsync();
        Assert.Equal(id, core.EventId);
        Assert.Equal("app_started", core.Message);
        Assert.Empty(core.StackTrace);
        await using var replayDb = fixture.NewContext();
        var replayService = new V1Diagnostics(replayDb);
        var retry = await replayService.BatchAsync(installation.Id, Batch(valid));
        Assert.Empty(retry.AcceptedEventIds);
        Assert.Equal(new[] { id }, retry.DuplicateEventIds);
        Assert.Empty(retry.Rejected);
        var changed = JsonNode.Parse(valid.GetRawText())!;
        changed["payload"]!["appVersionName"] = "2.0";
        var conflict = await service.BatchAsync(installation.Id, Batch(Parse(changed.ToJsonString())));
        Assert.Equal("duplicate_conflict", Assert.Single(conflict.Rejected).Code);
        Assert.Empty(conflict.DuplicateEventIds);
        Assert.Equal(receipt.PayloadJson, (await fixture.Db.Set<V1DiagnosticReceipt>().SingleAsync()).PayloadJson);
    }

    [Fact]
    public async Task Preheartbeat_event_infers_session_without_refreshing_installation_presence()
    {
        await using var fixture = await TestDatabase.CreateAsync();
        var installation = new Installation { TokenHash = "preheartbeat", LastSeenAt = DateTime.UtcNow.AddDays(-1) };
        var lastSeen = installation.LastSeenAt;
        fixture.Db.Installations.Add(installation);
        await fixture.Db.SaveChangesAsync();
        var session = Guid.NewGuid();
        var response = await new V1Diagnostics(fixture.Db).BatchAsync(installation.Id,
            Batch(Event("crash", "{\"exceptionType\":\"Exception\",\"frames\":[]}", session: session)));
        Assert.Single(response.AcceptedEventIds);
        Assert.Empty(response.Rejected);
        Assert.Equal(session.ToString("D"), (await fixture.Db.DeviceSessions.SingleAsync()).ClientSessionId);
        Assert.Equal(-1L, (await fixture.Db.Set<V1SessionState>().SingleAsync()).LastSequence);
        Assert.Equal(lastSeen, installation.LastSeenAt);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task App_ended_updates_only_matching_session_and_replays_never_rewrite_terminal_state(bool existingSession)
    {
        await using var fixture = await TestDatabase.CreateAsync();
        var installation = new Installation { TokenHash = "terminal", LastSeenAt = DateTime.UtcNow.AddDays(-1) };
        var lastSeen = installation.LastSeenAt;
        var sessionId = Guid.NewGuid();
        var otherSession = new DeviceSession { InstallationId = installation.Id, ClientSessionId = Guid.NewGuid().ToString("D") };
        fixture.Db.Installations.Add(installation);
        fixture.Db.DeviceSessions.Add(otherSession);
        if (existingSession)
            fixture.Db.DeviceSessions.Add(new DeviceSession { InstallationId = installation.Id, ClientSessionId = sessionId.ToString("D") });
        await fixture.Db.SaveChangesAsync();
        var service = new V1Diagnostics(fixture.Db);
        var first = Event("app_ended", "{\"reason\":\"clean_exit\",\"evidence\":\"reported\"}", session: sessionId);
        var accepted = await service.BatchAsync(installation.Id, Batch(first));
        Assert.Single(accepted.AcceptedEventIds);
        var matched = await fixture.Db.DeviceSessions.SingleAsync(x => x.ClientSessionId == sessionId.ToString("D"));
        Assert.Equal(first.GetProperty("occurredAt").GetDateTime(), matched.EndedAt);
        Assert.Equal("clean_exit", matched.ExitReason);
        var later = JsonNode.Parse(Event("app_ended", "{\"reason\":\"os_exit\",\"evidence\":\"recovered_os\"}", session: sessionId).GetRawText())!;
        later["occurredAt"] = first.GetProperty("occurredAt").GetDateTime().AddSeconds(1).ToString("O");
        await service.BatchAsync(installation.Id, Batch(Parse(later.ToJsonString())));
        var terminalAt = matched.EndedAt;
        var conflict = JsonNode.Parse(first.GetRawText())!;
        conflict["payload"]!["reason"] = "oom";
        var replay = await service.BatchAsync(installation.Id, Batch(first, Parse(conflict.ToJsonString())));
        Assert.Single(replay.DuplicateEventIds);
        Assert.Equal("duplicate_conflict", Assert.Single(replay.Rejected).Code);
        await using var verification = fixture.NewContext();
        var persisted = await verification.DeviceSessions.SingleAsync(x => x.ClientSessionId == sessionId.ToString("D"));
        Assert.Equal(terminalAt, persisted.EndedAt);
        Assert.Equal("os_exit", persisted.ExitReason);
        var untouched = await verification.DeviceSessions.SingleAsync(x => x.Id == otherSession.Id);
        Assert.Null(untouched.EndedAt);
        Assert.Null(untouched.ExitReason);
        Assert.Equal(lastSeen, (await verification.Installations.SingleAsync()).LastSeenAt);
    }

    [Fact]
    public async Task Another_installations_session_is_not_authorized()
    {
        await using var fixture = await TestDatabase.CreateAsync();
        var owner = new Installation { TokenHash = "owner" };
        var other = new Installation { TokenHash = "other" };
        var session = Guid.NewGuid();
        fixture.Db.Installations.AddRange(owner, other);
        fixture.Db.DeviceSessions.Add(new DeviceSession { InstallationId = owner.Id, ClientSessionId = session.ToString("D") });
        await fixture.Db.SaveChangesAsync();
        var response = await new V1Diagnostics(fixture.Db).BatchAsync(other.Id,
            Batch(Event("app_started", "{\"appVersionCode\":1,\"appVersionName\":\"1\"}", session: session)));
        Assert.Equal("invalid_session", Assert.Single(response.Rejected).Code);
        Assert.Empty(response.AcceptedEventIds);
        Assert.Empty(await fixture.Db.Set<V1DiagnosticReceipt>().ToListAsync());
        Assert.Empty(await fixture.Db.DiagnosticEvents.ToListAsync());
    }

    [Fact]
    public async Task Disabled_installation_cannot_store_diagnostics()
    {
        await using var fixture = await TestDatabase.CreateAsync();
        var installation = new Installation { TokenHash = "disabled", Status = InstallationStatus.Revoked };
        fixture.Db.Installations.Add(installation);
        await fixture.Db.SaveChangesAsync();
        var error = await Assert.ThrowsAsync<DomainException>(() => new V1Diagnostics(fixture.Db).BatchAsync(installation.Id,
            Batch(Event("app_started", "{\"appVersionCode\":1,\"appVersionName\":\"1\"}"))));
        Assert.Equal(403, error.StatusCode);
        Assert.Equal("installation_disabled", error.Code);
        Assert.Empty(await fixture.Db.DiagnosticEvents.ToListAsync());
    }

    [Theory]
    [InlineData(50, true)]
    [InlineData(51, false)]
    public async Task Batch_size_limit_is_inclusive(int count, bool valid)
    {
        await using var fixture = await TestDatabase.CreateAsync();
        var installation = new Installation { TokenHash = "batch-size" };
        fixture.Db.Installations.Add(installation);
        await fixture.Db.SaveChangesAsync();
        var events = Enumerable.Range(0, count).Select(_ => Parse("{}" )).ToArray();
        var service = new V1Diagnostics(fixture.Db);
        if (valid) Assert.Equal(count, (await service.BatchAsync(installation.Id, Batch(events))).Rejected.Count);
        else await Assert.ThrowsAsync<DomainException>(() => service.BatchAsync(installation.Id, Batch(events)));
        Assert.Empty(await fixture.Db.Set<V1DiagnosticReceipt>().ToListAsync());
    }

    [Theory]
    [InlineData("{\"schemaVersion\":1,\"events\":[]}")]
    [InlineData("{\"schemaVersion\":2,\"events\":[{}]}")]
    [InlineData("{\"schemaVersion\":1,\"schemaVersion\":1,\"events\":[{}]}")]
    [InlineData("{\"schemaVersion\":1,\"events\":[{}],\"extra\":true}")]
    public async Task Invalid_batch_envelopes_fail_before_any_event_is_written(string body)
    {
        await using var fixture = await TestDatabase.CreateAsync();
        await Assert.ThrowsAsync<DomainException>(() => new V1Diagnostics(fixture.Db).BatchAsync(Guid.NewGuid(), Parse(body)));
        Assert.Empty(await fixture.Db.Set<V1DiagnosticReceipt>().ToListAsync());
    }

    private static JsonElement Event(string kind, string payload, Guid? id = null, Guid? session = null) =>
        Parse($$"""{"eventId":"{{id ?? Guid.NewGuid()}}","appSessionId":"{{session ?? Guid.NewGuid()}}","sequence":0,"occurredAt":"{{DateTime.UtcNow.ToString("O")}}","kind":"{{kind}}","payload":{{payload}}}""");
    private static JsonElement Batch(params JsonElement[] events) =>
        Parse("{\"schemaVersion\":1,\"events\":[" + string.Join(',', events.Select(x => x.GetRawText())) + "]}");
    private static JsonElement Parse(string json)
    {
        using var document = JsonDocument.Parse(json);
        return document.RootElement.Clone();
    }
}
