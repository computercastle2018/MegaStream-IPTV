using System.Net;
using System.Net.Http.Json;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using MegaStream.Server.Contracts;
using MegaStream.Server.Data;
using MegaStream.Server.RemoteProviders.Core;
using MegaStream.Server.Services;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.AspNetCore.TestHost;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;
using Xunit;

namespace MegaStream.Server.Tests.RemoteProviders;

public sealed class ProviderCredentialsTests
{
    [Theory]
    [InlineData(RemoteProviderValues.XtreamCodes, "xtream_codes")]
    [InlineData(RemoteProviderValues.M3u, "m3u")]
    [InlineData(RemoteProviderValues.StalkerPortal, "stalker_portal")]
    public async Task Local_credentials_are_encrypted_bound_to_device_and_only_revealed_by_admin_post(string type, string localType)
    {
        using var app = new TestApplication();
        using var client = Client(app);
        Guid id, otherId;
        string bearer;
        using (var scope = app.Services.CreateScope())
        {
            var devices = scope.ServiceProvider.GetRequiredService<DeviceService>();
            var device = await devices.RegisterAsync(new(Guid.NewGuid().ToString("D"), "android", "47", "TV", "14"));
            id = device.InstallationId; bearer = device.BearerToken;
            otherId = (await devices.RegisterAsync(new(Guid.NewGuid().ToString("D"), "android", "47", "Other", "14"))).InstallationId;
        }
        var configuration = RemoteProviderTestData.Profile(type).Configuration;
        client.DefaultRequestHeaders.Authorization = new("Bearer", bearer);
        using var reported = await client.PostAsJsonAsync("/api/v1/devices/subscriptions", new LocalSubscriptionsRequest
        {
            Subscriptions = [new() { LocalId = 1, Name = "Local sports", Type = localType, Enabled = true, Status = "active",
                MaxConnections = 1, Credentials = configuration }]
        });
        Assert.Equal(HttpStatusCode.NoContent, reported.StatusCode);
        string stored;
        using (var scope = app.Services.CreateScope())
        {
            var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();
            stored = (await db.Installations.SingleAsync(x => x.Id == id)).LocalSubscriptionsJson!;
            foreach (var secret in RemoteProviderTestData.SecretValues(configuration)) Assert.DoesNotContain(secret, stored);
            Assert.Equal("Local sports", Assert.Single(LocalSubscriptionCredentialsStore.Summaries(stored))!.Name);
            var other = await db.Installations.SingleAsync(x => x.Id == otherId);
            other.LocalSubscriptionsJson = stored;
            await db.SaveChangesAsync();
            await Assert.ThrowsAsync<DomainException>(() => scope.ServiceProvider.GetRequiredService<LocalSubscriptionCredentialsStore>()
                .RevealAsync(otherId, 1, default));
        }
        var url = $"/admin/providers/installations/{id}/subscriptions/1/reveal";
        using var bearerReveal = await client.PostAsync(url, new FormUrlEncodedContent([]));
        Assert.Equal(HttpStatusCode.Redirect, bearerReveal.StatusCode);
        client.DefaultRequestHeaders.Authorization = null;
        await HttpSecurityTests.LoginAdmin(app, client);
        var html = await client.GetStringAsync("/admin/providers");
        foreach (var secret in RemoteProviderTestData.SecretValues(configuration)) Assert.DoesNotContain(secret, html);
        Assert.Contains(url, html);
        Assert.Contains("data-provider-credentials hidden", html);
        var details = await client.GetStringAsync($"/Admin/DeviceDetails/{id}");
        Assert.Contains("Local sports", details);
        Assert.Contains(url, details);
        foreach (var secret in RemoteProviderTestData.SecretValues(configuration)) Assert.DoesNotContain(secret, details);
        using (var scope = app.Services.CreateScope())
        {
            var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();
            (await db.Installations.SingleAsync(x => x.Id == id)).LastSeenAt = DateTime.UtcNow.AddHours(-1);
            await db.SaveChangesAsync();
        }
        Assert.DoesNotContain(url, await client.GetStringAsync("/admin/providers"));
        Assert.Contains(url, await client.GetStringAsync($"/Admin/DeviceDetails/{id}"));
        using var missingCsrf = await client.PostAsync(url, new FormUrlEncodedContent([]));
        Assert.Equal(HttpStatusCode.BadRequest, missingCsrf.StatusCode);
        using var revealed = await client.PostAsync(url, Csrf(html));
        Assert.Equal(HttpStatusCode.OK, revealed.StatusCode);
        Assert.True(revealed.Headers.CacheControl!.NoStore);
        Assert.Equal("no-referrer", revealed.Headers.GetValues("Referrer-Policy").Single());
        var body = await revealed.Content.ReadFromJsonAsync<RemoteProviderConfiguration>();
        Assert.Equal(configuration.Xtream?.Password, body!.Xtream?.Password);
        Assert.Equal(configuration.M3u?.M3uUrl, body.M3u?.M3uUrl);
        Assert.Equal(configuration.Stalker?.StalkerMacAddress, body.Stalker?.StalkerMacAddress);
        if (type == RemoteProviderValues.M3u)
        {
            using var failedExport = await client.PostAsync(url.Replace("/reveal", "/export"), Csrf(html));
            Assert.Equal(HttpStatusCode.BadGateway, failedExport.StatusCode);
            foreach (var secret in RemoteProviderTestData.SecretValues(configuration))
                Assert.DoesNotContain(secret, await failedExport.Content.ReadAsStringAsync());
        }
        using var getReveal = await client.GetAsync(url);
        Assert.Equal(HttpStatusCode.MethodNotAllowed, getReveal.StatusCode);
        client.DefaultRequestHeaders.Authorization = new("Bearer", bearer);
        using var removed = await client.PostAsJsonAsync("/api/v1/devices/subscriptions", new LocalSubscriptionsRequest { Subscriptions = [] });
        Assert.Equal(HttpStatusCode.NoContent, removed.StatusCode);
        using var unavailable = await client.PostAsync(url, Csrf(html));
        Assert.Equal(HttpStatusCode.NotFound, unavailable.StatusCode);
    }

