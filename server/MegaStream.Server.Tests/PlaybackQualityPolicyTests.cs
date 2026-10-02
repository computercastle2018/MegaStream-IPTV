using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using System.Text.RegularExpressions;
using MegaStream.Server.Contracts;
using MegaStream.Server.Data;
using MegaStream.Server.Models;
using MegaStream.Server.Services;
using MegaStream.Server.V1;
using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Xunit;

namespace MegaStream.Server.Tests;

public sealed class PlaybackQualityPolicyTests
{
    private const string Experience = "/api/v1/devices/experience";

    [Fact]
    public async Task Device_details_aliases_render_section_navigation_collapsible_logs_and_csrf_forms_with_one_refresh_report()
    {
        using var app = new TestApplication();
        using var client = Client(app);
        var device = await Register(app);
        using (var scope = app.Services.CreateScope())
            await scope.ServiceProvider.GetRequiredService<AppDbContext>().Installations
                .Where(x => x.Id == device.InstallationId).ExecuteUpdateAsync(set => set
                    .SetProperty(x => x.LocalSubscriptionsJson, "[]").SetProperty(x => x.LocalSubscriptionsReportedAt, DateTime.UtcNow));
        await Login(app, client);
        foreach (var path in new[] { "/Admin/DeviceDetails?id=" + device.InstallationId, "/Admin/DeviceDetails/" + device.InstallationId })
        {
            var html = await client.GetStringAsync(path);
            Assert.Contains("class=\"device-detail\"", html);
            foreach (var id in new[] { "device-quality", "device-updates", "device-license", "device-subscriptions",
                "device-subscription-policy", "device-policy", "device-history" })
            {
                Assert.Contains("href=\"#" + id + "\"", html);
                Assert.Contains("id=\"" + id + "\"", html);
            }
            foreach (var id in new[] { "device-audit", "device-sessions", "device-diagnostics" })
                Assert.Matches("<details id=\"" + id + "\"[^>]*>\\s*<summary>", html);
            Assert.Matches("<details id=\"device-subscriptions-table\"[^>]* open[^>]*>\\s*<summary>", html);
            Assert.Equal(5, Regex.Matches(html, "class=\"device-table-card\"").Count);
            Assert.Equal(1, Regex.Matches(html, "data-device-version-refresh").Count);
            var report = Regex.Match(html, "<dl data-device-version-refresh[^>]*>(.*?)</dl>", RegexOptions.Singleline).Value;
            Assert.Contains("data-device-version-value", report);
            Assert.Contains("data-device-version-reported-at", report);
            Assert.DoesNotContain("<form", report);
            Assert.DoesNotContain("<select", report);
            foreach (var action in new[] { "SetDevicePlaybackQuality", "SetDeviceUiStyle", "SetSubscriptionDetailsPolicy",
                "AssignDeviceLicense", "SetDevicePolicy", "RevokeDevice" })
            {
                var form = Regex.Match(html, "<form[^>]*action=\"/Admin/" + action + "\"[^>]*>.*?</form>", RegexOptions.Singleline).Value;
                Assert.NotEmpty(form);
                Assert.Contains("name=\"__RequestVerificationToken\"", form);
                Assert.Contains(device.InstallationId.ToString(), form);
            }
            Assert.Contains("name=\"AllowSubscriptionDetails\" value=\"false\"", html);
            Assert.Contains("/js/device-version-refresh.js?v=", html);
            Assert.Contains("/js/provider-credentials.js?v=", html);
        }
    }

