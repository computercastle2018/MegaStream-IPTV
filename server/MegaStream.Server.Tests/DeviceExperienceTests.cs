using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using System.Text.Json.Nodes;
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

public sealed class DeviceExperienceTests
{
    private const string Path = "/api/v1/devices/experience";

    [Theory]
    [InlineData("GET")]
    [InlineData("POST")]
    public async Task Experience_requires_bearer_not_cookie_query_or_revoked_device(string method)
    {
        using var app = new TestApplication();
        using var client = Client(app);
        var device = await Register(app);
        using var anonymous = await Request(client, method, Path + "?installationId=" + device.InstallationId + "&token=" + device.BearerToken);
        Assert.Equal(HttpStatusCode.Unauthorized, anonymous.StatusCode);
        Assert.True(anonymous.Headers.CacheControl?.NoStore);
        Assert.Null(anonymous.Headers.Location);
        await Login(app, client);
        using var cookie = await Request(client, method, Path);
        Assert.Equal(HttpStatusCode.Unauthorized, cookie.StatusCode);
        client.DefaultRequestHeaders.Authorization = new("Bearer", device.BearerToken);
        using var active = await Request(client, method, Path);
        Assert.Equal(method == "GET" ? HttpStatusCode.OK : HttpStatusCode.NoContent, active.StatusCode);
        using (var scope = app.Services.CreateScope())
            await scope.ServiceProvider.GetRequiredService<AppDbContext>().Installations
                .Where(x => x.Id == device.InstallationId).ExecuteUpdateAsync(set => set.SetProperty(x => x.Status, InstallationStatus.Revoked));
        using var revoked = await Request(client, method, Path);
        Assert.Equal(HttpStatusCode.Forbidden, revoked.StatusCode);
    }

    [Fact]
    public async Task Experience_filters_targets_expiration_and_future_records_caps_latest_50_and_redacts_secrets()
    {
        using var app = new TestApplication();
        using var client = Client(app);
        var owner = await Register(app);
        var other = await Register(app);
        var now = DateTime.UtcNow;
        using (var scope = app.Services.CreateScope())
        {
            var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();
            for (var i = 0; i < 55; i++)
                db.AdminNotifications.Add(new() { Title = "Notice " + i, Message = "Available", CreatedAt = now.AddMinutes(-i - 1),
                    TargetInstallationId = i % 2 == 0 ? null : owner.InstallationId });
            db.AdminNotifications.AddRange(
                new() { Title = "Other device", Message = "Private", TargetInstallationId = other.InstallationId },
                new() { Title = "Expired", Message = "Expired", ExpiresAt = now.AddSeconds(-1) },
                new() { Title = "Future", Message = "Future", CreatedAt = now.AddDays(1) },
                new() { Title = "Safe title", Message = "password=never-send-this", CreatedAt = now, ExpiresAt = now.AddDays(1) });
            await db.Installations.Where(x => x.Id == owner.InstallationId)
                .ExecuteUpdateAsync(set => set.SetProperty(x => x.AllowSubscriptionDetails, false));
            await db.SaveChangesAsync();
        }
        client.DefaultRequestHeaders.Authorization = new("Bearer", owner.BearerToken);
        using var response = await client.GetAsync(Path + "?installationId=" + other.InstallationId);
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.True(response.Headers.CacheControl?.NoStore);
        var json = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal(new[] { "allowSubscriptionDetails", "macAddress", "notifications" }, json.EnumerateObject().Select(x => x.Name).Order().ToArray());
        Assert.False(json.GetProperty("allowSubscriptionDetails").GetBoolean());
        Assert.Equal(JsonValueKind.Null, json.GetProperty("macAddress").ValueKind);
        var notices = json.GetProperty("notifications").EnumerateArray().ToArray();
        Assert.Equal(50, notices.Length);
        Assert.Equal("Safe title", notices[0].GetProperty("title").GetString());
        Assert.Equal("Notice 48", notices[^1].GetProperty("title").GetString());
        Assert.DoesNotContain("never-send-this", json.ToString());
        foreach (var notice in notices)
        {
            Assert.Equal(new[] { "createdAt", "id", "message", "title" }, notice.EnumerateObject().Select(x => x.Name).Order().ToArray());
            Assert.True(Guid.TryParse(notice.GetProperty("id").GetString(), out _));
            Assert.EndsWith("Z", notice.GetProperty("createdAt").GetString());
        }
        var repeated = await client.GetFromJsonAsync<JsonElement>(Path);
        Assert.Equal(notices.Select(x => x.GetProperty("id").GetString()),
            repeated.GetProperty("notifications").EnumerateArray().Select(x => x.GetProperty("id").GetString()));
    }

