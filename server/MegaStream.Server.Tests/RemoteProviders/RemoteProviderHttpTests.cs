using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using System.Text.RegularExpressions;
using MegaStream.Server.Contracts;
using MegaStream.Server.Data;
using MegaStream.Server.RemoteProviders.Core;
using MegaStream.Server.Services;
using MegaStream.Server.Models;
using Microsoft.Extensions.Configuration;
using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Xunit;

namespace MegaStream.Server.Tests.RemoteProviders;

public sealed class RemoteProviderHttpTests
{
    [Theory]
    [InlineData(2, false)]
    [InlineData(10, true)]
    public async Task Index_lists_only_active_reports_from_connected_active_devices_using_shared_online_window(int window, bool includesOlder)
    {
        using var app = new TestApplication();
        using var client = Client(app);
        await LoginAsync(app, client);
        app.Services.GetRequiredService<IConfiguration>()["ONLINE_WINDOW_MINUTES"] = window.ToString();
        RegisterResponse connected, older, offline, revoked, stale;
        Guid licenseId;
        using (var scope = app.Services.CreateScope())
        {
            var devices = scope.ServiceProvider.GetRequiredService<DeviceService>();
            connected = await devices.RegisterAsync(new(Guid.NewGuid().ToString("D"), "android", "1.0", "Connected TV", "14"));
            older = await devices.RegisterAsync(new(Guid.NewGuid().ToString("D"), "android", "1.0", "Older TV", "14"));
            offline = await devices.RegisterAsync(new(Guid.NewGuid().ToString("D"), "android", "1.0", "Offline TV", "14"));
            revoked = await devices.RegisterAsync(new(Guid.NewGuid().ToString("D"), "android", "1.0", "Revoked TV", "14"));
            stale = await devices.RegisterAsync(new(Guid.NewGuid().ToString("D"), "android", "1.0", "Stale TV", "14"));
            var admin = scope.ServiceProvider.GetRequiredService<IAdminService>();
            licenseId = (await admin.CreateLicenseAsync("Linked license", DateTime.UtcNow.AddDays(-1), DateTime.UtcNow.AddDays(1), 1)).License.Id;
            await admin.AssignInstallationLicenseAsync(connected.InstallationId, licenseId);
        }
        var expiry = DateTimeOffset.UtcNow.AddDays(1).ToUnixTimeMilliseconds();
        using var reporter = Client(app);
        reporter.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", connected.BearerToken);
        using var reported = await reporter.PostAsJsonAsync("/api/v1/devices/subscriptions", new LocalSubscriptionsRequest
        {
            Subscriptions = [
                Subscription(1, "Connected sports", "active", true, expiry),
                Subscription(2, "No expiry", "active", true, null, "m3u"),
                Subscription(3, "Disabled report", "active", false, expiry),
                Subscription(4, "Expired report", "active", true, DateTimeOffset.UtcNow.AddDays(-1).ToUnixTimeMilliseconds()),
                Subscription(5, "Partial report", "partial", true, expiry),
                Subscription(6, "Error report", "error", true, expiry),
                Subscription(7, "https://provider.invalid/live/private-user/private-password/1", "active", true, expiry, "stalker_portal")
            ]
        });
        Assert.Equal(HttpStatusCode.NoContent, reported.StatusCode);
        foreach (var (device, name) in new[] { (older, "Older subscription"), (offline, "Offline subscription"), (revoked, "Revoked subscription"), (stale, "Stale subscription") })
        {
            reporter.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", device.BearerToken);
            using var response = await reporter.PostAsJsonAsync("/api/v1/devices/subscriptions", new LocalSubscriptionsRequest
            { Subscriptions = [Subscription(1, name, "active", true, expiry)] });
            Assert.Equal(HttpStatusCode.NoContent, response.StatusCode);
        }
        DateTime reportedAt;
        using (var scope = app.Services.CreateScope())
        {
            var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();
            (await db.Installations.SingleAsync(x => x.Id == older.InstallationId)).LastSeenAt = DateTime.UtcNow.AddMinutes(-3);
            (await db.Installations.SingleAsync(x => x.Id == offline.InstallationId)).LastSeenAt = DateTime.UtcNow.AddHours(-1);
            (await db.Installations.SingleAsync(x => x.Id == revoked.InstallationId)).Status = InstallationStatus.Revoked;
            (await db.Installations.SingleAsync(x => x.Id == stale.InstallationId)).LocalSubscriptionsReportedAt = DateTime.UtcNow.AddHours(-1);
            reportedAt = (await db.Installations.SingleAsync(x => x.Id == connected.InstallationId)).LocalSubscriptionsReportedAt!.Value;
            await db.SaveChangesAsync();
        }
        using var page = await client.GetAsync("/admin/providers");
        Assert.Equal(HttpStatusCode.OK, page.StatusCode);
        Assert.True(page.Headers.CacheControl?.NoStore);
        var html = WebUtility.HtmlDecode(await page.Content.ReadAsStringAsync());
        Assert.Contains("Connected sports", html);
        Assert.Contains("No expiry", html);
        Assert.Equal(includesOlder, html.Contains("Older subscription"));
        foreach (var excluded in new[] { "Disabled report", "Expired report", "Partial report", "Error report", "Offline subscription", "Revoked subscription", "Stale subscription", "provider.invalid", "private-user", "private-password" })
            Assert.DoesNotContain(excluded, html);
        Assert.Contains($"/Admin/DeviceDetails?id={connected.InstallationId}", html);
        Assert.Contains($"/Admin/LicenseDetails?id={licenseId}", html);
        Assert.Contains(reportedAt.ToString("yyyy-MM-dd HH:mm:ss"), html);
        Assert.Contains(DateTimeOffset.FromUnixTimeMilliseconds(expiry).ToString("yyyy-MM-dd HH:mm:ss"), html);
        Assert.Contains("<details class=\"subscription-information\"><summary>", html);
        Assert.DoesNotContain("<details class=\"subscription-information\" open", html);
    }

