using System.Text.Json;
using MegaStream.Server.Data;
using MegaStream.Server.Models;
using MegaStream.Server.Services;
using Microsoft.EntityFrameworkCore;

namespace MegaStream.Server.V1;

public sealed class V1DiagnosticReceipt
{
    public Guid InstallationId { get; set; }
    public Guid EventId { get; set; }
    public Guid AppSessionId { get; set; }
    public long Sequence { get; set; }
    public DateTime OccurredAt { get; set; }
    public DateTime ReceivedAt { get; set; }
    public string Kind { get; set; } = "";
    public string PayloadJson { get; set; } = "";
}

public sealed record V1DiagnosticRejection(Guid? EventId, string Code);
public sealed record V1DiagnosticBatchResponse(IReadOnlyList<Guid> AcceptedEventIds,
    IReadOnlyList<Guid> DuplicateEventIds, IReadOnlyList<V1DiagnosticRejection> Rejected, DateTime ServerTime);
public sealed record V1ValidatedDiagnostic(Guid EventId, Guid AppSessionId, long Sequence,
    DateTime OccurredAt, string Kind, string PayloadJson);

public sealed class V1Diagnostics(AppDbContext db)
{
    public static void ConfigureModel(ModelBuilder builder)
    {
        builder.Entity<V1DiagnosticReceipt>(entity =>
        {
            entity.ToTable("V1DiagnosticReceipts");
            entity.HasKey(x => new { x.InstallationId, x.EventId });
            entity.HasIndex(x => x.ReceivedAt);
            // Preserve sub-microsecond event identity across MySQL round-trips for replay comparison.
            entity.Property(x => x.OccurredAt).HasConversion<long>();
            entity.Property(x => x.Kind).HasMaxLength(32).IsRequired();
            entity.Property(x => x.PayloadJson).HasMaxLength(16384).IsRequired();
            entity.HasOne<Installation>().WithMany().HasForeignKey(x => x.InstallationId)
                .OnDelete(DeleteBehavior.Restrict);
        });
    }