    [Fact]
    public async Task Invalid_credentials_do_not_overwrite_snapshot_or_echo_secret_and_old_reports_still_work()
    {
        using var app = new TestApplication();
        using var client = Client(app);
        Guid id;
        using (var scope = app.Services.CreateScope())
        {
            var device = await scope.ServiceProvider.GetRequiredService<DeviceService>().RegisterAsync(new(Guid.NewGuid().ToString("D"), "android", "47", "TV", "14"));
            id = device.InstallationId; client.DefaultRequestHeaders.Authorization = new("Bearer", device.BearerToken);
        }
        var report = new LocalSubscriptionsRequest { Subscriptions = [new() { LocalId = 1, Name = "Old", Type = "m3u", MaxConnections = 1, Status = "unknown" }] };
        using var accepted = await client.PostAsJsonAsync("/api/v1/devices/subscriptions", report);
        Assert.Equal(HttpStatusCode.NoContent, accepted.StatusCode);
        report.Subscriptions[0].Credentials = new() { Xtream = new() { Username = "test-secret", Password = "test-secret" } };
        using var invalid = await client.PostAsJsonAsync("/api/v1/devices/subscriptions", report);
        Assert.Equal(HttpStatusCode.BadRequest, invalid.StatusCode);
        Assert.DoesNotContain("test-secret", await invalid.Content.ReadAsStringAsync());
        using var scope2 = app.Services.CreateScope();
        var stored = (await scope2.ServiceProvider.GetRequiredService<AppDbContext>().Installations.SingleAsync(x => x.Id == id)).LocalSubscriptionsJson!;
        Assert.StartsWith("[", stored);
        Assert.Null(Assert.Single(LocalSubscriptionCredentialsStore.Summaries(stored))!.Credentials);
    }

