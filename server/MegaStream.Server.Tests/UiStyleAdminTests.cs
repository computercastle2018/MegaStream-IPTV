using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;
using MegaStream.Server.Contracts;
using MegaStream.Server.Data;
using MegaStream.Server.Models;
using MegaStream.Server.Services;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Xunit;

namespace MegaStream.Server.Tests;

public sealed class UiStyleAdminTests
{
    private const string Experience = "/api/v1/devices/experience";

    [Theory]
    [InlineData(null, null, null)]
    [InlineData(null, "modern", "modern")]
    [InlineData("classic", "studio", "classic")]
    [InlineData("studio", null, "studio")]
    public async Task V2_style_is_device_then_license_then_local_while_v1_shape_stays_exact(string? deviceStyle, string? licenseStyle, string? expected)
    {
        using var app = new TestApplication();
        using var client = Client(app);
        var device = await Register(app);
        using (var scope = app.Services.CreateScope())
        {
            var admin = scope.ServiceProvider.GetRequiredService<IAdminService>();
            var license = await admin.CreateLicenseAsync("Customer", DateTime.UtcNow.AddDays(-1), DateTime.UtcNow.AddDays(1), 1);
            await admin.AssignInstallationLicenseAsync(device.InstallationId, license.License.Id);
            var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();
            (await db.Installations.SingleAsync()).UiStyle = deviceStyle;
            (await db.Licenses.SingleAsync()).UiStyle = licenseStyle;
            await db.SaveChangesAsync();
        }
        client.DefaultRequestHeaders.Authorization = new("Bearer", device.BearerToken);
        using var response = await client.GetAsync(Experience + "?version=2&installationId=" + Guid.NewGuid());
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.True(response.Headers.CacheControl?.NoStore);
        var v2 = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal(new[] { "allowSubscriptionDetails", "macAddress", "notifications", "uiStyle" }, v2.EnumerateObject().Select(x => x.Name).Order().ToArray());
        Assert.Equal(expected, v2.GetProperty("uiStyle").GetString());
        foreach (var path in new[] { Experience, Experience + "?version=1" })
        {
            var json = await client.GetStringAsync(path);
            var v1 = JsonSerializer.Deserialize<JsonElement>(json);
            Assert.Equal(new[] { "allowSubscriptionDetails", "macAddress", "notifications" }, v1.EnumerateObject().Select(x => x.Name).Order().ToArray());
            Assert.NotNull(JsonSerializer.Deserialize<DeviceExperienceResponse>(json,
                new JsonSerializerOptions(JsonSerializerDefaults.Web) { UnmappedMemberHandling = JsonUnmappedMemberHandling.Disallow }));
            foreach (var property in v1.EnumerateObject()) Assert.Equal(property.Value.GetRawText(), v2.GetProperty(property.Name).GetRawText());
        }
    }

    [Fact]
    public async Task Admin_dropdown_changes_persist_and_clearing_device_then_license_restores_inheritance_without_changing_entitlements()
    {
        using var app = new TestApplication();
        using var client = Client(app);
        var device = await Register(app);
        Guid licenseId;
        using (var scope = app.Services.CreateScope())
        {
            var admin = scope.ServiceProvider.GetRequiredService<IAdminService>();
            licenseId = (await admin.CreateLicenseAsync("Customer", DateTime.UtcNow.AddDays(-1), DateTime.UtcNow.AddDays(1), 1)).License.Id;
            await admin.AssignInstallationLicenseAsync(device.InstallationId, licenseId);
        }
        await HttpSecurityTests.LoginAdmin(app, client);
        using var bearer = Client(app);
        bearer.DefaultRequestHeaders.Authorization = new("Bearer", device.BearerToken);
        foreach (var (target, style, expected) in new[] { ("License", "modern", "modern"), ("Device", "studio", "studio"),
            ("Device", "classic", "classic"), ("Device", "", "modern"), ("License", "", (string?)null) })
        {
            var id = target == "Device" ? device.InstallationId : licenseId;
            var html = await client.GetStringAsync($"/Admin/{target}Details?id={id}");
            Assert.Contains("name=\"UiStyle\"", html);
            using var result = await client.PostAsync($"/Admin/Set{target}UiStyle", new FormUrlEncodedContent(new Dictionary<string, string>
            { ["Id"] = id.ToString(), ["UiStyle"] = style, ["__RequestVerificationToken"] = Token(html) }));
            Assert.Equal(HttpStatusCode.Redirect, result.StatusCode);
            var experience = await bearer.GetFromJsonAsync<JsonElement>(Experience + "?version=2");
            Assert.Equal(expected, experience.GetProperty("uiStyle").GetString());
        }
        using var verify = app.Services.CreateScope();
        var db = verify.ServiceProvider.GetRequiredService<AppDbContext>();
        var installation = await db.Installations.SingleAsync();
        var license = await db.Licenses.SingleAsync();
        Assert.Null(installation.UiStyle);
        Assert.Null(license.UiStyle);
        Assert.Equal(licenseId, installation.LicenseId);
        Assert.Equal(1, license.MaxInstallations);
        Assert.Equal(1, license.Revision);
        Assert.Equal("off", installation.KioskMode);
    }