    public async Task<V1DiagnosticBatchResponse> BatchAsync(Guid installationId, JsonElement body, CancellationToken ct = default)
    {
        var root = new ClosedObject(body, "schemaVersion events");
        if (root.Integer("schemaVersion") != 1) throw Invalid("unsupported_schema");
        var events = root.Required("events");
        if (events.ValueKind != JsonValueKind.Array || events.GetArrayLength() is < 1 or > 50)
            throw Invalid("invalid_payload");

        await using var tx = await db.Database.BeginTransactionAsync(DomainRules.Isolation(db), ct);
        await DomainRules.LockDevice(db, installationId, ct);
        var installation = await db.Installations.SingleOrDefaultAsync(x => x.Id == installationId, ct)
            ?? throw new DomainException(401, "invalid_device", "Device authentication failed.");
        await db.Entry(installation).ReloadAsync(ct);
        if (installation.Status != InstallationStatus.Active)
            throw new DomainException(403, "installation_disabled", "Installation is disabled.");
        var ids = events.EnumerateArray().Select(EventIdOrNull).OfType<Guid>().Distinct().ToArray();
        var seen = await db.Set<V1DiagnosticReceipt>().Where(x => x.InstallationId == installationId && ids.Contains(x.EventId))
            .ToDictionaryAsync(x => x.EventId, ct);
        var candidateSessions = events.EnumerateArray().Select(x => UuidOrNull(x, "appSessionId"))
            .OfType<Guid>().Select(x => x.ToString("D")).Distinct().ToArray();
        var sessions = await db.DeviceSessions.Where(x => x.InstallationId == installationId && candidateSessions.Contains(x.ClientSessionId))
            .ToDictionaryAsync(x => x.ClientSessionId, StringComparer.Ordinal, ct);
        var accepted = new List<Guid>();
        var duplicates = new List<Guid>();
        var rejected = new List<V1DiagnosticRejection>();
        var now = DateTime.UtcNow;
        foreach (var item in events.EnumerateArray())
        {
            var id = EventIdOrNull(item);
            V1ValidatedDiagnostic validated;
            try { validated = ValidateEvent(item); }
            catch (DomainException error)
            {
                rejected.Add(new(id, error.Code));
                continue;
            }
            if (seen.TryGetValue(validated.EventId, out var previous))
            {
                if (previous.AppSessionId == validated.AppSessionId && previous.Sequence == validated.Sequence &&
                    previous.OccurredAt == validated.OccurredAt && previous.Kind == validated.Kind && previous.PayloadJson == validated.PayloadJson)
                    duplicates.Add(validated.EventId);
                else rejected.Add(new(validated.EventId, "duplicate_conflict"));
                continue;
            }
            try { await V1SessionClaims.ClaimAsync(db, installationId, validated.AppSessionId, now, ct); }
            catch (DomainException error) when (error.Code == "invalid_session")
            {
                rejected.Add(new(validated.EventId, "invalid_session"));
                continue;
            }
            var clientSessionId = validated.AppSessionId.ToString("D");
            if (!sessions.TryGetValue(clientSessionId, out var session))
            {
                session = new DeviceSession
                {
                    InstallationId = installationId, ClientSessionId = clientSessionId,
                    StartedAt = validated.OccurredAt < now ? validated.OccurredAt : now, LastHeartbeatAt = now
                };
                sessions.Add(clientSessionId, session);
                db.DeviceSessions.Add(session);
            }
            if (validated.Kind == "app_ended")
            {
                using var payload = JsonDocument.Parse(validated.PayloadJson);
                session.EndedAt = validated.OccurredAt;
                session.ExitReason = payload.RootElement.GetProperty("reason").GetString()!;
            }
            var receipt = new V1DiagnosticReceipt
            {
                InstallationId = installationId, EventId = validated.EventId, AppSessionId = validated.AppSessionId,
                Sequence = validated.Sequence, OccurredAt = validated.OccurredAt, ReceivedAt = now,
                Kind = validated.Kind, PayloadJson = validated.PayloadJson
            };
            db.Set<V1DiagnosticReceipt>().Add(receipt);
            // The dashboard receives only controlled summary tokens, never a free-form message or stack.
            db.DiagnosticEvents.Add(new DiagnosticEvent
            {
                InstallationId = installationId, EventId = validated.EventId,
                SessionId = validated.AppSessionId.ToString("D"), OccurredAt = validated.OccurredAt, CreatedAt = now,
                Category = validated.Kind, Message = validated.Kind,
                Level = validated.Kind is "crash" or "anr" ? "error" : validated.Kind is "playback_problem" or "memory_pressure" ? "warning" : "info"
            });
            seen.Add(validated.EventId, receipt);
            accepted.Add(validated.EventId);
        }
        await db.SaveChangesAsync(ct);
        await tx.CommitAsync(ct);
        return new(accepted, duplicates, rejected, now);
    }

    public static V1ValidatedDiagnostic ValidateEvent(JsonElement element)
    {
        var item = new ClosedObject(element, "eventId appSessionId sequence occurredAt kind payload");
        var eventId = item.Uuid("eventId");
        Guid sessionId;
        long sequence;
        try { sessionId = item.Uuid("appSessionId"); }
        catch (DomainException) { throw Invalid("invalid_session"); }
        try { sequence = item.Integer("sequence"); }
        catch (DomainException) { throw Invalid("invalid_sequence"); }
        var timeElement = item.Required("occurredAt");
        if (timeElement.ValueKind != JsonValueKind.String) throw Invalid("invalid_time");
        var timestamp = timeElement.GetString()!;
        if (timestamp.Length > 40 || !(timestamp.EndsWith('Z') || timestamp.EndsWith("+00:00", StringComparison.Ordinal)) ||
            !timeElement.TryGetDateTimeOffset(out var occurredAt) || occurredAt.Offset != TimeSpan.Zero)
            throw Invalid("invalid_time");
        var now = DateTimeOffset.UtcNow;
        if (occurredAt < now.AddDays(-30) || occurredAt > now.AddMinutes(5)) throw Invalid("invalid_time");
        var kind = item.Text("kind", 32);
        var payload = ValidatePayload(kind, item.Required("payload"));
        var json = JsonSerializer.Serialize(payload);
        if (json.Length > 16384) throw Invalid("payload_too_large");
        return new(eventId, sessionId, sequence, occurredAt.UtcDateTime, kind, json);
    }