    [Theory]
    [InlineData("00:11:22:33:44:55", true)]
    [InlineData("02-aa-bb-cc-dd-ee", true)]
    [InlineData("00:00:00:00:00:00", false)]
    [InlineData("02:00:00:00:00:00", false)]
    [InlineData("FF:FF:FF:FF:FF:FF", false)]
    [InlineData("01:11:22:33:44:55", false)]
    [InlineData("00:11-22:33:44:55", false)]
    [InlineData("GG:11:22:33:44:55", false)]
    [InlineData("001122334455", false)]
    [InlineData("00:11:22:33:44:55:66", false)]
    [InlineData("", false)]
    public async Task Mac_reports_validate_normalize_and_never_mutate_another_device(string mac, bool valid)
    {
        using var app = new TestApplication();
        using var client = Client(app);
        var owner = await Register(app);
        var other = await Register(app);
        client.DefaultRequestHeaders.Authorization = new("Bearer", owner.BearerToken);
        using var response = await client.PostAsJsonAsync(Path + "?installationId=" + other.InstallationId,
            new { macAddress = mac, installationId = other.InstallationId, allowSubscriptionDetails = false });
        Assert.Equal(valid ? HttpStatusCode.NoContent : HttpStatusCode.BadRequest, response.StatusCode);
        using var scope = app.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();
        var installation = await db.Installations.SingleAsync(x => x.Id == owner.InstallationId);
        Assert.Equal(valid ? mac.Replace('-', ':').ToUpperInvariant() : null, installation.MacAddress);
        Assert.True(installation.AllowSubscriptionDetails);
        Assert.Null((await db.Installations.SingleAsync(x => x.Id == other.InstallationId)).MacAddress);
    }

    [Fact]
    public async Task Null_or_missing_mac_preserves_record_and_does_not_change_presence_license_or_kiosk()
    {
        using var app = new TestApplication();
        using var client = Client(app);
        var device = await Register(app);
        client.DefaultRequestHeaders.Authorization = new("Bearer", device.BearerToken);
        DateTime lastSeen;
        using (var scope = app.Services.CreateScope())
            lastSeen = (await scope.ServiceProvider.GetRequiredService<AppDbContext>().Installations.SingleAsync()).LastSeenAt;
        Assert.Equal(HttpStatusCode.NoContent, (await client.PostAsJsonAsync(Path, new { macAddress = "00:11:22:33:44:55" })).StatusCode);
        foreach (var body in new object[] { new { macAddress = (string?)null }, new { } })
            Assert.Equal(HttpStatusCode.NoContent, (await client.PostAsJsonAsync(Path, body)).StatusCode);
        var experience = await client.GetFromJsonAsync<DeviceExperienceResponse>(Path);
        Assert.Equal("00:11:22:33:44:55", experience!.MacAddress);
        using var verify = app.Services.CreateScope();
        var installation = await verify.ServiceProvider.GetRequiredService<AppDbContext>().Installations.SingleAsync();
        Assert.Equal(lastSeen, installation.LastSeenAt);
        Assert.Null(installation.LicenseId);
        Assert.Equal("off", installation.KioskMode);
        Assert.True(installation.AllowLocalExit);
    }

