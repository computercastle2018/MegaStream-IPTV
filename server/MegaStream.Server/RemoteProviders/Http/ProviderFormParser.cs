using MegaStream.Server.RemoteProviders.Core;

namespace MegaStream.Server.RemoteProviders.Http;

internal static class ProviderFormParser
{
    public static ReplaceRemoteProviderProfile Parse(IFormCollection form)
    {
        var type = Field(form, "type", 32);
        var configuration = new RemoteProviderConfiguration
        {
            ServerUrl = Optional(form, "serverUrl", 4096), EpgUrl = Optional(form, "epgUrl", 4096),
            HttpUserAgent = Optional(form, "httpUserAgent", 512), HttpHeaders = Headers(Optional(form, "httpHeaders", 16384)),
            EpgSyncMode = Field(form, "epgSyncMode", 16)
        };
        switch (type)
        {
            case RemoteProviderValues.XtreamCodes:
                configuration.Xtream = Xtream(form);
                break;
            case RemoteProviderValues.M3u:
                configuration.M3u = new M3uConfiguration
                {
                    M3uUrl = Field(form, "m3uUrl", 4096), VodClassificationEnabled = Boolean(form, "vodClassificationEnabled")
                };
                break;
            case RemoteProviderValues.StalkerPortal:
                configuration.Stalker = Stalker(form);
                break;
            default: throw new FormatException();
        }
        return new ReplaceRemoteProviderProfile { DisplayName = Field(form, "displayName", 128), Type = type, Configuration = configuration };
    }

    private static XtreamConfiguration Xtream(IFormCollection form) => new()
    {
        Username = Field(form, "username", 256), Password = Field(form, "password", 1024),
        LiveSyncMode = Field(form, "liveSyncMode", 32), FastSyncEnabled = Boolean(form, "fastSyncEnabled")
    };

    private static StalkerConfiguration Stalker(IFormCollection form) => new()
    {
        PortalUrl = Field(form, "portalUrl", 4096), StalkerMacAddress = Field(form, "stalkerMacAddress", 17),
        DeviceProfile = Optional(form, "deviceProfile", 256), Timezone = Optional(form, "timezone", 64),
        Locale = Optional(form, "locale", 32)
    };

    public static string Field(IFormCollection form, string key, int maxLength)
    {
        var field = Optional(form, key, maxLength);
        if (string.IsNullOrWhiteSpace(field)) throw new FormatException();
        return field;
    }

    private static bool Boolean(IFormCollection form, string key) => Field(form, key, 5) switch
    {
        "true" => true, "false" => false, _ => throw new FormatException()
    };

    private static string? Optional(IFormCollection form, string key, int maxLength)
    {
        var fields = form[key];
        if (fields.Count > 1) throw new FormatException();
        var field = fields.Count == 0 ? null : fields[0];
        if (field?.Length > maxLength) throw new FormatException();
        return string.IsNullOrEmpty(field) ? null : field;
    }

    private static Dictionary<string, string>? Headers(string? text)
    {
        if (text is null) return null;
        var headers = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (var line in text.Split('\n', StringSplitOptions.RemoveEmptyEntries))
        {
            var separator = line.IndexOf(':');
            if (separator <= 0 || headers.Count >= 20) throw new FormatException();
            var key = line[..separator].Trim();
            var headerValue = line[(separator + 1)..].Trim();
            if (key.Length > 64 || headerValue.Length > 512 || !headers.TryAdd(key, headerValue)) throw new FormatException();
        }
        return headers;
    }
}
