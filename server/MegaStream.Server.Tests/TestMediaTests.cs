using System.Buffers.Binary;
using System.Net;
using System.Net.Http.Headers;
using System.Text;
using MegaStream.Server.Controllers;
using Microsoft.AspNetCore.Authorization;
using Xunit;
using static MegaStream.Server.Tests.RemoteProviders.RemoteProviderHttpTests;

namespace MegaStream.Server.Tests;

public sealed class TestMediaTests
{
    [Fact]
    public async Task Public_fixture_is_small_mp4_with_h264_and_aac_and_null_origin_cors()
    {
        using var app = new TestApplication();
        using var client = Client(app);
        client.DefaultRequestHeaders.Add("Origin", "null");
        using var response = await client.GetAsync("/api/v1/test-media/video.mp4");
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Equal("video/mp4", response.Content.Headers.ContentType?.MediaType);
        var bytes = await response.Content.ReadAsByteArrayAsync();
        Assert.InRange(bytes.Length, 1, 1_000_000);
        Assert.Equal("ftyp", Encoding.ASCII.GetString(bytes, 4, 4));
        var atoms = Encoding.ASCII.GetString(bytes);
        Assert.Contains("avc1", atoms);
        Assert.Contains("mp4a", atoms);
        Assert.True(atoms.IndexOf("moov", StringComparison.Ordinal) < atoms.IndexOf("mdat", StringComparison.Ordinal));
        var movieHeader = atoms.IndexOf("mvhd", StringComparison.Ordinal);
        Assert.True(movieHeader >= 0);
        Assert.Equal(0, bytes[movieHeader + 4]);
        var timescale = BinaryPrimitives.ReadUInt32BigEndian(bytes.AsSpan(movieHeader + 16, 4));
        var duration = BinaryPrimitives.ReadUInt32BigEndian(bytes.AsSpan(movieHeader + 20, 4));
        Assert.Equal(10d, (double)duration / timescale);
        Assert.Equal("bytes", Assert.Single(response.Headers.AcceptRanges));
        Assert.Equal("null", Assert.Single(response.Headers.GetValues("Access-Control-Allow-Origin")));
        Assert.False(response.Headers.Contains("Access-Control-Allow-Credentials"));
    }

    [Fact]
    public async Task Public_video_supports_byte_ranges_and_rejects_unsatisfiable_ranges()
    {
        using var app = new TestApplication();
        using var client = Client(app);
        var full = await client.GetByteArrayAsync("/api/v1/test-media/video.mp4");
        using var request = new HttpRequestMessage(HttpMethod.Get, "/api/v1/test-media/video.mp4");
        request.Headers.Range = new RangeHeaderValue(0, 31);
        using var response = await client.SendAsync(request);
        Assert.Equal(HttpStatusCode.PartialContent, response.StatusCode);
        Assert.Equal("video/mp4", response.Content.Headers.ContentType?.MediaType);
        Assert.Equal($"bytes 0-31/{full.Length}", response.Content.Headers.ContentRange?.ToString());
        Assert.Equal(full[..32], await response.Content.ReadAsByteArrayAsync());
        using var invalid = new HttpRequestMessage(HttpMethod.Get, "/api/v1/test-media/video.mp4");
        invalid.Headers.Range = new RangeHeaderValue(full.Length + 1, null);
        using var rejected = await client.SendAsync(invalid);
        Assert.Equal(HttpStatusCode.RequestedRangeNotSatisfiable, rejected.StatusCode);
    }

    [Fact]
    public async Task Playlist_has_one_fixed_absolute_https_source_even_with_untrusted_host_or_query()
    {
        using var app = new TestApplication();
        using var client = Client(app);
        using var request = new HttpRequestMessage(HttpMethod.Get, "/api/v1/test-media/playlist.m3u?url=https://untrusted.invalid/private");
        request.Headers.Host = "untrusted.invalid";
        request.Headers.Add("Origin", "null");
        using var response = await client.SendAsync(request);
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Equal("audio/x-mpegurl", response.Content.Headers.ContentType?.MediaType);
        var playlist = await response.Content.ReadAsStringAsync();
        Assert.Equal("#EXTM3U\n#EXTINF:10,MegaStream synthetic test (10 seconds)\nhttps://megastrem.megastation.uk/api/v1/test-media/video.mp4\n", playlist);
        Assert.Equal("null", Assert.Single(response.Headers.GetValues("Access-Control-Allow-Origin")));
        Assert.False(response.Headers.Contains("Access-Control-Allow-Credentials"));
    }

    [Fact]
    public async Task Admin_page_uses_existing_cookie_policy_and_rtl_layout()
    {
        Assert.Equal("Admin", Assert.Single(typeof(TestMediaController).GetCustomAttributes(typeof(AuthorizeAttribute), true).Cast<AuthorizeAttribute>()).Policy);
        using var app = new TestApplication();
        using var client = Client(app);
        using var denied = await client.GetAsync("/admin/test-media");
        Assert.Equal(HttpStatusCode.Redirect, denied.StatusCode);
        Assert.Contains("/Account/Login", denied.Headers.Location?.OriginalString);
        await LoginAsync(app, client);
        using var response = await client.GetAsync("/admin/test-media");
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.True(response.Headers.CacheControl?.NoStore);
        var html = await response.Content.ReadAsStringAsync();
        Assert.Contains("dir=\"rtl\"", html);
        Assert.Contains("<video controls", html);
        Assert.Contains("/api/v1/test-media/playlist.m3u", html);
    }

    [Fact]
    public async Task No_arbitrary_file_route_or_upload_exists_and_path_queries_cannot_select_a_file()
    {
        using var app = new TestApplication();
        using var client = Client(app);
        var expected = await client.GetByteArrayAsync("/api/v1/test-media/video.mp4");
        Assert.Equal(expected, await client.GetByteArrayAsync("/api/v1/test-media/video.mp4?path=/etc/passwd&file=../../appsettings.json"));
        using var other = await client.GetAsync("/api/v1/test-media/appsettings.json");
        Assert.Equal(HttpStatusCode.NotFound, other.StatusCode);
        foreach (var path in new[] { "/api/v1/test-media/video.mp4", "/api/v1/test-media/playlist.m3u" })
        {
            using var post = await client.PostAsync(path, new StringContent("not an upload"));
            Assert.Equal(HttpStatusCode.MethodNotAllowed, post.StatusCode);
        }
        Assert.Empty(typeof(TestMediaController).GetMethod(nameof(TestMediaController.Video))!.GetParameters());
    }
}