    [Theory]
    [InlineData("/Admin/SendNotification")]
    [InlineData("/Admin/SetSubscriptionDetailsPolicy")]
    public async Task Admin_experience_changes_require_csrf_even_with_admin_cookie(string path)
    {
        using var app = new TestApplication();
        using var client = Client(app);
        var device = await Register(app);
        await Login(app, client);
        using var response = await client.PostAsync(path, new FormUrlEncodedContent(new Dictionary<string, string>
        {
            ["Id"] = device.InstallationId.ToString(), ["AllowSubscriptionDetails"] = "false", ["Title"] = "Notice", ["Message"] = "Text"
        }));
        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        using var scope = app.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();
        Assert.True((await db.Installations.SingleAsync()).AllowSubscriptionDetails);
        Assert.Empty(await db.AdminNotifications.ToListAsync());
    }

    [Fact]
    public async Task Admin_policy_checkbox_false_and_true_persist_without_changing_kiosk_or_license()
    {
        using var app = new TestApplication();
        using var client = Client(app);
        var device = await Register(app);
        await Login(app, client);
        using (var scope = app.Services.CreateScope())
            await scope.ServiceProvider.GetRequiredService<AppDbContext>().Installations
                .ExecuteUpdateAsync(set => set.SetProperty(x => x.KioskMode, "always").SetProperty(x => x.AllowLocalExit, false));
        foreach (var enabled in new[] { false, true })
        {
            var html = await client.GetStringAsync("/Admin/DeviceDetails/" + device.InstallationId);
            Assert.Contains("name=\"AllowSubscriptionDetails\" value=\"false\"", html);
            var fields = new List<KeyValuePair<string, string>> { new("Id", device.InstallationId.ToString()), new("__RequestVerificationToken", Token(html)) };
            if (enabled) fields.Add(new("AllowSubscriptionDetails", "true"));
            fields.Add(new("AllowSubscriptionDetails", "false"));
            fields.Add(new("KioskMode", "off"));
            using var response = await client.PostAsync("/Admin/SetSubscriptionDetailsPolicy", new FormUrlEncodedContent(fields));
            Assert.Equal(HttpStatusCode.Redirect, response.StatusCode);
            using var verify = app.Services.CreateScope();
            var installation = await verify.ServiceProvider.GetRequiredService<AppDbContext>().Installations.SingleAsync();
            Assert.Equal(enabled, installation.AllowSubscriptionDetails);
            Assert.Equal("always", installation.KioskMode);
            Assert.False(installation.AllowLocalExit);
            Assert.Null(installation.LicenseId);
        }
    }

