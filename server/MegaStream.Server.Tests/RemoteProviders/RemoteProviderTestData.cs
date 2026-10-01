using MegaStream.Server.RemoteProviders.Core;

namespace MegaStream.Server.Tests.RemoteProviders;

internal static class RemoteProviderTestData
{
    public static ReplaceRemoteProviderProfile Profile(string type = RemoteProviderValues.XtreamCodes)
    {
        var config = new RemoteProviderConfiguration
        {
            ServerUrl = "https://provider.invalid/private-server-token",
            EpgUrl = "https://epg.invalid/feed?token=epg-secret-value",
            HttpUserAgent = "PrivateProviderAgent-7391",
            HttpHeaders = new() { ["Authorization"] = "Bearer header-secret-value" }
        };
        switch (type)
        {
            case RemoteProviderValues.XtreamCodes:
                config.Xtream = new() { Username = "private-xtream-user", Password = "private-xtream-password", FastSyncEnabled = true, LiveSyncMode = "category_by_category" };
                break;
            case RemoteProviderValues.M3u:
                config.M3u = new() { M3uUrl = "https://playlist.invalid/live?token=m3u-secret-value", VodClassificationEnabled = true };
                break;
            case RemoteProviderValues.StalkerPortal:
                // Provider virtual identity only; never installation/hardware identity.
                config.Stalker = new()
                {
                    PortalUrl = "https://portal.invalid/private-portal-token",
                    StalkerMacAddress = "02:11:22:33:44:55",
                    DeviceProfile = "virtual-profile-secret",
                    Timezone = "Asia/Riyadh",
                    Locale = "en-US"
                };
                break;
            default:
                throw new ArgumentOutOfRangeException(nameof(type));
        }
        return new() { DisplayName = "Living room", Type = type, Configuration = config };
    }

    public static IEnumerable<string> SecretValues(RemoteProviderConfiguration config)
    {
        if (config.ServerUrl is not null) yield return config.ServerUrl;
        if (config.EpgUrl is not null) yield return config.EpgUrl;
        if (config.HttpUserAgent is not null) yield return config.HttpUserAgent;
        if (config.HttpHeaders is not null)
            foreach (var value in config.HttpHeaders.Values) yield return value;
        if (config.Xtream is { } xtream)
        {
            yield return xtream.Username;
            yield return xtream.Password;
        }
        if (config.M3u is { } m3u) yield return m3u.M3uUrl;
        if (config.Stalker is { } stalker)
        {
            yield return stalker.PortalUrl;
            yield return stalker.StalkerMacAddress;
            if (stalker.DeviceProfile is not null) yield return stalker.DeviceProfile;
        }
    }
}