    [Theory]
    [InlineData(RemoteProviderValues.XtreamCodes)]
    [InlineData(RemoteProviderValues.M3u)]
    [InlineData(RemoteProviderValues.StalkerPortal)]
    public async Task Index_create_buttons_open_supported_type_with_registered_device_target(string type)
    {
        using var app = new TestApplication();
        using var client = Client(app);
        await LoginAsync(app, client);
        Guid id;
        using (var scope = app.Services.CreateScope())
            id = (await scope.ServiceProvider.GetRequiredService<DeviceService>().RegisterAsync(new(Guid.NewGuid().ToString("D"), "android", "1.0", "Target TV", "14"))).InstallationId;
        var html = await client.GetStringAsync("/admin/providers");
        Assert.Contains($"href=\"/admin/providers/create?type={type}\"", html);
        Assert.Contains($"value=\"{id}\"", html);
        var editor = await client.GetStringAsync($"/admin/providers/create?type={type}");
        Assert.Contains($"<option value=\"{type}\" selected>", editor);
        Assert.Contains($"value=\"{id}\"", editor);
        using var target = await client.GetAsync($"/admin/providers/installations?installationId={id}");
        Assert.Equal(HttpStatusCode.Redirect, target.StatusCode);
        Assert.EndsWith($"/installations/{id}", target.Headers.Location!.OriginalString);
    }

    [Theory]
    [InlineData("{")]
    [InlineData("[null]")]
    [InlineData("[{\"password\":\"persisted-secret\"}]")]
    public async Task Invalid_report_is_flagged_without_exposing_payload_or_breaking_create_buttons(string json)
    {
        using var app = new TestApplication();
        using var client = Client(app);
        await LoginAsync(app, client);
        using (var scope = app.Services.CreateScope())
        {
            var devices = scope.ServiceProvider.GetRequiredService<DeviceService>();
            var registered = await devices.RegisterAsync(new(Guid.NewGuid().ToString("D"), "android", "1.0", "TV", "14"));
            var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();
            var device = await db.Installations.SingleAsync(x => x.Id == registered.InstallationId);
            device.LocalSubscriptionsJson = json;
            device.LocalSubscriptionsReportedAt = DateTime.UtcNow;
            await db.SaveChangesAsync();
        }
        var html = WebUtility.HtmlDecode(await client.GetStringAsync("/admin/providers"));
        Assert.Contains("تعذر عرض بعض بلاغات الاشتراكات", html);
        Assert.DoesNotContain("persisted-secret", html);
        Assert.Contains("/admin/providers/create?type=M3U", html);
    }