    [Theory]
    [InlineData("global")]
    [InlineData("device")]
    [InlineData("missing")]
    [InlineData("revoked")]
    [InlineData("malformed")]
    [InlineData("oversize")]
    [InlineData("oversize_message")]
    [InlineData("empty_after_sanitization")]
    [InlineData("expired")]
    public async Task Admin_notification_validates_target_content_and_expiration_before_saving(string scenario)
    {
        using var app = new TestApplication();
        using var client = Client(app);
        var device = await Register(app);
        await Login(app, client);
        if (scenario == "revoked")
        {
            using var scope = app.Services.CreateScope();
            await scope.ServiceProvider.GetRequiredService<AppDbContext>().Installations.ExecuteUpdateAsync(set => set.SetProperty(x => x.Status, InstallationStatus.Revoked));
        }
        var form = new Dictionary<string, string>
        {
            ["__RequestVerificationToken"] = Token(await client.GetStringAsync("/Admin/Notifications")),
            ["Title"] = scenario == "oversize" ? new string('x', 129) : "Notice",
            ["Message"] = scenario switch { "oversize_message" => new string('x', 2001), "empty_after_sanitization" => "\u0001", _ => "Service update password=do-not-deliver" },
            ["TargetInstallationId"] = scenario switch { "global" => "", "missing" => Guid.NewGuid().ToString(), "malformed" => "not-a-guid", _ => device.InstallationId.ToString() }
        };
        if (scenario == "expired") form["ExpiresAt"] = "2000-01-01T12:00";
        if (scenario == "device") form["ExpiresAt"] = "2099-01-01T12:00";
        var before = DateTime.UtcNow;
        using var response = await client.PostAsync("/Admin/SendNotification", new FormUrlEncodedContent(form));
        var valid = scenario is "global" or "device";
        Assert.Equal(valid ? HttpStatusCode.Redirect : HttpStatusCode.BadRequest, response.StatusCode);
        using var verify = app.Services.CreateScope();
        var notifications = await verify.ServiceProvider.GetRequiredService<AppDbContext>().AdminNotifications.ToListAsync();
        if (!valid) { Assert.Empty(notifications); return; }
        var saved = Assert.Single(notifications);
        Assert.Equal(scenario == "global" ? (Guid?)null : device.InstallationId, saved.TargetInstallationId);
        Assert.DoesNotContain("do-not-deliver", saved.Message);
        if (scenario == "global") Assert.InRange(saved.ExpiresAt!.Value, before.AddDays(30), DateTime.UtcNow.AddDays(30));
        else Assert.Equal(new DateTime(2099, 1, 1, 12, 0, 0), saved.ExpiresAt);
    }

    [Fact]
    public async Task Provider_start_date_is_optional_source_metadata_not_installation_creation_date()
    {
        using var app = new TestApplication();
        using var client = Client(app);
        var device = await Register(app);
        client.DefaultRequestHeaders.Authorization = new("Bearer", device.BearerToken);
        const long sourceDate = 1577836800000;
        var old = new { localId = 1, name = "Old subscription", type = "m3u", enabled = true, status = "active", maxConnections = 1 };
        var source = new { localId = 2, name = "Source subscription", type = "m3u", enabled = true, status = "active", maxConnections = 1, startedAt = sourceDate };
        using var response = await client.PostAsJsonAsync("/api/v1/devices/subscriptions", new { subscriptions = new object[] { old, source } });
        Assert.Equal(HttpStatusCode.NoContent, response.StatusCode);
        using (var scope = app.Services.CreateScope())
        {
            var json = (await scope.ServiceProvider.GetRequiredService<AppDbContext>().Installations.SingleAsync()).LocalSubscriptionsJson!;
            var subscriptions = JsonSerializer.Deserialize<List<LocalSubscription>>(json)!;
            Assert.Null(subscriptions[0].StartedAt);
            Assert.Equal(sourceDate, subscriptions[1].StartedAt);
        }
        client.DefaultRequestHeaders.Authorization = null;
        await Login(app, client);
        var html = WebUtility.HtmlDecode(await client.GetStringAsync("/Admin/DeviceDetails/" + device.InstallationId));
        Assert.Contains("غير معروفة", html);
        Assert.Contains("2020-01-01 00:00:00", html);
    }

    [Theory]
    [InlineData("missing")]
    [InlineData("malformed")]
    [InlineData("revoked")]
    public async Task Invalid_policy_submission_is_rejected_without_changing_policy(string scenario)
    {
        using var app = new TestApplication();
        using var client = Client(app);
        var device = await Register(app);
        await Login(app, client);
        if (scenario == "revoked")
        {
            using var scope = app.Services.CreateScope();
            await scope.ServiceProvider.GetRequiredService<AppDbContext>().Installations.ExecuteUpdateAsync(set => set.SetProperty(x => x.Status, InstallationStatus.Revoked));
        }
        var form = new Dictionary<string, string> { ["Id"] = device.InstallationId.ToString(),
            ["__RequestVerificationToken"] = Token(await client.GetStringAsync("/Admin/DeviceDetails/" + device.InstallationId)) };
        if (scenario != "missing") form["AllowSubscriptionDetails"] = scenario == "malformed" ? "garbage" : "false";
        using var response = await client.PostAsync("/Admin/SetSubscriptionDetailsPolicy", new FormUrlEncodedContent(form));
        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        using var verify = app.Services.CreateScope();
        Assert.True((await verify.ServiceProvider.GetRequiredService<AppDbContext>().Installations.SingleAsync()).AllowSubscriptionDetails);
    }