    [Fact]
    public async Task Export_downloads_actual_playlist_with_csrf_and_revoke_disables_reveal_and_export()
    {
        using var app = new TestApplication();
        var exporter = new TestExporter(() => new(HttpStatusCode.OK) { Content = new StringContent("#EXTM3U\n#EXTINF:-1,Sports\nhttps://media.example.org/live/1.ts\n") });
        using var customized = app.WithWebHostBuilder(builder => builder.ConfigureTestServices(services =>
        { services.RemoveAll<ProviderPlaylistExporter>(); services.AddSingleton<ProviderPlaylistExporter>(exporter); }));
        using var client = Client(customized);
        await HttpSecurityTests.LoginAdmin(customized, client);
        Guid id;
        using (var scope = customized.Services.CreateScope())
            id = (await scope.ServiceProvider.GetRequiredService<IRemoteProviderService>().CreateProfileAsync(RemoteProviderTestData.Profile())).Id;
        var html = await client.GetStringAsync($"/admin/providers/{id}");
        using var missing = await client.PostAsync($"/admin/providers/{id}/export", new FormUrlEncodedContent([]));
        Assert.Equal(HttpStatusCode.BadRequest, missing.StatusCode);
        using var file = await client.PostAsync($"/admin/providers/{id}/export", Csrf(html));
        Assert.Equal(HttpStatusCode.OK, file.StatusCode);
        Assert.Equal("audio/x-mpegurl", file.Content.Headers.ContentType!.MediaType);
        Assert.Contains("Living-room.m3u", file.Content.Headers.ContentDisposition!.ToString());
        Assert.True(file.Headers.CacheControl!.NoStore);
        Assert.StartsWith("#EXTM3U\n#EXTINF:", await file.Content.ReadAsStringAsync());
        Assert.Equal("/private-server-token/get.php", exporter.RequestUri!.AbsolutePath);
        var query = Microsoft.AspNetCore.WebUtilities.QueryHelpers.ParseQuery(exporter.RequestUri.Query);
        Assert.Equal("private-xtream-user", query["username"]);
        Assert.Equal("m3u_plus", query["type"]);
        Guid localDevice;
        using (var scope = customized.Services.CreateScope())
        {
            var device = await scope.ServiceProvider.GetRequiredService<DeviceService>().RegisterAsync(new(Guid.NewGuid().ToString("D"), "android", "47", "Kings Pro", "14"));
            localDevice = device.InstallationId;
            client.DefaultRequestHeaders.Authorization = new("Bearer", device.BearerToken);
        }
        using var uploaded = await client.PostAsJsonAsync("/api/v1/devices/subscriptions", new LocalSubscriptionsRequest
        { Subscriptions = [new() { LocalId = 3, Name = "Local playlist", Type = "m3u", MaxConnections = 1, Status = "active", Enabled = true,
            Credentials = RemoteProviderTestData.Profile(RemoteProviderValues.M3u).Configuration }] });
        Assert.Equal(HttpStatusCode.NoContent, uploaded.StatusCode);
        client.DefaultRequestHeaders.Authorization = null;
        using var localFile = await client.PostAsync($"/admin/providers/installations/{localDevice}/subscriptions/3/export", Csrf(html));
        Assert.Equal(HttpStatusCode.OK, localFile.StatusCode);
        Assert.Contains("Kings-Pro.m3u", localFile.Content.Headers.ContentDisposition!.ToString());
        Assert.Contains("#EXTINF:", await localFile.Content.ReadAsStringAsync());
        Assert.Equal("playlist.invalid", exporter.RequestUri!.Host);
        using (var scope = customized.Services.CreateScope())
            await scope.ServiceProvider.GetRequiredService<IRemoteProviderService>().RevokeProfileAsync(id);
        foreach (var action in new[] { "reveal", "export" })
        {
            using var revoked = await client.PostAsync($"/admin/providers/{id}/{action}", Csrf(html));
            Assert.NotEqual(HttpStatusCode.OK, revoked.StatusCode);
            Assert.DoesNotContain("private-xtream-password", await revoked.Content.ReadAsStringAsync());
        }
    }

    [Theory]
    [InlineData("127.0.0.1", false)] [InlineData("10.0.0.1", false)] [InlineData("169.254.169.254", false)]
    [InlineData("100.64.0.1", false)] [InlineData("192.168.1.1", false)] [InlineData("198.18.0.1", false)]
    [InlineData("::1", false)] [InlineData("fe80::1", false)] [InlineData("fc00::1", false)]
    [InlineData("::ffff:127.0.0.1", false)] [InlineData("2002:7f00:1::", false)] [InlineData("2001:db8::1", false)]
    [InlineData("8.8.8.8", true)] [InlineData("2606:4700:4700::1111", true)]
    public void Outbound_addresses_must_be_public(string address, bool allowed) =>
        Assert.Equal(allowed, ProviderPlaylistExporter.IsPublicAddress(IPAddress.Parse(address)));

    [Theory]
    [InlineData("<html>not playlist</html>")]
    [InlineData("#EXTM3U\nhttps://provider.example.org/get.php?token=secret")]
    [InlineData("#EXTM3U\n#EXTINF:-1,Test\nfile:///etc/passwd")]
    [InlineData("#EXTM3U\n#EXTINF:-1,Test\nhttps://public.example.org/stream\u0000")]
    public async Task Invalid_playlist_fails_safely_without_provider_url_echo(string playlist)
    {
        var exporter = new TestExporter(() => new(HttpStatusCode.OK) { Content = new StringContent(playlist) });
        var error = await Assert.ThrowsAsync<DomainException>(() => exporter.DownloadAsync(RemoteProviderTestData.Profile().Configuration, default));
        Assert.DoesNotContain("private", error.Message);
        Assert.Null(error.InnerException);
    }