    private static LocalSubscription Subscription(long id, string name, string status, bool enabled, long? expiry, string type = "xtream_codes") =>
        new() { LocalId = id, Name = name, Status = status, Enabled = enabled, ExpiresAt = expiry, Type = type, MaxConnections = 2 };

    [Theory]
    [InlineData("optional")]
    [InlineData("auto_enabled")]
    public async Task Create_form_assigns_subscription_only_to_selected_device(string policy)
    {
        using var app = new TestApplication();
        using var client = Client(app);
        await LoginAsync(app, client);
        RegisterResponse owner, other;
        using (var scope = app.Services.CreateScope())
        {
            var devices = scope.ServiceProvider.GetRequiredService<DeviceService>();
            owner = await devices.RegisterAsync(new(Guid.NewGuid().ToString("D"), "android", "1.0", "Selected TV", "14"));
            other = await devices.RegisterAsync(new(Guid.NewGuid().ToString("D"), "android", "1.0", "Other TV", "14"));
        }
        var html = await client.GetStringAsync("/admin/providers/create?type=XTREAM_CODES");
        Assert.Contains($"value=\"{owner.InstallationId:D}\"", html);
        var form = Form();
        form["__RequestVerificationToken"] = Token(html);
        form["installationId"] = owner.InstallationId.ToString("D");
        form["assignmentPolicy"] = policy;
        using var response = await client.PostAsync("/admin/providers/create", new FormUrlEncodedContent(form));
        Assert.Equal(HttpStatusCode.Redirect, response.StatusCode);
        Assert.EndsWith($"/installations/{owner.InstallationId:D}", response.Headers.Location!.OriginalString);
        using var verify = app.Services.CreateScope();
        var service = verify.ServiceProvider.GetRequiredService<IRemoteProviderService>();
        var assignment = Assert.Single((await service.GetDeviceAsync(owner.InstallationId)).Items);
        Assert.Equal(policy, assignment.Policy);
        Assert.Empty((await service.GetDeviceAsync(other.InstallationId)).Items);
    }

    [Theory]
    [InlineData("not-a-device")]
    [InlineData("00000000-0000-0000-0000-000000000001")]
    public async Task Invalid_selected_device_does_not_save_subscription_or_echo_credentials(string device)
    {
        using var app = new TestApplication();
        using var client = Client(app);
        await LoginAsync(app, client);
        var form = Form();
        form["__RequestVerificationToken"] = Token(await client.GetStringAsync("/admin/providers/create"));
        form["installationId"] = device;
        form["assignmentPolicy"] = "auto_enabled";
        using var response = await client.PostAsync("/admin/providers/create", new FormUrlEncodedContent(form));
        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        var html = WebUtility.HtmlDecode(await response.Content.ReadAsStringAsync());
        Assert.DoesNotContain(form["password"], html);
        using var verify = app.Services.CreateScope();
        Assert.Empty(await verify.ServiceProvider.GetRequiredService<IRemoteProviderService>().ListProfilesAsync());
    }

    [Theory]
    [InlineData("GET", "/api/v1/providers/assignments")]
    [InlineData("POST", "/api/v1/providers/assignments/00000000-0000-0000-0000-000000000001/status")]
    public async Task Anonymous_provider_api_returns_uncacheable_401_without_login_redirect(string method, string path)
    {
        using var app = new TestApplication();
        using var client = Client(app);
        using var request = new HttpRequestMessage(new HttpMethod(method), path);
        if (method == "POST") request.Content = JsonContent.Create(new { profileRevision = 1, state = "applied" });
        using var response = await client.SendAsync(request);
        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
        Assert.Null(response.Headers.Location);
        Assert.True(response.Headers.CacheControl?.NoStore);
        Assert.Equal("application/problem+json", response.Content.Headers.ContentType?.MediaType);
    }

    [Fact]
    public async Task Authenticated_admin_without_antiforgery_cannot_create_provider()
    {
        using var app = new TestApplication();
        using var client = Client(app);
        await LoginAsync(app, client);
        using var response = await client.PostAsync("/admin/providers/create", new FormUrlEncodedContent(Form()));
        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        using var scope = app.Services.CreateScope();
        Assert.Empty(await scope.ServiceProvider.GetRequiredService<AppDbContext>().Set<RemoteProviderProfile>().ToListAsync());
    }

