using System.Text.Json;
using System.Text.RegularExpressions;
using MegaStream.Server.Services;

namespace MegaStream.Server.RemoteProviders.Core;

public static class ConfigurationValidator
{
    public static void Validate(ReplaceRemoteProviderProfile request)
    {
        if (request is null || string.IsNullOrWhiteSpace(request.DisplayName) || request.DisplayName.Length > 128 || request.DisplayName.Any(char.IsControl)) Invalid();
        try { Sanitizer.ValidateDiagnostic(request.DisplayName); }
        catch (DomainException) { Invalid(); }
        var configuration = request.Configuration;
        if (configuration is null) Invalid();
        Url(configuration.ServerUrl, false); Url(configuration.EpgUrl, false);
        Text(configuration.HttpUserAgent, 512);
        Headers(configuration.HttpHeaders);
        EpgSync(configuration.EpgSyncMode);
        switch (request.Type)
        {
            case RemoteProviderValues.XtreamCodes: Xtream(configuration); break;
            case RemoteProviderValues.M3u: M3u(configuration); break;
            case RemoteProviderValues.StalkerPortal: Stalker(configuration); break;
            default: Invalid(); break;
        }
        if (JsonSerializer.SerializeToUtf8Bytes(configuration).Length > 24576) Invalid();
    }

    private static void Headers(Dictionary<string, string>? headers)
    {
        if (headers is null) return;
        if (headers.Count > 20 || headers.Keys.Distinct(StringComparer.OrdinalIgnoreCase).Count() != headers.Count) Invalid();
        foreach (var header in headers)
        {
            if (header.Value is null || header.Key.Length is < 1 or > 64 || !Regex.IsMatch(header.Key, "^[A-Za-z0-9!#$%&'*+.^_`|~-]+$") ||
                header.Key.Equals("Host", StringComparison.OrdinalIgnoreCase) || header.Key.Equals("Content-Length", StringComparison.OrdinalIgnoreCase) ||
                header.Key.Equals("Connection", StringComparison.OrdinalIgnoreCase) || header.Key.Equals("Transfer-Encoding", StringComparison.OrdinalIgnoreCase)) Invalid();
            Text(header.Value, 512);
        }
    }
    private static void Xtream(RemoteProviderConfiguration configuration)
    {
        if (configuration.Xtream is null || configuration.M3u is not null || configuration.Stalker is not null) Invalid();
        Url(configuration.ServerUrl, true);
        Text(configuration.Xtream.Username, 256, true); Text(configuration.Xtream.Password, 1024, true);
        if (configuration.Xtream.LiveSyncMode is not ("auto" or "category_by_category" or "stream_all")) Invalid();
    }
    private static void M3u(RemoteProviderConfiguration configuration)
    {
        if (configuration.M3u is null || configuration.Xtream is not null || configuration.Stalker is not null) Invalid();
        Url(configuration.M3u.M3uUrl, true);
    }
    private static void Stalker(RemoteProviderConfiguration configuration)
    {
        if (configuration.Stalker is null || configuration.Xtream is not null || configuration.M3u is not null) Invalid();
        Url(configuration.Stalker.PortalUrl, true);
        if (configuration.Stalker.StalkerMacAddress is null || !Regex.IsMatch(configuration.Stalker.StalkerMacAddress, "^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")) Invalid();
        Text(configuration.Stalker.DeviceProfile, 256); Text(configuration.Stalker.Timezone, 64); Text(configuration.Stalker.Locale, 32);
    }
    private static void EpgSync(string mode)
    {
        if (mode is not ("upfront" or "background" or "skip")) Invalid();
    }
    private static void Text(string? text, int limit, bool required = false)
    {
        if ((required && string.IsNullOrWhiteSpace(text)) || (text is not null && (text.Length > limit || text.Any(char.IsControl)))) Invalid();
    }
    private static void Url(string? url, bool required)
    {
        if (url is null && !required) return;
        if (string.IsNullOrWhiteSpace(url) || url.Length > 4096 || url.Any(c => char.IsControl(c) || char.IsWhiteSpace(c) || c == '\\') ||
            !Uri.TryCreate(url, UriKind.Absolute, out var uri) || (uri.Scheme != "http" && uri.Scheme != "https") ||
            string.IsNullOrEmpty(uri.Host) || uri.UserInfo.Length != 0 || uri.Fragment.Length != 0) Invalid();
    }
    [System.Diagnostics.CodeAnalysis.DoesNotReturn]
    private static void Invalid() => throw new DomainException(400, "invalid_provider_configuration", "Provider configuration is invalid.");
}
