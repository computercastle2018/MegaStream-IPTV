using System.Net;
using Xunit;

namespace MegaStream.Server.Tests;

public sealed class WebosCorsTests
{
    [Theory]
    [InlineData("GET")]
    [InlineData("POST")]
    [InlineData("DELETE")]
    public async Task Null_origin_preflight_advertises_only_explicit_methods_and_headers(string method)
    {
        using var app = new TestApplication();
        using var client = app.CreateClient();
        using var request = new HttpRequestMessage(HttpMethod.Options, "/api/v1/devices/heartbeat");
        request.Headers.Add("Origin", "null");
        request.Headers.Add("Access-Control-Request-Method", method);
        request.Headers.Add("Access-Control-Request-Headers", "content-type,authorization,idempotency-key,x-unapproved");
        using var response = await client.SendAsync(request);
        Assert.Equal(HttpStatusCode.NoContent, response.StatusCode);
        Assert.Equal("null", Assert.Single(response.Headers.GetValues("Access-Control-Allow-Origin")));
        Assert.Equal("GET,POST", Assert.Single(response.Headers.GetValues("Access-Control-Allow-Methods")));
        Assert.Equal("Content-Type,Authorization,Idempotency-Key", Assert.Single(response.Headers.GetValues("Access-Control-Allow-Headers")));
        Assert.False(response.Headers.Contains("Access-Control-Allow-Credentials"));
    }

    [Theory]
    [InlineData("/api/v1/public/signing-key", "https://untrusted.example")]
    [InlineData("/api/v1/public/signing-key", "file://")]
    [InlineData("/api/v10/public/signing-key", "null")]
    [InlineData("/api/devices/register", "null")]
    [InlineData("/Admin", "null")]
    [InlineData("/Account/Login", "null")]
    public async Task Cors_does_not_allow_other_origins_or_non_v1_routes(string path, string origin)
    {
        using var app = new TestApplication();
        using var client = app.CreateClient();
        using var request = new HttpRequestMessage(HttpMethod.Options, path);
        request.Headers.Add("Origin", origin);
        request.Headers.Add("Access-Control-Request-Method", "POST");
        using var response = await client.SendAsync(request);
        Assert.False(response.Headers.Contains("Access-Control-Allow-Origin"));
        Assert.False(response.Headers.Contains("Access-Control-Allow-Credentials"));
    }

    [Fact]
    public async Task Null_origin_can_read_public_keys_and_authentication_failure_without_cookie_access()
    {
        using var app = new TestApplication();
        using var client = app.CreateClient();
        client.DefaultRequestHeaders.Add("Origin", "null");
        using var keys = await client.GetAsync("/api/v1/public/signing-key");
        Assert.Equal(HttpStatusCode.OK, keys.StatusCode);
        Assert.Equal("null", Assert.Single(keys.Headers.GetValues("Access-Control-Allow-Origin")));
        using var denied = await client.PostAsync("/api/v1/devices/heartbeat", null);
        Assert.Equal(HttpStatusCode.Unauthorized, denied.StatusCode);
        Assert.Equal("null", Assert.Single(denied.Headers.GetValues("Access-Control-Allow-Origin")));
        Assert.False(denied.Headers.Contains("Access-Control-Allow-Credentials"));
    }
}