    [Fact]
    public async Task Invalid_admin_submission_does_not_redisplay_secret_attempted_values()
    {
        using var app = new TestApplication();
        using var client = Client(app);
        await LoginAsync(app, client);
        var form = Form();
        form["__RequestVerificationToken"] = Token(await client.GetStringAsync("/admin/providers/create"));
        form["serverUrl"] = "file:///private-server-secret";
        using var response = await client.PostAsync("/admin/providers/create", new FormUrlEncodedContent(form));
        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        Assert.True(response.Headers.CacheControl?.NoStore);
        var html = WebUtility.HtmlDecode(await response.Content.ReadAsStringAsync());
        foreach (var name in new[] { "serverUrl", "username", "password", "epgUrl", "httpHeaders" })
            Assert.DoesNotContain(form[name], html, StringComparison.Ordinal);
        using var scope = app.Services.CreateScope();
        Assert.Empty(await scope.ServiceProvider.GetRequiredService<AppDbContext>().Set<RemoteProviderProfile>().ToListAsync());
    }

    [Fact]
    public async Task Admin_detail_and_list_never_render_persisted_credentials()
    {
        using var app = new TestApplication();
        using var client = Client(app);
        await LoginAsync(app, client);
        var input = RemoteProviderTestData.Profile();
        Guid profileId;
        using (var scope = app.Services.CreateScope())
            profileId = (await scope.ServiceProvider.GetRequiredService<IRemoteProviderService>().CreateProfileAsync(input)).Id;
        foreach (var path in new[] { "/admin/providers", $"/admin/providers/{profileId:D}" })
        {
            using var response = await client.GetAsync(path);
            Assert.Equal(HttpStatusCode.OK, response.StatusCode);
            Assert.True(response.Headers.CacheControl?.NoStore);
            var html = WebUtility.HtmlDecode(await response.Content.ReadAsStringAsync());
            foreach (var secret in RemoteProviderTestData.SecretValues(input.Configuration))
                Assert.DoesNotContain(secret, html, StringComparison.Ordinal);
        }
    }

    [Fact]
    public async Task Device_bearer_identity_not_query_parameter_controls_get_and_report_ownership()
    {
        using var app = new TestApplication();
        using var ownerClient = Client(app);
        using var otherClient = Client(app);
        RegisterResponse owner;
        RegisterResponse other;
        RemoteProviderAssignment assignment;
        RemoteProviderProfileMetadata profile;
        using (var scope = app.Services.CreateScope())
        {
            var devices = scope.ServiceProvider.GetRequiredService<DeviceService>();
            owner = await devices.RegisterAsync(new(Guid.NewGuid().ToString("D"), "android", "1.0", "TV", "14"));
            other = await devices.RegisterAsync(new(Guid.NewGuid().ToString("D"), "android", "1.0", "TV", "14"));
            var providers = scope.ServiceProvider.GetRequiredService<IRemoteProviderService>();
            profile = await providers.CreateProfileAsync(RemoteProviderTestData.Profile());
            assignment = await providers.AssignAsync(profile.Id, owner.InstallationId, new());
        }
        ownerClient.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", owner.BearerToken);
        otherClient.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", other.BearerToken);
        using var ownerResponse = await ownerClient.GetAsync("/api/v1/providers/assignments");
        Assert.Equal(HttpStatusCode.OK, ownerResponse.StatusCode);
        Assert.True(ownerResponse.Headers.CacheControl?.NoStore);
        var ownerBody = await ownerResponse.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Single(ownerBody.GetProperty("assignments").EnumerateArray());
        Assert.Empty(ownerBody.GetProperty("tombstones").EnumerateArray());
        Assert.True(ownerBody.GetProperty("serverRevision").GetInt64() >= assignment.Revision);
        using var otherResponse = await otherClient.GetAsync($"/api/v1/providers/assignments?installationId={owner.InstallationId:D}");
        Assert.Equal(HttpStatusCode.OK, otherResponse.StatusCode);
        var otherBody = await otherResponse.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Empty(otherBody.GetProperty("assignments").EnumerateArray());
        Assert.Empty(otherBody.GetProperty("tombstones").EnumerateArray());
        using var report = await otherClient.PostAsJsonAsync($"/api/v1/providers/assignments/{assignment.Id:D}/status",
            new { profileRevision = profile.Revision, state = "applied" });
        Assert.Equal(HttpStatusCode.NotFound, report.StatusCode);
        Assert.True(report.Headers.CacheControl?.NoStore);
        using var verify = app.Services.CreateScope();
        Assert.Empty(await verify.ServiceProvider.GetRequiredService<IRemoteProviderService>().ListReportsAsync(owner.InstallationId));
        using var admin = await ownerClient.GetAsync("/admin/providers");
        Assert.Equal(HttpStatusCode.Redirect, admin.StatusCode);
        Assert.Contains("/Account/Login", admin.Headers.Location!.OriginalString);
    }

