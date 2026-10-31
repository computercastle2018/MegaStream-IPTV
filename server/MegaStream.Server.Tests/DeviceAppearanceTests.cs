using System.Net;
using System.Net.Http.Json;
using System.Security.Claims;
using System.Text.Json;
using MegaStream.Server.Contracts;
using MegaStream.Server.Controllers;
using MegaStream.Server.Data;
using MegaStream.Server.Models;
using MegaStream.Server.Services;
using MegaStream.Server.Tests.RemoteProviders;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Xunit;
using static MegaStream.Server.Tests.RemoteProviders.RemoteProviderHttpTests;

namespace MegaStream.Server.Tests;

public sealed class DeviceAppearanceTests
{
    private const string Endpoint = "/api/v1/devices/appearance";

    [Theory]
    [InlineData("android", "classic")]
    [InlineData("macos", "modern")]
    [InlineData("windows", "studio")]
    [InlineData("webos", "studio")]
    public async Task Active_bearer_device_sets_only_own_override_without_license_or_access_changes(string platform, string style)
    {
        using var app = new TestApplication();
        using var client = Client(app);
        var owner = await Register(app, platform);
        var other = await Register(app, platform);
        string originalDevice;
        using (var scope = app.Services.CreateScope())
            originalDevice = JsonSerializer.Serialize(await scope.ServiceProvider.GetRequiredService<AppDbContext>()
                .Installations.AsNoTracking().SingleAsync(x => x.Id == owner.InstallationId));
        client.DefaultRequestHeaders.Authorization = new("Bearer", owner.BearerToken);
        using var response = await client.PostAsJsonAsync(Endpoint, new { uiStyle = style, expectedUiStyle = (string?)null });
        Assert.Equal(HttpStatusCode.NoContent, response.StatusCode);
        Assert.True(response.Headers.CacheControl?.NoStore);
        using var verify = app.Services.CreateScope();
        var db = verify.ServiceProvider.GetRequiredService<AppDbContext>();
        var device = await db.Installations.AsNoTracking().SingleAsync(x => x.Id == owner.InstallationId);
        Assert.Equal(style, device.UiStyle);
        device.UiStyle = null;
        Assert.Equal(originalDevice, JsonSerializer.Serialize(device));
        Assert.Null((await db.Installations.AsNoTracking().SingleAsync(x => x.Id == other.InstallationId)).UiStyle);
        Assert.Empty(await db.Licenses.ToListAsync());
    }

    [Theory]
    [InlineData("{}")]
    [InlineData("{\"uiStyle\":\"studio\"}")]
    [InlineData("{\"uiStyle\":null,\"expectedUiStyle\":null}")]
    [InlineData("{\"uiStyle\":\"dark\",\"expectedUiStyle\":null}")]
    [InlineData("{\"uiStyle\":\"Studio\",\"expectedUiStyle\":null}")]
    [InlineData("{\"uiStyle\":\"studio\",\"expectedUiStyle\":0}")]
    [InlineData("{\"uiStyle\":\"studio\",\"expectedUiStyle\":null,\"installationId\":\"other\"}")]
    public async Task Strict_payload_rejects_missing_expected_invalid_style_and_device_selector(string json)
    {
        using var app = new TestApplication();
        using var client = Client(app);
        var owner = await Register(app);
        client.DefaultRequestHeaders.Authorization = new("Bearer", owner.BearerToken);
        using var response = await client.PostAsync(Endpoint, new StringContent(json, System.Text.Encoding.UTF8, "application/json"));
        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        using var verify = app.Services.CreateScope();
        Assert.Null((await verify.ServiceProvider.GetRequiredService<AppDbContext>().Installations.SingleAsync()).UiStyle);
    }

    [Fact]
    public async Task Anonymous_and_cookie_authentication_cannot_change_appearance_and_query_cannot_select_device()
    {
        using var app = new TestApplication();
        using var client = Client(app);
        var device = await Register(app);
        var body = new { uiStyle = "modern", expectedUiStyle = (string?)null };
        using var anonymous = await client.PostAsJsonAsync(Endpoint, body);
        Assert.Equal(HttpStatusCode.Unauthorized, anonymous.StatusCode);
        await LoginAsync(app, client);
        using var cookie = await client.PostAsJsonAsync(Endpoint, body);
        Assert.Equal(HttpStatusCode.Unauthorized, cookie.StatusCode);
        client.DefaultRequestHeaders.Authorization = new("Bearer", device.BearerToken);
        using var selector = await client.PostAsJsonAsync(Endpoint + "?installationId=" + Guid.NewGuid(), body);
        Assert.Equal(HttpStatusCode.BadRequest, selector.StatusCode);
        using var verify = app.Services.CreateScope();
        Assert.Null((await verify.ServiceProvider.GetRequiredService<AppDbContext>().Installations.SingleAsync()).UiStyle);
    }