    [Fact]
    public async Task Version_three_defaults_to_1080_and_older_versions_keep_exact_fields()
    {
        using var app = new TestApplication();
        using var client = Client(app);
        var device = await Register(app);
        client.DefaultRequestHeaders.Authorization = new("Bearer", device.BearerToken);
        Assert.Equal("1080", (await client.GetFromJsonAsync<JsonElement>(Experience + "?version=3"))
            .GetProperty("playbackQuality").GetString());
        foreach (var version in new[] { 1, 2 })
        {
            var json = await client.GetFromJsonAsync<JsonElement>(Experience + "?version=" + version);
            var expected = version == 1
                ? new[] { "allowSubscriptionDetails", "macAddress", "notifications" }
                : new[] { "allowSubscriptionDetails", "macAddress", "notifications", "uiStyle" };
            Assert.Equal(expected, json.EnumerateObject().Select(x => x.Name).Order().ToArray());
        }
        Assert.Equal(HttpStatusCode.BadRequest, (await client.GetAsync(Experience + "?version=4")).StatusCode);
    }

    [Theory]
    [InlineData("auto")]
    [InlineData("480")]
    [InlineData("720")]
    [InlineData("1080")]
    [InlineData("2160")]
    public async Task Device_override_wins_and_explicit_inheritance_restores_global_default(string quality)
    {
        using var app = new TestApplication();
        using var admin = Client(app);
        var device = await Register(app);
        await Login(app, admin);
        var token = Token(await admin.GetStringAsync("/Admin/DeviceDetails?id=" + device.InstallationId));
        Assert.Equal(HttpStatusCode.Redirect, (await Post(admin, "SetGlobalPlaybackQuality", token,
            new() { ["PlaybackQuality"] = "480" })).StatusCode);
        Assert.Equal(HttpStatusCode.Redirect, (await Post(admin, "SetDevicePlaybackQuality", token,
            new() { ["Id"] = device.InstallationId.ToString(), ["PlaybackQuality"] = quality })).StatusCode);
        using var bearer = Client(app);
        bearer.DefaultRequestHeaders.Authorization = new("Bearer", device.BearerToken);
        Assert.Equal(quality, (await bearer.GetFromJsonAsync<JsonElement>(Experience + "?version=3"))
            .GetProperty("playbackQuality").GetString());
        Assert.Equal(HttpStatusCode.Redirect, (await Post(admin, "SetDevicePlaybackQuality", token,
            new() { ["Id"] = device.InstallationId.ToString(), ["PlaybackQuality"] = "" })).StatusCode);
        Assert.Equal("480", (await bearer.GetFromJsonAsync<JsonElement>(Experience + "?version=3"))
            .GetProperty("playbackQuality").GetString());
        using var verify = app.Services.CreateScope();
        var saved = await verify.ServiceProvider.GetRequiredService<AppDbContext>().Installations.SingleAsync();
        Assert.Null(saved.PlaybackQuality);
        Assert.True(saved.AllowSubscriptionDetails);
        Assert.Null(saved.UiStyle);
        Assert.Null(saved.LicenseId);
        Assert.Equal("off", saved.KioskMode);
    }

    [Fact]
    public async Task Bulk_apply_and_inherit_cover_all_active_devices_not_only_listed_rows_and_leave_revoked_unchanged()
    {
        using var app = new TestApplication();
        using var client = Client(app);
        await Register(app);
        var revoked = await Register(app);
        using (var scope = app.Services.CreateScope())
        {
            var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();
            await db.Installations.Where(x => x.Id == revoked.InstallationId).ExecuteUpdateAsync(set =>
                set.SetProperty(x => x.Status, InstallationStatus.Revoked).SetProperty(x => x.PlaybackQuality, "2160"));
            for (var i = 0; i < 501; i++)
                db.Installations.Add(new Installation { TokenHash = Guid.NewGuid().ToString("N"),
                    PlaybackQuality = "auto", UiStyle = "studio", KioskMode = "always", AllowLocalExit = false });
            await db.SaveChangesAsync();
        }
        await Login(app, client);
        var token = Token(await client.GetStringAsync("/Admin/Devices"));
        foreach (var mode in new[] { "apply", "inherit" })
        {
            var fields = new Dictionary<string, string> { ["Scope"] = "all", ["Mode"] = mode, ["Confirm"] = "true" };
            if (mode == "apply") fields["PlaybackQuality"] = "720";
            Assert.Equal(HttpStatusCode.Redirect, (await Post(client, "SetAllPlaybackQuality", token, fields)).StatusCode);
            using var verify = app.Services.CreateScope();
            var db = verify.ServiceProvider.GetRequiredService<AppDbContext>();
            var active = await db.Installations.Where(x => x.Status == InstallationStatus.Active).ToListAsync();
            Assert.Equal(502, active.Count);
            Assert.All(active, x => Assert.Equal(mode == "apply" ? "720" : null, x.PlaybackQuality));
            Assert.Equal("2160", (await db.Installations.SingleAsync(x => x.Id == revoked.InstallationId)).PlaybackQuality);
            Assert.Equal("1080", (await db.PlaybackQualityDefaults.SingleAsync()).Quality);
            Assert.Equal(501, active.Count(x => x.UiStyle == "studio" && x.KioskMode == "always" && !x.AllowLocalExit));
        }
    }