    [Fact]
    public async Task Redirect_and_oversized_response_fail_without_following_or_returning_partial_playlist()
    {
        foreach (var status in new[] { HttpStatusCode.Redirect, HttpStatusCode.OK })
        {
            var exporter = new TestExporter(() =>
            {
                var response = new HttpResponseMessage(status) { Content = new StringContent("#EXTM3U") };
                response.Headers.Location = new Uri("http://127.0.0.1/private");
                if (status == HttpStatusCode.OK) response.Content.Headers.ContentLength = ProviderPlaylistExporter.MaximumBytes + 1;
                return response;
            });
            await Assert.ThrowsAsync<DomainException>(() => exporter.DownloadAsync(RemoteProviderTestData.Profile().Configuration, default));
        }
        var normalizer = new TestExporter(() => new(HttpStatusCode.OK)
        { Content = new StringContent("#EXTM3U\r\n#EXTINF:-1,Test\r\n/live/1.ts\r\n") });
        var normalized = await normalizer.DownloadAsync(new() { M3u = new() { M3uUrl = "https://public.example.org/get.php" } }, default);
        Assert.Contains("https://public.example.org/live/1.ts", Encoding.UTF8.GetString(normalized));
        var chunked = new TestExporter(() => new(HttpStatusCode.OK)
        { Content = new StreamContent(new NonSeekableStream(new byte[32769])) });
        await Assert.ThrowsAsync<DomainException>(() => chunked.DownloadAsync(RemoteProviderTestData.Profile().Configuration, default));
    }

    [Fact]
    public async Task Real_transport_blocks_loopback_and_stalker_is_explicitly_unsupported()
    {
        var exporter = new ProviderPlaylistExporter();
        var configuration = new RemoteProviderConfiguration { M3u = new() { M3uUrl = "http://127.0.0.1:9/playlist" } };
        await Assert.ThrowsAsync<DomainException>(() => exporter.DownloadAsync(configuration, default));
        var error = Assert.Throws<DomainException>(() => ProviderPlaylistExporter.PlaylistUri(RemoteProviderTestData.Profile(RemoteProviderValues.StalkerPortal).Configuration));
        Assert.Equal("playlist_export_unsupported", error.Code);
    }

    [Fact]
    public async Task Playlist_above_old_ten_megabyte_limit_streams_without_full_server_buffer_and_rejects_invalid_tail()
    {
        var playlist = new StringBuilder("#EXTM3U\n");
        while (playlist.Length <= 11 * 1024 * 1024)
            playlist.Append("#EXTINF:-1,Channel\nhttps://media.example.org/live/1.ts\n");
        var exporter = new TestExporter(() => new(HttpStatusCode.OK) { Content = new StringContent(playlist.ToString()) });
        await using (var stream = await exporter.OpenAsync(RemoteProviderTestData.Profile().Configuration, default))
        {
            Assert.False(stream.CanSeek);
            await stream.CopyToAsync(Stream.Null);
        }
        var invalidTail = new TestExporter(() => new(HttpStatusCode.OK)
        { Content = new StringContent("#EXTM3U\n#EXTINF:-1,Valid\nhttps://media.example.org/1.ts\n#EXTINF:-1,Invalid\nfile:///etc/passwd\n") });
        await Assert.ThrowsAsync<DomainException>(() => invalidTail.DownloadAsync(RemoteProviderTestData.Profile().Configuration, default));
    }

    private static HttpClient Client(WebApplicationFactory<Program> app) => app.CreateClient(new WebApplicationFactoryClientOptions
    { AllowAutoRedirect = false, BaseAddress = new Uri("https://localhost") });

    private static FormUrlEncodedContent Csrf(string html)
    {
        var input = Regex.Match(html, "<input[^>]*name=\"__RequestVerificationToken\"[^>]*>").Value;
        var token = WebUtility.HtmlDecode(Regex.Match(input, "value=\"([^\"]+)\"").Groups[1].Value);
        Assert.NotEmpty(token);
        return new(new Dictionary<string, string> { ["__RequestVerificationToken"] = token });
    }

    private sealed class TestExporter(Func<HttpResponseMessage> response) : ProviderPlaylistExporter
    {
        public Uri? RequestUri { get; private set; }
        protected override HttpMessageHandler CreateHandler() => new ResponseHandler(request => { RequestUri = request.RequestUri; return response(); });
    }

    private sealed class ResponseHandler(Func<HttpRequestMessage, HttpResponseMessage> response) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct) => Task.FromResult(response(request));
    }

    private sealed class NonSeekableStream(byte[] bytes) : MemoryStream(bytes)
    {
        public override bool CanSeek => false;
    }
}