    [Theory]
    [InlineData("Device", "invalid")]
    [InlineData("License", "invalid")]
    [InlineData("Device", "csrf")]
    [InlineData("License", "csrf")]
    [InlineData("Device", "anonymous")]
    [InlineData("License", "anonymous")]
    [InlineData("Device", "revoked")]
    [InlineData("License", "missing")]
    public async Task Unauthorized_or_invalid_style_changes_do_not_persist(string target, string scenario)
    {
        using var app = new TestApplication();
        using var client = Client(app);
        var device = await Register(app);
        Guid licenseId;
        using (var scope = app.Services.CreateScope())
        {
            var admin = scope.ServiceProvider.GetRequiredService<IAdminService>();
            licenseId = (await admin.CreateLicenseAsync("Customer", DateTime.UtcNow.AddDays(-1), DateTime.UtcNow.AddDays(1), 1)).License.Id;
            if (scenario == "revoked") await admin.RevokeInstallationAsync(device.InstallationId);
        }
        var id = target == "Device" ? device.InstallationId : licenseId;
        var form = new Dictionary<string, string> { ["Id"] = scenario == "missing" ? Guid.NewGuid().ToString() : id.ToString(),
            ["UiStyle"] = scenario == "invalid" ? "MODERN" : "studio" };
        if (scenario != "anonymous")
        {
            await HttpSecurityTests.LoginAdmin(app, client);
            if (scenario != "csrf") form["__RequestVerificationToken"] = Token(await client.GetStringAsync($"/Admin/{target}Details?id={id}"));
        }
        using var response = await client.PostAsync($"/Admin/Set{target}UiStyle", new FormUrlEncodedContent(form));
        Assert.Equal(scenario == "anonymous" ? HttpStatusCode.Redirect : HttpStatusCode.BadRequest, response.StatusCode);
        using var verify = app.Services.CreateScope();
        var db = verify.ServiceProvider.GetRequiredService<AppDbContext>();
        Assert.Null((await db.Installations.SingleAsync()).UiStyle);
        Assert.Null((await db.Licenses.SingleAsync()).UiStyle);
    }

    [Fact]
    public async Task V2_experience_requires_device_bearer_not_cookie_and_rejects_revoked_or_unsupported_versions()
    {
        using var app = new TestApplication();
        using var client = Client(app);
        var device = await Register(app);
        Assert.Equal(HttpStatusCode.Unauthorized, (await client.GetAsync(Experience + "?version=2&token=" + device.BearerToken)).StatusCode);
        await HttpSecurityTests.LoginAdmin(app, client);
        Assert.Equal(HttpStatusCode.Unauthorized, (await client.GetAsync(Experience + "?version=2")).StatusCode);
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", device.BearerToken);
        Assert.Equal(HttpStatusCode.BadRequest, (await client.GetAsync(Experience + "?version=3")).StatusCode);
        using (var scope = app.Services.CreateScope()) await scope.ServiceProvider.GetRequiredService<IAdminService>().RevokeInstallationAsync(device.InstallationId);
        Assert.Equal(HttpStatusCode.Forbidden, (await client.GetAsync(Experience + "?version=2")).StatusCode);
    }

    private static HttpClient Client(TestApplication app) => app.CreateClient(new WebApplicationFactoryClientOptions
    { AllowAutoRedirect = false, BaseAddress = new Uri("https://localhost") });
    private static async Task<RegisterResponse> Register(TestApplication app)
    {
        using var scope = app.Services.CreateScope();
        return await scope.ServiceProvider.GetRequiredService<DeviceService>().RegisterAsync(new(Guid.NewGuid().ToString("D"), "android", "1.0", "TV", "14"));
    }
    private static string Token(string html) => WebUtility.HtmlDecode(Regex.Match(html, "name=\"__RequestVerificationToken\"[^>]*value=\"([^\"]+)\"").Groups[1].Value);
}