    [Theory]
    [InlineData("SetDevicePlaybackQuality")]
    [InlineData("SetGlobalPlaybackQuality")]
    [InlineData("SetAllPlaybackQuality")]
    public async Task Mutations_require_admin_cookie_and_csrf_and_reject_invalid_quality(string action)
    {
        using var app = new TestApplication();
        using var client = Client(app);
        var device = await Register(app);
        var fields = new Dictionary<string, string> { ["Id"] = device.InstallationId.ToString(),
            ["Scope"] = "all", ["Mode"] = "apply", ["Confirm"] = "true", ["PlaybackQuality"] = "720" };
        Assert.Equal(HttpStatusCode.Redirect, (await Post(client, action, null, fields)).StatusCode);
        using var bearer = Client(app);
        bearer.DefaultRequestHeaders.Authorization = new("Bearer", device.BearerToken);
        Assert.Equal(HttpStatusCode.Redirect, (await Post(bearer, action, null, new(fields))).StatusCode);
        await Login(app, client);
        Assert.Equal(HttpStatusCode.BadRequest, (await Post(client, action, null, fields)).StatusCode);
        var token = Token(await client.GetStringAsync("/Admin/Devices"));
        foreach (var invalid in new[] { "360", "1080p", "AUTO", "garbage" })
        {
            fields["PlaybackQuality"] = invalid;
            Assert.Equal(HttpStatusCode.BadRequest, (await Post(client, action, token, fields)).StatusCode);
        }
        using var verify = app.Services.CreateScope();
        var db = verify.ServiceProvider.GetRequiredService<AppDbContext>();
        Assert.Null((await db.Installations.SingleAsync()).PlaybackQuality);
        Assert.Equal("1080", (await db.PlaybackQualityDefaults.SingleAsync()).Quality);
    }

    [Theory]
    [InlineData("scope")]
    [InlineData("confirm")]
    [InlineData("mode")]
    [InlineData("apply_without_quality")]
    [InlineData("inherit_with_quality")]
    public async Task Bulk_requires_explicit_valid_scope_confirmation_and_intent(string invalid)
    {
        using var app = new TestApplication();
        using var client = Client(app);
        await Register(app);
        await Login(app, client);
        var fields = new Dictionary<string, string> { ["Scope"] = "all", ["Mode"] = "apply",
            ["Confirm"] = "true", ["PlaybackQuality"] = "720" };
        if (invalid == "scope") fields["Scope"] = "selected";
        if (invalid == "confirm") fields.Remove("Confirm");
        if (invalid == "mode") fields.Remove("Mode");
        if (invalid == "apply_without_quality") fields.Remove("PlaybackQuality");
        if (invalid == "inherit_with_quality") fields["Mode"] = "inherit";
        Assert.Equal(HttpStatusCode.BadRequest, (await Post(client, "SetAllPlaybackQuality",
            Token(await client.GetStringAsync("/Admin/Devices")), fields)).StatusCode);
        using var verify = app.Services.CreateScope();
        Assert.Null((await verify.ServiceProvider.GetRequiredService<AppDbContext>().Installations.SingleAsync()).PlaybackQuality);
    }