    private static Dictionary<string, object> ValidatePayload(string kind, JsonElement element)
    {
        var fields = kind switch
        {
            "app_started" => "appVersionCode appVersionName",
            "playback_started" => "playbackSessionId channelName? sourceType streamType playbackMode",
            "playback_sample" => "playbackSessionId videoCodec audioCodec videoDecoder audioDecoder width height droppedFrames rebufferCount bufferedMs ttffMs memory?",
            "playback_problem" => "playbackSessionId category code httpStatus? retryAttempt memory?",
            "memory_pressure" => "memory trimLevel",
            "crash" => "exceptionType frames",
            "anr" => "evidence durationMs? frames",
            "playback_ended" => "playbackSessionId reason durationMs",
            "app_ended" => "reason evidence",
            _ => throw Invalid("invalid_kind")
        };
        var p = new ClosedObject(element, fields);
        var result = new Dictionary<string, object>(StringComparer.Ordinal);
        void Number(string name, long max = long.MaxValue, long min = 0) => result[name] = p.Integer(name, min, max);
        void Enum(string name, string allowed) => result[name] = p.Enum(name, allowed);
        if (kind.StartsWith("playback_", StringComparison.Ordinal)) result["playbackSessionId"] = p.Uuid("playbackSessionId");
        if (p.Has("memory")) result["memory"] = Memory(p.Required("memory"));
        switch (kind)
        {
            case "app_started":
                Number("appVersionCode", int.MaxValue, 1);
                result["appVersionName"] = p.Text("appVersionName", 32);
                break;
            case "playback_started":
                if (p.Has("channelName")) result["channelName"] = p.Text("channelName", 120);
                Enum("sourceType", "xtream_codes m3u stalker_portal local unknown");
                Enum("streamType", "hls dash mpeg_ts progressive rtsp smooth_streaming unknown");
                Enum("playbackMode", "live vod catch_up preview multiview tv_input recording");
                break;
            case "playback_sample":
                Enum("videoCodec", "unknown h264 hevc av1 vp9 mpeg2 other");
                Enum("audioCodec", "unknown aac ac3 eac3 dts mp3 opus other");
                Enum("videoDecoder", "hardware software unknown");
                Enum("audioDecoder", "platform ffmpeg unknown");
                Number("width", int.MaxValue); Number("height", int.MaxValue);
                foreach (var name in new[] { "droppedFrames", "rebufferCount", "bufferedMs", "ttffMs" }) Number(name);
                break;
            case "playback_problem":
                var category = p.Enum("category", "network http decoder drm source timeout stall unknown");
                var code = p.Text("code", 64);
                if (!ProblemCategories.TryGetValue(code, out var categories) || !categories.Split(' ').Contains(category, StringComparer.Ordinal))
                    throw Invalid();
                result["category"] = category; result["code"] = code;
                if (p.Has("httpStatus")) Number("httpStatus", 599, 100);
                Number("retryAttempt");
                break;
            case "memory_pressure":
                Enum("trimLevel", "running_moderate running_low running_critical background moderate complete low_memory unknown");
                break;
            case "crash":
                result["exceptionType"] = p.Identifier("exceptionType", 120);
                result["frames"] = Frames(p.Required("frames"));
                break;
            case "anr":
                Enum("evidence", "os_exit_reason watchdog_suspected");
                if (p.Has("durationMs")) Number("durationMs");
                result["frames"] = Frames(p.Required("frames"));
                break;
            case "playback_ended":
                Enum("reason", "user_stop channel_changed completed error license_blocked background sleep_timer unknown");
                Number("durationMs");
                break;
            case "app_ended":
                Enum("reason", "clean_exit os_exit java_crash anr oom low_memory_kill native_crash signal user_requested system_kill unknown");
                Enum("evidence", "reported recovered_os recovered_marker inferred watchdog_suspected");
                break;
        }
        return result;
    }

    private static Dictionary<string, object> Memory(JsonElement element)
    {
        var p = new ClosedObject(element, "javaUsedBytes javaMaxBytes nativeHeapBytes pssBytes availableSystemBytes lowMemory");
        var result = new Dictionary<string, object>();
        foreach (var name in new[] { "javaUsedBytes", "javaMaxBytes", "nativeHeapBytes", "pssBytes", "availableSystemBytes" })
            result[name] = p.Integer(name, 0, 1L << 40);
        if ((long)result["javaUsedBytes"] > (long)result["javaMaxBytes"]) throw Invalid();
        var low = p.Required("lowMemory");
        if (low.ValueKind is not (JsonValueKind.True or JsonValueKind.False)) throw Invalid();
        result["lowMemory"] = low.GetBoolean();
        return result;
    }