    [Theory]
    [InlineData(RemoteProviderValues.XtreamCodes, "xtream_codes")]
    [InlineData(RemoteProviderValues.M3u, "m3u")]
    [InlineData(RemoteProviderValues.StalkerPortal, "stalker_portal")]
    public async Task Wire_contract_has_flat_typed_configuration_separate_revisions_and_explicit_tombstones(string type, string wireType)
    {
        using var app = new TestApplication();
        using var client = Client(app);
        var input = RemoteProviderTestData.Profile(type);
        RegisterResponse device;
        RemoteProviderProfileMetadata profile;
        RemoteProviderAssignment assignment;
        using (var scope = app.Services.CreateScope())
        {
            device = await scope.ServiceProvider.GetRequiredService<DeviceService>().RegisterAsync(
                new(Guid.NewGuid().ToString("D"), "android", "1.0", "TV", "14"));
            var service = scope.ServiceProvider.GetRequiredService<IRemoteProviderService>();
            profile = await service.CreateProfileAsync(input);
            assignment = await service.AssignAsync(profile.Id, device.InstallationId, new() { Policy = "auto_enabled" });
        }
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", device.BearerToken);
        using var response = await client.GetAsync("/api/v1/providers/assignments");
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        var body = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal(new[] { "assignments", "serverRevision", "tombstones" }, body.EnumerateObject().Select(x => x.Name).Order().ToArray());
        var item = Assert.Single(body.GetProperty("assignments").EnumerateArray());
        Assert.Equal(assignment.Id, item.GetProperty("assignmentId").GetGuid());
        Assert.Equal(profile.Id, item.GetProperty("profileId").GetGuid());
        Assert.Equal(assignment.Revision, item.GetProperty("assignmentRevision").GetInt64());
        Assert.Equal(profile.Revision, item.GetProperty("profileRevision").GetInt64());
        Assert.False(item.TryGetProperty("revision", out _));
        Assert.Equal(wireType, item.GetProperty("type").GetString());
        Assert.Equal("auto_enabled", item.GetProperty("policy").GetString());
        var configuration = item.GetProperty("configuration");
        Assert.Equal(input.Configuration.ServerUrl, configuration.GetProperty("serverUrl").GetString());
        Assert.Equal(input.Configuration.HttpHeaders!["Authorization"], configuration.GetProperty("httpHeaders").GetProperty("Authorization").GetString());
        Assert.False(configuration.TryGetProperty("xtream", out _));
        Assert.False(configuration.TryGetProperty("m3u", out _));
        Assert.False(configuration.TryGetProperty("stalker", out _));
        if (type == RemoteProviderValues.XtreamCodes)
        {
            Assert.Equal("private-xtream-user", configuration.GetProperty("username").GetString());
            Assert.Equal("private-xtream-password", configuration.GetProperty("password").GetString());
            Assert.Equal("background", configuration.GetProperty("epgSyncMode").GetString());
            Assert.True(configuration.GetProperty("fastSyncEnabled").GetBoolean());
            Assert.Equal("category_by_category", configuration.GetProperty("liveSyncMode").GetString());
            Assert.False(configuration.TryGetProperty("vodSyncMode", out _));
        }
        else if (type == RemoteProviderValues.M3u)
        {
            Assert.Equal(input.Configuration.M3u!.M3uUrl, configuration.GetProperty("m3uUrl").GetString());
            Assert.Equal("background", configuration.GetProperty("epgSyncMode").GetString());
            Assert.True(configuration.GetProperty("vodClassificationEnabled").GetBoolean());
            Assert.False(configuration.TryGetProperty("classification", out _));
        }
        else
        {
            Assert.Equal(input.Configuration.Stalker!.PortalUrl, configuration.GetProperty("portalUrl").GetString());
            Assert.Equal("02:11:22:33:44:55", configuration.GetProperty("stalkerMacAddress").GetString());
            Assert.Equal("virtual-profile-secret", configuration.GetProperty("deviceProfile").GetString());
        }
        using var report = await client.PostAsJsonAsync($"/api/v1/providers/assignments/{assignment.Id:D}/status",
            new { profileRevision = item.GetProperty("profileRevision").GetInt64(), state = "active" });
        Assert.Equal(HttpStatusCode.NoContent, report.StatusCode);
        using (var scope = app.Services.CreateScope())
            await scope.ServiceProvider.GetRequiredService<IRemoteProviderService>().RevokeAssignmentAsync(assignment.Id);
        var cursor = body.GetProperty("serverRevision").GetInt64();
        var delta = await client.GetFromJsonAsync<JsonElement>($"/api/v1/providers/assignments?afterRevision={cursor}");
        Assert.Empty(delta.GetProperty("assignments").EnumerateArray());
        var tombstone = Assert.Single(delta.GetProperty("tombstones").EnumerateArray());
        Assert.Equal(assignment.Id, tombstone.GetProperty("assignmentId").GetGuid());
        Assert.True(tombstone.GetProperty("assignmentRevision").GetInt64() > cursor);
        Assert.False(tombstone.TryGetProperty("revision", out _));
        Assert.True(tombstone.TryGetProperty("revokedAt", out var revokedAt));
        Assert.NotEqual(JsonValueKind.Null, revokedAt.ValueKind);
        Assert.EndsWith("Z", revokedAt.GetString());
        Assert.False(tombstone.TryGetProperty("configuration", out _));
    }

