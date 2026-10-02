using System.Net;
using System.Net.Sockets;
using System.Text;
using MegaStream.Server.Services;
using Microsoft.AspNetCore.WebUtilities;

namespace MegaStream.Server.RemoteProviders.Core;

public class ProviderPlaylistExporter
{
    public const int MaximumBytes = 256 * 1024 * 1024;
    private static readonly string[] BlockedNetworks = ["0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8",
        "169.254.0.0/16", "172.16.0.0/12", "192.0.0.0/24", "192.0.2.0/24", "192.88.99.0/24", "192.168.0.0/16",
        "198.18.0.0/15", "198.51.100.0/24", "203.0.113.0/24", "224.0.0.0/3", "2001::/23", "2001:db8::/32", "2002::/16", "3fff::/20"];

    public async Task<byte[]> DownloadAsync(RemoteProviderConfiguration configuration, CancellationToken ct)
    {
        await using var input = await OpenAsync(configuration, ct);
        using var output = new MemoryStream();
        await input.CopyToAsync(output, ct);
        return output.ToArray();
    }

    public async Task<Stream> OpenAsync(RemoteProviderConfiguration configuration, CancellationToken ct)
    {
        var deadline = CancellationTokenSource.CreateLinkedTokenSource(ct);
        deadline.CancelAfter(TimeSpan.FromSeconds(180));
        HttpClient? client = null;
        HttpResponseMessage? response = null;
        try
        {
            var uri = PlaylistUri(configuration);
            // Direct HttpClient avoids IHttpClientFactory URL logging; no redirects, cookies or environment proxy.
            client = new HttpClient(CreateHandler()) { Timeout = Timeout.InfiniteTimeSpan };
            using var request = new HttpRequestMessage(HttpMethod.Get, uri);
            if (configuration.HttpUserAgent is { } agent) request.Headers.TryAddWithoutValidation("User-Agent", agent);
            foreach (var header in configuration.HttpHeaders ?? []) request.Headers.TryAddWithoutValidation(header.Key, header.Value);
            response = await client.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, deadline.Token);
            if (!response.IsSuccessStatusCode || response.Content.Headers.ContentLength > MaximumBytes) throw Unavailable();
            var stream = new ProviderPlaylistStream(await response.Content.ReadAsStreamAsync(deadline.Token), uri, client, response, deadline);
            try { await stream.InitializeAsync(); }
            catch { stream.Dispose(); throw; }
            return stream;
        }
        catch (Exception error)
        {
            response?.Dispose(); client?.Dispose(); deadline.Dispose();
            // Never attach upstream exceptions: their messages can contain credential-bearing URLs.
            if (error is HttpRequestException or IOException or SocketException or OperationCanceledException or DecoderFallbackException) throw Unavailable();
            throw;
        }
    }

    protected virtual HttpMessageHandler CreateHandler() => new SocketsHttpHandler
    {
        AllowAutoRedirect = false, UseCookies = false, UseProxy = false,
        MaxResponseHeadersLength = 32, ConnectTimeout = TimeSpan.FromSeconds(10),
        ConnectCallback = ConnectPublicAsync
    };

    private static async ValueTask<Stream> ConnectPublicAsync(SocketsHttpConnectionContext context, CancellationToken ct)
    {
        var addresses = await Dns.GetHostAddressesAsync(context.DnsEndPoint.Host, ct);
        if (addresses.Length == 0 || addresses.Any(x => !IsPublicAddress(x))) throw Unavailable();
        // Pin the validated DNS answer; the HTTP/TLS layer still uses the original hostname.
        var socket = new Socket(addresses[0].AddressFamily, SocketType.Stream, ProtocolType.Tcp);
        try
        {
            await socket.ConnectAsync(new IPEndPoint(addresses[0], context.DnsEndPoint.Port), ct);
            return new NetworkStream(socket, ownsSocket: true);
        }
        catch { socket.Dispose(); throw; }
    }

    public static bool IsPublicAddress(IPAddress address)
    {
        if (address.IsIPv4MappedToIPv6) address = address.MapToIPv4();
        if (address.AddressFamily == AddressFamily.InterNetworkV6 && !IPNetwork.Parse("2000::/3").Contains(address)) return false;
        return !BlockedNetworks.Any(x => IPNetwork.Parse(x).Contains(address));
    }

    public static Uri PlaylistUri(RemoteProviderConfiguration configuration)
    {
        if (configuration.M3u is { } m3u) return SafeUri(m3u.M3uUrl);
        if (configuration.Xtream is not { } xtream) throw new DomainException(400, "playlist_export_unsupported", "This subscription does not support M3U export.");
        var builder = new UriBuilder(SafeUri(configuration.ServerUrl!)) { Query = "", Fragment = "" };
        builder.Path = builder.Path.TrimEnd('/') + "/get.php";
        return new Uri(QueryHelpers.AddQueryString(builder.Uri.AbsoluteUri, new Dictionary<string, string?>
        {
            ["username"] = xtream.Username, ["password"] = xtream.Password, ["type"] = "m3u_plus", ["output"] = "ts"
        }));
    }

    private static Uri SafeUri(string value)
    {
        if (!Uri.TryCreate(value, UriKind.Absolute, out var uri) || uri.Scheme is not ("http" or "https") ||
            uri.UserInfo.Length != 0 || uri.Fragment.Length != 0 || value.Any(char.IsControl)) throw Unavailable();
        return uri;
    }

    internal static DomainException Unavailable() => new(502, "playlist_export_unavailable", "Playlist export is unavailable. Check the provider and its public playlist address.");
}