    [Theory]
    [InlineData("missing")]
    [InlineData("unknown")]
    [InlineData("revoked")]
    public async Task Device_change_rejects_missing_quality_and_unknown_or_revoked_device(string invalid)
    {
        using var app = new TestApplication();
        using var client = Client(app);
        var device = await Register(app);
        await Login(app, client);
        if (invalid == "revoked")
        {
            using var scope = app.Services.CreateScope();
            await scope.ServiceProvider.GetRequiredService<AppDbContext>().Installations.ExecuteUpdateAsync(set =>
                set.SetProperty(x => x.Status, InstallationStatus.Revoked));
        }
        var fields = new Dictionary<string, string> { ["Id"] = (invalid == "unknown" ? Guid.NewGuid() : device.InstallationId).ToString() };
        if (invalid != "missing") fields["PlaybackQuality"] = "720";
        Assert.Equal(HttpStatusCode.BadRequest, (await Post(client, "SetDevicePlaybackQuality",
            Token(await client.GetStringAsync("/Admin/Devices")), fields)).StatusCode);
        using var verify = app.Services.CreateScope();
        Assert.Null((await verify.ServiceProvider.GetRequiredService<AppDbContext>().Installations.SingleAsync()).PlaybackQuality);
    }

    private static Task<HttpResponseMessage> Post(HttpClient client, string action, string? token, Dictionary<string, string> fields)
    {
        var form = new Dictionary<string, string>(fields);
        if (token is not null) form["__RequestVerificationToken"] = token;
        return client.PostAsync("/Admin/" + action, new FormUrlEncodedContent(form));
    }
    private static HttpClient Client(TestApplication app) => app.CreateClient(new WebApplicationFactoryClientOptions
    { AllowAutoRedirect = false, BaseAddress = new Uri("https://localhost") });
    private static async Task<RegisterResponse> Register(TestApplication app)
    {
        using var scope = app.Services.CreateScope();
        var request = new V1RegisterRequest { InstallationId = Guid.NewGuid(), Credential = Secrets.RandomToken(),
            AppVersionCode = 1, AppVersionName = "1.0", PackageName = "com.megastream.app", Channel = "stable",
            Manufacturer = "Example", Model = "TV", AndroidApi = 34, AndroidRelease = "14", Abi = "arm64_v8a", Locale = "en-US", ManagedDevice = false };
        await scope.ServiceProvider.GetRequiredService<V1Service>().RegisterAsync(request, Guid.NewGuid().ToString("D"));
        return new(request.InstallationId, request.Credential);
    }
    private static async Task Login(TestApplication app, HttpClient client)
    {
        using (var scope = app.Services.CreateScope())
        {
            var users = scope.ServiceProvider.GetRequiredService<UserManager<IdentityUser>>();
            Assert.True((await users.CreateAsync(new IdentityUser { UserName = "quality@example.invalid", Email = "quality@example.invalid" }, "Quality-Password8!")).Succeeded);
        }
        Assert.Equal(HttpStatusCode.Redirect, (await client.PostAsync("/Account/Login", new FormUrlEncodedContent(new Dictionary<string, string>
        { ["UserName"] = "quality@example.invalid", ["Password"] = "Quality-Password8!",
          ["__RequestVerificationToken"] = Token(await client.GetStringAsync("/Account/Login")) }))).StatusCode);
    }
    private static string Token(string html)
    {
        var input = Regex.Match(html, "<input[^>]*name=\"__RequestVerificationToken\"[^>]*>").Value;
        var value = WebUtility.HtmlDecode(Regex.Match(input, "value=\"([^\"]+)\"").Groups[1].Value);
        Assert.NotEmpty(value);
        return value;
    }
}