    [Fact]
    public async Task Old_strict_heartbeat_contract_still_accepts_old_app_and_rejects_experience_fields()
    {
        using var app = new TestApplication();
        using var client = Client(app);
        var device = await Register(app);
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", device.BearerToken);
        var heartbeat = new V1HeartbeatRequest { AppSessionId = Guid.NewGuid(), Sequence = 0, Mode = "foreground",
            AppVersionCode = 1, PackageName = "com.megastream.app", Channel = "stable", AppVersionName = "1.0", ManagedDevice = false };
        using var response = await client.PostAsJsonAsync("/api/v1/devices/heartbeat", heartbeat);
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        var json = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.False(json.TryGetProperty("allowSubscriptionDetails", out _));
        Assert.False(json.TryGetProperty("notifications", out _));
        Assert.False(json.TryGetProperty("macAddress", out _));
        var body = JsonSerializer.SerializeToNode(heartbeat, new JsonSerializerOptions(JsonSerializerDefaults.Web))!.AsObject();
        body["macAddress"] = "00:11:22:33:44:55";
        using var rejected = await client.PostAsJsonAsync("/api/v1/devices/heartbeat", body);
        Assert.Equal(HttpStatusCode.BadRequest, rejected.StatusCode);
    }

    private static HttpClient Client(TestApplication app) => app.CreateClient(new WebApplicationFactoryClientOptions
    { AllowAutoRedirect = false, BaseAddress = new Uri("https://localhost") });
    private static async Task<RegisterResponse> Register(TestApplication app)
    {
        using var scope = app.Services.CreateScope();
        var registration = new V1RegisterRequest { InstallationId = Guid.NewGuid(), Credential = Secrets.RandomToken(),
            AppVersionCode = 1, AppVersionName = "1.0", PackageName = "com.megastream.app", Channel = "stable",
            Manufacturer = "Example", Model = "TV", AndroidApi = 34, AndroidRelease = "14", Abi = "arm64_v8a", Locale = "en-US", ManagedDevice = false };
        await scope.ServiceProvider.GetRequiredService<V1Service>().RegisterAsync(registration, Guid.NewGuid().ToString("D"));
        return new(registration.InstallationId, registration.Credential);
    }
    private static Task<HttpResponseMessage> Request(HttpClient client, string method, string path) => method == "GET"
        ? client.GetAsync(path) : client.PostAsJsonAsync(path, new { macAddress = (string?)null });
    private static async Task Login(TestApplication app, HttpClient client)
    {
        using (var scope = app.Services.CreateScope())
        {
            var users = scope.ServiceProvider.GetRequiredService<UserManager<IdentityUser>>();
            Assert.True((await users.CreateAsync(new IdentityUser { UserName = "experience@example.invalid", Email = "experience@example.invalid" }, "Experience-Password8!")).Succeeded);
        }
        using var response = await client.PostAsync("/Account/Login", new FormUrlEncodedContent(new Dictionary<string, string>
        {
            ["UserName"] = "experience@example.invalid", ["Password"] = "Experience-Password8!",
            ["__RequestVerificationToken"] = Token(await client.GetStringAsync("/Account/Login"))
        }));
        Assert.Equal(HttpStatusCode.Redirect, response.StatusCode);
    }
    private static string Token(string html)
    {
        var input = Regex.Match(html, "<input[^>]*name=\"__RequestVerificationToken\"[^>]*>").Value;
        var token = WebUtility.HtmlDecode(Regex.Match(input, "value=\"([^\"]+)\"").Groups[1].Value);
        Assert.NotEmpty(token);
        return token;
    }
}