    private static List<Dictionary<string, object>> Frames(JsonElement element)
    {
        if (element.ValueKind != JsonValueKind.Array || element.GetArrayLength() > 32) throw Invalid();
        var result = new List<Dictionary<string, object>>();
        foreach (var frame in element.EnumerateArray())
        {
            var f = new ClosedObject(frame, "className methodName line?");
            var value = new Dictionary<string, object>
            {
                ["className"] = f.Identifier("className", 160), ["methodName"] = f.Identifier("methodName", 120)
            };
            if (f.Has("line")) value["line"] = f.Integer("line", 0, int.MaxValue);
            result.Add(value);
        }
        return result;
    }

    private static readonly IReadOnlyDictionary<string, string> ProblemCategories = new Dictionary<string, string>(StringComparer.Ordinal)
    {
        ["network_unavailable"] = "network", ["dns_failure"] = "network", ["tls_failure"] = "network",
        ["connection_timeout"] = "timeout network", ["read_timeout"] = "timeout network",
        ["http_400"] = "http", ["http_401"] = "http", ["http_403"] = "http", ["http_404"] = "http",
        ["http_408"] = "http", ["http_429"] = "http", ["http_5xx"] = "http",
        ["decoder_init_failed"] = "decoder", ["decoder_query_failed"] = "decoder", ["decoder_runtime_error"] = "decoder",
        ["codec_unsupported"] = "decoder", ["drm_failed"] = "drm", ["source_invalid"] = "source",
        ["source_io"] = "source", ["manifest_parse_failed"] = "source", ["behind_live_window"] = "source",
        ["stall_detected"] = "stall timeout", ["retry_exhausted"] = "stall timeout", ["buffer_underrun"] = "stall timeout",
        ["unknown"] = "unknown"
    };

    private static Guid? EventIdOrNull(JsonElement item) => UuidOrNull(item, "eventId");

    private static Guid? UuidOrNull(JsonElement item, string propertyName)
    {
        if (item.ValueKind != JsonValueKind.Object) return null;
        var properties = item.EnumerateObject().Where(x => x.NameEquals(propertyName)).ToArray();
        return properties.Length == 1 && properties[0].Value.ValueKind == JsonValueKind.String &&
            Guid.TryParseExact(properties[0].Value.GetString(), "D", out var id) && id != Guid.Empty ? id : null;
    }

    private static DomainException Invalid(string code = "invalid_payload") => new(400, code, "Invalid diagnostic schema.");

    private sealed class ClosedObject
    {
        private readonly Dictionary<string, JsonElement> values = new(StringComparer.Ordinal);
        public ClosedObject(JsonElement element, string schema)
        {
            if (element.ValueKind != JsonValueKind.Object) throw Invalid();
            var fields = schema.Split(' ');
            var allowed = fields.Select(x => x.TrimEnd('?')).ToHashSet(StringComparer.Ordinal);
            foreach (var property in element.EnumerateObject())
                if (!allowed.Contains(property.Name) || !values.TryAdd(property.Name, property.Value)) throw Invalid();
            foreach (var field in fields.Where(x => !x.EndsWith('?')))
                if (!values.ContainsKey(field)) throw Invalid();
        }
        public bool Has(string name) => values.ContainsKey(name);
        public JsonElement Required(string name) => values.TryGetValue(name, out var value) ? value : throw Invalid();
        public long Integer(string name, long min = 0, long max = long.MaxValue)
        {
            var element = Required(name);
            if (element.ValueKind != JsonValueKind.Number || !element.TryGetInt64(out var value) || value < min || value > max) throw Invalid();
            return value;
        }
        public string Text(string name, int max)
        {
            var element = Required(name);
            if (element.ValueKind != JsonValueKind.String) throw Invalid();
            var value = element.GetString()!;
            if (value.Length > max || value.Any(char.IsControl)) throw Invalid();
            try { Sanitizer.ValidateDiagnostic(value); }
            catch (DomainException) { throw Invalid("unsafe_content"); }
            return value;
        }
        public Guid Uuid(string name)
        {
            if (!Guid.TryParseExact(Text(name, 36), "D", out var id) || id == Guid.Empty) throw Invalid();
            return id;
        }
        public string Enum(string name, string allowed)
        {
            var value = Text(name, 64);
            return allowed.Split(' ').Contains(value, StringComparer.Ordinal) ? value : throw Invalid();
        }
        public string Identifier(string name, int max)
        {
            var value = Text(name, max);
            if (value.Length == 0 || value.Any(c => !(char.IsAsciiLetterOrDigit(c) || c is '_' or '$' or '.' or '<' or '>')))
                throw Invalid();
            return value;
        }
    }
}
