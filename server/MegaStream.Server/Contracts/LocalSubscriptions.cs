using System.ComponentModel.DataAnnotations;
using System.Text.Json.Serialization;

namespace MegaStream.Server.Contracts;

[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed class LocalSubscriptionsRequest
{
    [Required, MaxLength(100)] public List<LocalSubscription> Subscriptions { get; set; } = [];
}

[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed class LocalSubscription
{
    [Range(1, long.MaxValue)] public long LocalId { get; set; }
    [Required, StringLength(128)] public string Name { get; set; } = "";
    [Required, RegularExpression("xtream_codes|m3u|stalker_portal")] public string Type { get; set; } = "";
    public bool Enabled { get; set; }
    [Required, RegularExpression("active|partial|expired|disabled|error|unknown")] public string Status { get; set; } = "";
    [Range(0L, 253402300799999L)] public long? ExpiresAt { get; set; }
    [Range(0L, 253402300799999L)] public long? StartedAt { get; set; }
    [Range(1, int.MaxValue)] public int MaxConnections { get; set; }

    [JsonIgnore] public string DisplayState => ExpiresAt <= DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() || Status == "expired"
        ? "منتهي" : !Enabled ? "غير مفعّل" : Status switch
        { "active" => "مفعّل", "partial" => "مفعّل جزئياً", "disabled" => "متوقف", "error" => "خطأ", _ => "غير معروف" };
}
