using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.RegularExpressions;
using MegaStream.Server.Contracts;
using MegaStream.Server.Data;
using MegaStream.Server.RemoteProviders.Core;
using MegaStream.Server.Services;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Xunit;
using static MegaStream.Server.Tests.RemoteProviders.RemoteProviderHttpTests;

namespace MegaStream.Server.Tests.RemoteProviders;

public sealed class ProviderRedemptionHttpTests
{
    [Fact]
    public async Task Admin_generation_requires_csrf_shows_code_once_and_licensed_device_redeems_without_secret_response()
    {
        using var app = new TestApplication();
        using var admin = Client(app);
        using var device = Client(app);
        Guid profileId;
        RegisterResponse installation;
        using (var scope = app.Services.CreateScope())
        {
            profileId = (await scope.ServiceProvider.GetRequiredService<IRemoteProviderService>()
                .CreateProfileAsync(RemoteProviderTestData.Profile())).Id;
            installation = await scope.ServiceProvider.GetRequiredService<DeviceService>().RegisterAsync(
                new(Guid.NewGuid().ToString("D"), "webos", "1.0", "LG TV", "webOS"));
            await RemoteProviderTestData.LicenseAsync(scope.ServiceProvider, installation.InstallationId);
        }
        var detailsPath = $"/admin/providers/{profileId}";
        var generatePath = detailsPath + "/redemption-codes";
        using var anonymousAdmin = await admin.PostAsync(generatePath, null);
        Assert.Equal(HttpStatusCode.Redirect, anonymousAdmin.StatusCode);
        await LoginAsync(app, admin);
        using var noCsrf = await admin.PostAsync(generatePath, new FormUrlEncodedContent(new Dictionary<string, string>()));
        Assert.Equal(HttpStatusCode.BadRequest, noCsrf.StatusCode);
        using (var scope = app.Services.CreateScope())
            Assert.Empty(await scope.ServiceProvider.GetRequiredService<AppDbContext>().Set<ProviderRedemptionCode>().ToListAsync());
        var details = await admin.GetStringAsync(detailsPath);
        Assert.Contains(generatePath, details);
        using var generated = await admin.PostAsync(generatePath, new FormUrlEncodedContent(new Dictionary<string, string>
        {
            ["__RequestVerificationToken"] = Token(details)
        }));
        Assert.Equal(HttpStatusCode.OK, generated.StatusCode);
        Assert.True(generated.Headers.CacheControl?.NoStore);
        var html = await generated.Content.ReadAsStringAsync();
        var code = Regex.Match(html, "id=\"provider-redemption-code\"[^>]*>([0-9A-F]{24})<").Groups[1].Value;
        Assert.Equal(24, code.Length);
        Assert.Contains("24", html);
        Assert.Contains("UTC", html);
        foreach (var secret in RemoteProviderTestData.SecretValues(RemoteProviderTestData.Profile().Configuration))
            Assert.DoesNotContain(secret, html);
        Assert.DoesNotContain(code, await admin.GetStringAsync(detailsPath));
        using var denied = await device.PostAsJsonAsync("/api/v1/providers/redeem", new { code });
        Assert.Equal(HttpStatusCode.Unauthorized, denied.StatusCode);
        device.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", installation.BearerToken);
        device.DefaultRequestHeaders.Add("Origin", "null");
        using var redeemed = await device.PostAsJsonAsync("/api/v1/providers/redeem", new { code });
        Assert.Equal(HttpStatusCode.NoContent, redeemed.StatusCode);
        Assert.Equal("", await redeemed.Content.ReadAsStringAsync());
        Assert.True(redeemed.Headers.CacheControl?.NoStore);
        Assert.Equal("null", Assert.Single(redeemed.Headers.GetValues("Access-Control-Allow-Origin")));
        var assignments = await device.GetStringAsync("/api/v1/providers/assignments");
        Assert.Contains("private-xtream-user", assignments);
        using var replay = await device.PostAsJsonAsync("/api/v1/providers/redeem", new { code });
        Assert.Equal(HttpStatusCode.NoContent, replay.StatusCode);
    }

    [Theory]
    [InlineData("{}")]
    [InlineData("{\"code\":null}")]
    [InlineData("{\"code\":\"123456789012345678901234\",\"installationId\":\"other\"}")]
    [InlineData("{\"code\":\"https://private.invalid/token\"}")]
    public async Task Device_payload_is_strict_and_never_echoes_code(string json)
    {
        using var app = new TestApplication();
        using var client = Client(app);
        using (var scope = app.Services.CreateScope())
        {
            var installation = await scope.ServiceProvider.GetRequiredService<DeviceService>().RegisterAsync(
                new(Guid.NewGuid().ToString("D"), "webos", "1.0", "LG", "webOS"));
            await RemoteProviderTestData.LicenseAsync(scope.ServiceProvider, installation.InstallationId);
            client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", installation.BearerToken);
        }
        using var response = await client.PostAsync("/api/v1/providers/redeem", new StringContent(json, System.Text.Encoding.UTF8, "application/json"));
        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        var error = await response.Content.ReadAsStringAsync();
        Assert.DoesNotContain("123456789012345678901234", error);
        Assert.DoesNotContain("private.invalid", error);
    }

    [Fact]
    public async Task Redemption_attempts_have_stricter_limit_than_global_device_requests()
    {
        using var app = new TestApplication();
        using var client = Client(app);
        using (var scope = app.Services.CreateScope())
        {
            var installation = await scope.ServiceProvider.GetRequiredService<DeviceService>().RegisterAsync(
                new(Guid.NewGuid().ToString("D"), "webos", "1.0", "LG", "webOS"));
            await RemoteProviderTestData.LicenseAsync(scope.ServiceProvider, installation.InstallationId);
            client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", installation.BearerToken);
        }
        for (var attempt = 0; attempt < 6; attempt++)
        {
            using var rejected = await client.PostAsJsonAsync("/api/v1/providers/redeem", new { code = new string('0', 24) });
            Assert.Equal(HttpStatusCode.BadRequest, rejected.StatusCode);
        }
        using var limited = await client.PostAsJsonAsync("/api/v1/providers/redeem", new { code = new string('0', 24) });
        Assert.Equal(HttpStatusCode.TooManyRequests, limited.StatusCode);
        using var normal = await client.GetAsync("/api/v1/providers/assignments");
        Assert.Equal(HttpStatusCode.OK, normal.StatusCode);
    }
}