    private static HttpClient Client(TestApplication app) => app.CreateClient(new WebApplicationFactoryClientOptions
    { AllowAutoRedirect = false, BaseAddress = new Uri("https://localhost") });

    private static async Task LoginAsync(TestApplication app, HttpClient client)
    {
        using (var scope = app.Services.CreateScope())
        {
            var users = scope.ServiceProvider.GetRequiredService<UserManager<IdentityUser>>();
            var result = await users.CreateAsync(new IdentityUser { UserName = "provider-admin@example.invalid", Email = "provider-admin@example.invalid" }, "Provider-Admin-Password8!");
            Assert.True(result.Succeeded, string.Join(", ", result.Errors.Select(x => x.Description)));
        }
        var token = Token(await client.GetStringAsync("/Account/Login"));
        using var login = await client.PostAsync("/Account/Login", new FormUrlEncodedContent(new Dictionary<string, string>
        {
            ["UserName"] = "provider-admin@example.invalid", ["Password"] = "Provider-Admin-Password8!", ["__RequestVerificationToken"] = token
        }));
        Assert.Equal(HttpStatusCode.Redirect, login.StatusCode);
        using var page = await client.GetAsync("/admin/providers");
        Assert.Equal(HttpStatusCode.OK, page.StatusCode);
    }

    private static string Token(string html)
    {
        var input = Regex.Match(html, "<input[^>]*name=\"__RequestVerificationToken\"[^>]*>").Value;
        var token = WebUtility.HtmlDecode(Regex.Match(input, "value=\"([^\"]+)\"").Groups[1].Value);
        Assert.NotEmpty(token);
        return token;
    }

    private static Dictionary<string, string> Form() => new()
    {
        ["displayName"] = "Private provider", ["type"] = RemoteProviderValues.XtreamCodes,
        ["serverUrl"] = "https://provider.invalid/private-server-secret", ["username"] = "private-form-user",
        ["password"] = "private-form-password", ["epgUrl"] = "https://epg.invalid/?token=private-epg-token",
        ["httpHeaders"] = "Authorization: Bearer private-header-token", ["liveSyncMode"] = "category_by_category",
        ["epgSyncMode"] = "background", ["fastSyncEnabled"] = "true"
    };
}