    [Fact]
    public async Task Inactive_installation_is_forbidden_without_changing_appearance()
    {
        using var app = new TestApplication();
        using var client = Client(app);
        var device = await Register(app);
        using (var scope = app.Services.CreateScope())
            await scope.ServiceProvider.GetRequiredService<AppDbContext>().Installations.ExecuteUpdateAsync(s => s.SetProperty(x => x.Status, InstallationStatus.Revoked));
        client.DefaultRequestHeaders.Authorization = new("Bearer", device.BearerToken);
        using var response = await client.PostAsJsonAsync(Endpoint, new { uiStyle = "modern", expectedUiStyle = (string?)null });
        Assert.Equal(HttpStatusCode.Forbidden, response.StatusCode);
        using var verify = app.Services.CreateScope();
        Assert.Null((await verify.ServiceProvider.GetRequiredService<AppDbContext>().Installations.SingleAsync()).UiStyle);
    }

    [Fact]
    public async Task License_inheritance_requires_current_expected_value_and_becomes_device_only_override()
    {
        using var app = new TestApplication();
        using var client = Client(app);
        var device = await Register(app);
        string licenseBefore;
        using (var scope = app.Services.CreateScope())
        {
            await RemoteProviderTestData.LicenseAsync(scope.ServiceProvider, device.InstallationId);
            var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();
            await db.Licenses.ExecuteUpdateAsync(s => s.SetProperty(x => x.UiStyle, "modern"));
            licenseBefore = JsonSerializer.Serialize(await db.Licenses.AsNoTracking().SingleAsync());
        }
        client.DefaultRequestHeaders.Authorization = new("Bearer", device.BearerToken);
        using var stale = await client.PostAsJsonAsync(Endpoint, new { uiStyle = "studio", expectedUiStyle = (string?)null });
        Assert.Equal(HttpStatusCode.Conflict, stale.StatusCode);
        Assert.Equal("appearance_conflict", (await stale.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("code").GetString());
        using var accepted = await client.PostAsJsonAsync(Endpoint, new { uiStyle = "studio", expectedUiStyle = "modern" });
        Assert.Equal(HttpStatusCode.NoContent, accepted.StatusCode);
        using (var scope = app.Services.CreateScope())
        {
            var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();
            Assert.Equal(licenseBefore, JsonSerializer.Serialize(await db.Licenses.AsNoTracking().SingleAsync()));
            Assert.Equal("studio", (await db.Installations.AsNoTracking().SingleAsync()).UiStyle);
            await db.Installations.ExecuteUpdateAsync(s => s.SetProperty(x => x.UiStyle, "classic"));
        }
        using var adminChanged = await client.PostAsJsonAsync(Endpoint, new { uiStyle = "modern", expectedUiStyle = "studio" });
        Assert.Equal(HttpStatusCode.Conflict, adminChanged.StatusCode);
        using var verify = app.Services.CreateScope();
        Assert.Equal("classic", (await verify.ServiceProvider.GetRequiredService<AppDbContext>().Installations.SingleAsync()).UiStyle);
    }

    [Fact]
    public async Task Concurrent_independent_connections_compare_and_set_under_device_lock_allow_one_winner()
    {
        await using var store = await RemoteProviderDatabase.CreateAsync();
        var id = await store.AddInstallationAsync(false);
        using var start = new Barrier(2);
        var attempts = new[] { "modern", "studio" }.Select(style => Task.Run(async () =>
        {
            await using var db = store.NewContext();
            var controller = new DeviceAppearanceController(db)
            {
                ControllerContext = new ControllerContext { HttpContext = new DefaultHttpContext
                {
                    User = new ClaimsPrincipal(new ClaimsIdentity([new Claim(ClaimTypes.NameIdentifier, id.ToString())], DeviceAuthenticationHandler.SchemeName))
                } }
            };
            if (!start.SignalAndWait(TimeSpan.FromSeconds(15))) throw new TimeoutException("Appearance contenders did not start.");
            try
            {
                await controller.Set(new DeviceAppearanceRequest { UiStyle = style, ExpectedUiStyle = null }, default);
                return (Style: style, Error: (DomainException?)null);
            }
            catch (DomainException error) { return (Style: style, Error: error); }
        })).ToArray();
        var outcomes = await Task.WhenAll(attempts);
        var winner = Assert.Single(outcomes, x => x.Error is null);
        var loser = Assert.Single(outcomes, x => x.Error is not null);
        Assert.Equal(409, loser.Error!.StatusCode); Assert.Equal("appearance_conflict", loser.Error.Code);
        await using var verify = store.NewContext();
        Assert.Equal(winner.Style, (await verify.Installations.SingleAsync()).UiStyle);
        Assert.Empty(await verify.Licenses.ToListAsync());
    }

    private static async Task<RegisterResponse> Register(TestApplication app, string platform = "webos")
    {
        using var scope = app.Services.CreateScope();
        return await scope.ServiceProvider.GetRequiredService<DeviceService>()
            .RegisterAsync(new(Guid.NewGuid().ToString("D"), platform, "1.0", "TV", "OS"));
    }
}
