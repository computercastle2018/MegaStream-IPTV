using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using MegaStream.Server.Contracts;
using MegaStream.Server.Data;
using MegaStream.Server.Services;
using Microsoft.AspNetCore.DataProtection;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;
using Xunit;

namespace MegaStream.Server.Tests;

public class HttpSecurityTests
{
    [Theory]
    [InlineData("POST", "/api/v1/devices/heartbeat")]
    [InlineData("POST", "/api/v1/devices/subscriptions")]
    [InlineData("POST", "/api/v1/licenses/activate")]
    [InlineData("POST", "/api/v1/diagnostics/batch")]
    [InlineData("POST", "/api/v1/updates/check")]
    [InlineData("POST", "/api/v1/updates/commands/00000000-0000-0000-0000-000000000001/status")]
    [InlineData("GET", "/api/v1/updates/pending")]
    public async Task Anonymous_protected_api_returns_401_problem_without_cookie_redirect(string method, string path)
    {
        using var app = new TestApplication();
        using var client = app.CreateClient(new WebApplicationFactoryClientOptions { AllowAutoRedirect = false, BaseAddress = new Uri("https://localhost") });
        using var request = new HttpRequestMessage(new HttpMethod(method), path);
        if (method == "POST") request.Content = JsonContent.Create(new { });
        var response = await client.SendAsync(request);
        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
        Assert.Null(response.Headers.Location);
        Assert.Equal("application/problem+json", response.Content.Headers.ContentType?.MediaType);
        Assert.True(response.Headers.CacheControl?.NoStore);
        var problem = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal(401, problem.GetProperty("status").GetInt32());
    }

    [Theory]
    [InlineData("/Admin")]
    [InlineData("/admin/updates")]
    public async Task Device_bearer_cannot_access_cookie_only_admin_dashboard(string path)
    {
        using var app = new TestApplication();
        using var client = app.CreateClient(new WebApplicationFactoryClientOptions { AllowAutoRedirect = false, BaseAddress = new Uri("https://localhost") });
        RegisterResponse device;
        using (var scope = app.Services.CreateScope())
            device = await scope.ServiceProvider.GetRequiredService<DeviceService>().RegisterAsync(new RegisterRequest(Guid.NewGuid().ToString("D"), "android", "1.0", "TV", "14"));
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", device.BearerToken);
        var response = await client.GetAsync(path);
        Assert.Equal(HttpStatusCode.Redirect, response.StatusCode);
        Assert.Contains("/Account/Login", response.Headers.Location!.OriginalString);
        Assert.True(response.Headers.CacheControl?.NoStore);
    }

    [Fact]
    public async Task Devices_sharing_an_ip_have_independent_authenticated_rate_budgets()
    {
        using var app = new TestApplication();
        using var client = app.CreateClient(new WebApplicationFactoryClientOptions { AllowAutoRedirect = false, BaseAddress = new Uri("https://localhost") });
        var tokens = await RegisterRateLimitDevices(app);
        foreach (var token in tokens)
        {
            client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", token);
            for (var requestNumber = 1; requestNumber <= 120; requestNumber++)
            {
                using var response = await client.GetAsync("/api/v1/updates/pending");
                // An empty pending-update result can be represented as 204 by MVC's null formatter.
                Assert.True(response.IsSuccessStatusCode, $"Device budget failed at request {requestNumber}: {response.StatusCode}");
            }
            using var limited = await client.GetAsync("/api/v1/updates/pending");
            Assert.Equal(HttpStatusCode.TooManyRequests, limited.StatusCode);
            Assert.Equal("application/problem+json", limited.Content.Headers.ContentType?.MediaType);
            Assert.True(limited.Headers.CacheControl?.NoStore);
        }
    }

    [Fact]
    public async Task Anonymous_registration_keeps_the_ip_budget_when_valid_device_credentials_change()
    {
        using var app = new TestApplication();
        using var client = app.CreateClient(new WebApplicationFactoryClientOptions { AllowAutoRedirect = false, BaseAddress = new Uri("https://localhost") });
        var tokens = await RegisterRateLimitDevices(app);
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", tokens[0]);
        for (var requestNumber = 1; requestNumber <= 120; requestNumber++)
        {
            using var body = new StringContent("{", System.Text.Encoding.UTF8, "application/json");
            using var response = await client.PostAsync("/api/v1/installations/register", body);
            Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        }
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", tokens[1]);
        using var finalBody = new StringContent("{", System.Text.Encoding.UTF8, "application/json");
        using var limited = await client.PostAsync("/api/v1/installations/register", finalBody);
        Assert.Equal(HttpStatusCode.TooManyRequests, limited.StatusCode);
        Assert.True(limited.Headers.CacheControl?.NoStore);
    }

    private static async Task<string[]> RegisterRateLimitDevices(TestApplication app)
    {
        using var scope = app.Services.CreateScope();
        var service = scope.ServiceProvider.GetRequiredService<DeviceService>();
        var tokens = new List<string>();
        for (var index = 0; index < 2; index++)
            tokens.Add((await service.RegisterAsync(new RegisterRequest(Guid.NewGuid().ToString("D"), "android", "1.0", "TV", "14"))).BearerToken);
        return tokens.ToArray();
    }

    internal static async Task LoginAdmin(WebApplicationFactory<Program> app, HttpClient client)
    {
        using (var scope = app.Services.CreateScope())
        {
            var users = scope.ServiceProvider.GetRequiredService<UserManager<IdentityUser>>();
            var created = await users.CreateAsync(new IdentityUser { UserName = "admin@example.invalid", Email = "admin@example.invalid" }, "Admin-Test-Password8!");
            Assert.True(created.Succeeded, string.Join(", ", created.Errors.Select(x => x.Description)));
        }
        var loginPage = await client.GetStringAsync("/Account/Login");
        var input = System.Text.RegularExpressions.Regex.Match(loginPage, "<input[^>]*name=\"__RequestVerificationToken\"[^>]*>").Value;
        var token = WebUtility.HtmlDecode(System.Text.RegularExpressions.Regex.Match(input, "value=\"([^\"]+)\"").Groups[1].Value);
        Assert.NotEmpty(token);
        var login = await client.PostAsync("/Account/Login", new FormUrlEncodedContent(new Dictionary<string, string>
        {
            ["UserName"] = "admin@example.invalid", ["Password"] = "Admin-Test-Password8!", ["__RequestVerificationToken"] = token
        }));
        Assert.Equal(HttpStatusCode.Redirect, login.StatusCode);
    }

    [Fact]
    public async Task Local_subscriptions_are_device_scoped_replaceable_and_render_with_presence_without_secrets()
    {
        using var app = new TestApplication();
        using var client = app.CreateClient(new WebApplicationFactoryClientOptions { AllowAutoRedirect = false, BaseAddress = new Uri("https://localhost") });
        await LoginAdmin(app, client);
        RegisterResponse first, second;
        using (var scope = app.Services.CreateScope())
        {
            var devices = scope.ServiceProvider.GetRequiredService<DeviceService>();
            first = await devices.RegisterAsync(new RegisterRequest(Guid.NewGuid().ToString("D"), "android", "1.0", "TV", "14"));
            second = await devices.RegisterAsync(new RegisterRequest(Guid.NewGuid().ToString("D"), "android", "1.0", "Other TV", "14"));
        }
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", first.BearerToken);
        var subscription = new LocalSubscription { LocalId = 1, Name = "Sports", Type = "xtream_codes", Status = "active", Enabled = true, MaxConnections = 2 };
        Assert.Equal(HttpStatusCode.NoContent, (await client.PostAsJsonAsync("/api/v1/devices/subscriptions", new LocalSubscriptionsRequest { Subscriptions = [subscription] })).StatusCode);
        using (var scope = app.Services.CreateScope())
        {
            var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();
            Assert.Null((await db.Installations.SingleAsync(x => x.Id == second.InstallationId)).LocalSubscriptionsJson);
            var stored = await db.Installations.SingleAsync(x => x.Id == first.InstallationId);
            Assert.Null(stored.LicenseId);
            Assert.NotNull(stored.LocalSubscriptionsReportedAt);
        }
        var html = WebUtility.HtmlDecode(await client.GetStringAsync($"/Admin/DeviceDetails?id={first.InstallationId}"));
        Assert.Contains("Sports", html);
        Assert.Contains("<details class=\"subscription-information\"><summary>إظهار / إخفاء معلومات الاشتراك</summary>", html);
        Assert.DoesNotContain("<details class=\"subscription-information\" open", html);
        Assert.Contains("الاشتراكات الموجودة على الجهاز", html);
        Assert.Contains("badge online", html);
        using (var scope = app.Services.CreateScope())
        {
            var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();
            await db.Installations.Where(x => x.Id == first.InstallationId).ExecuteUpdateAsync(set => set.SetProperty(x => x.LastSeenAt, DateTime.UtcNow.AddDays(-1)));
        }
        html = WebUtility.HtmlDecode(await client.GetStringAsync($"/Admin/DeviceDetails?id={first.InstallationId}"));
        Assert.Contains("badge offline", html);
        Assert.Contains("غير متصل", html);
        subscription.Name = "https://provider.invalid/live/user/password/1";
        Assert.Equal(HttpStatusCode.NoContent, (await client.PostAsJsonAsync("/api/v1/devices/subscriptions", new LocalSubscriptionsRequest { Subscriptions = [subscription] })).StatusCode);
        html = await client.GetStringAsync($"/Admin/DeviceDetails?id={first.InstallationId}");
        Assert.DoesNotContain("provider.invalid", html);
        Assert.Equal(HttpStatusCode.NoContent, (await client.PostAsJsonAsync("/api/v1/devices/subscriptions", new LocalSubscriptionsRequest())).StatusCode);
        html = WebUtility.HtmlDecode(await client.GetStringAsync($"/Admin/DeviceDetails?id={first.InstallationId}"));
        Assert.DoesNotContain("Sports", html);
        Assert.Contains("لا توجد اشتراكات محفوظة", html);
    }

    [Theory]
    [InlineData("secret")]
    [InlineData("null")]
    [InlineData("duplicate")]
    [InlineData("status")]
    [InlineData("overflow")]
    [InlineData("revoked")]
    public async Task Invalid_or_revoked_subscription_reports_cannot_overwrite_inventory(string scenario)
    {
        using var app = new TestApplication();
        using var client = app.CreateClient(new WebApplicationFactoryClientOptions { AllowAutoRedirect = false, BaseAddress = new Uri("https://localhost") });
        RegisterResponse device;
        using (var scope = app.Services.CreateScope())
        {
            device = await scope.ServiceProvider.GetRequiredService<DeviceService>().RegisterAsync(new RegisterRequest(Guid.NewGuid().ToString("D"), "android", "1.0", "TV", "14"));
            if (scenario == "revoked")
                await scope.ServiceProvider.GetRequiredService<AppDbContext>().Installations.Where(x => x.Id == device.InstallationId)
                    .ExecuteUpdateAsync(set => set.SetProperty(x => x.Status, MegaStream.Server.Models.InstallationStatus.Revoked));
        }
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", device.BearerToken);
        var row = new { localId = 1, name = "Sports", type = "m3u", enabled = true, status = scenario == "status" ? "invalid" : "active", expiresAt = (long?)null, maxConnections = 1 };
        object body = scenario switch
        {
            "null" => new { subscriptions = new object?[] { null } },
            "secret" => new { subscriptions = new[] { new { row.localId, row.name, row.type, row.enabled, row.status, row.expiresAt, row.maxConnections, password = "must-not-store" } } },
            "duplicate" => new { subscriptions = new[] { row, row } },
            "overflow" => new { subscriptions = Enumerable.Repeat(row, 101).ToArray() },
            _ => new { subscriptions = new[] { row } }
        };
        var response = await client.PostAsJsonAsync("/api/v1/devices/subscriptions", body);
        Assert.Equal(scenario == "revoked" ? HttpStatusCode.Forbidden : HttpStatusCode.BadRequest, response.StatusCode);
        using var verify = app.Services.CreateScope();
        var stored = await verify.ServiceProvider.GetRequiredService<AppDbContext>().Installations.SingleAsync();
        Assert.Null(stored.LocalSubscriptionsJson);
        Assert.Null(stored.LocalSubscriptionsReportedAt);
    }

    [Theory]
    [InlineData("/Admin")]
    [InlineData("/Admin/Licenses")]
    [InlineData("/Admin/Devices")]
    [InlineData("/Admin/Codes")]
    [InlineData("/Admin/Diagnostics")]
    [InlineData("/admin/updates")]
    [InlineData("/admin/providers")]
    public async Task Authenticated_admin_pages_render_successfully(string path)
    {
        using var app = new TestApplication();
        using var client = app.CreateClient(new WebApplicationFactoryClientOptions { AllowAutoRedirect = false, BaseAddress = new Uri("https://localhost") });
        await LoginAdmin(app, client);
        var response = await client.GetAsync(path);
        Assert.True(response.StatusCode == HttpStatusCode.OK, $"{path} returned {response.StatusCode}: {await response.Content.ReadAsStringAsync()}");
        Assert.Equal("text/html", response.Content.Headers.ContentType?.MediaType);
    }

    [Theory]
    [InlineData("LicenseDetails")]
    [InlineData("DeviceDetails")]
    [InlineData("DiagnosticDetails")]
    public async Task Populated_admin_detail_pages_render_successfully(string action)
    {
        using var app = new TestApplication();
        using var client = app.CreateClient(new WebApplicationFactoryClientOptions { AllowAutoRedirect = false, BaseAddress = new Uri("https://localhost") });
        await LoginAdmin(app, client);
        string id;
        using (var scope = app.Services.CreateScope())
        {
            var admin = scope.ServiceProvider.GetRequiredService<IAdminService>();
            var devices = scope.ServiceProvider.GetRequiredService<DeviceService>();
            var license = await admin.CreateLicenseAsync("Rendered license", DateTime.UtcNow.AddDays(-1), DateTime.UtcNow.AddDays(30), 1);
            var device = await devices.RegisterAsync(new RegisterRequest(Guid.NewGuid().ToString("D"), "android", "1.0", "TV", "14"));
            await devices.ActivateAsync(device.InstallationId, license.FullKey);
            await devices.DiagnosticsAsync(device.InstallationId, new DiagnosticsRequest([new("error", "decoder", "Sample playback failure")]));
            var diagnostic = await scope.ServiceProvider.GetRequiredService<AppDbContext>().DiagnosticEvents.SingleAsync();
            id = action switch { "LicenseDetails" => license.License.Id.ToString(), "DeviceDetails" => device.InstallationId.ToString(), _ => diagnostic.Id.ToString() };
        }
        var response = await client.GetAsync($"/Admin/{action}?id={id}");
        Assert.True(response.StatusCode == HttpStatusCode.OK, $"{action} returned {response.StatusCode}: {await response.Content.ReadAsStringAsync()}");
        Assert.Equal("text/html", response.Content.Headers.ContentType?.MediaType);
    }

    [Fact]
    public async Task Authenticated_admin_post_without_antiforgery_token_cannot_create_license()
    {
        using var app = new TestApplication();
        using var client = app.CreateClient(new WebApplicationFactoryClientOptions { AllowAutoRedirect = false, BaseAddress = new Uri("https://localhost") });
        await LoginAdmin(app, client);
        Assert.Equal(HttpStatusCode.OK, (await client.GetAsync("/Admin")).StatusCode);
        var response = await client.PostAsync("/Admin/CreateLicense", new FormUrlEncodedContent(new Dictionary<string, string>
        {
            ["Label"] = "Should not be created", ["ValidFrom"] = "2030-01-01", ["ValidUntil"] = "2031-01-01", ["MaxInstallations"] = "1"
        }));
        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        using var verify = app.Services.CreateScope();
        Assert.Empty(await verify.ServiceProvider.GetRequiredService<AppDbContext>().Licenses.ToListAsync());
    }

    [Theory]
    [InlineData("/Admin/SetDevicePolicy")]
    [InlineData("/Admin/AssignDeviceLicense")]
    public async Task Authenticated_device_post_without_antiforgery_token_cannot_change_device(string path)
    {
        using var app = new TestApplication();
        using var client = app.CreateClient(new WebApplicationFactoryClientOptions { AllowAutoRedirect = false, BaseAddress = new Uri("https://localhost") });
        await LoginAdmin(app, client);
        Guid installationId;
        using (var scope = app.Services.CreateScope())
            installationId = (await scope.ServiceProvider.GetRequiredService<DeviceService>().RegisterAsync(new RegisterRequest(Guid.NewGuid().ToString("D"), "android", "1.0", "TV", "14"))).InstallationId;
        var response = await client.PostAsync(path, new FormUrlEncodedContent(new Dictionary<string, string>
        {
            ["Id"] = installationId.ToString(), ["KioskMode"] = "always", ["AllowLocalExit"] = "false", ["LicenseId"] = Guid.NewGuid().ToString()
        }));
        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        using var verify = app.Services.CreateScope();
        var db = verify.ServiceProvider.GetRequiredService<AppDbContext>();
        var device = await db.Installations.SingleAsync(x => x.Id == installationId);
        Assert.Equal("off", device.KioskMode);
        Assert.True(device.AllowLocalExit);
        Assert.Null(device.LicenseId);
        Assert.Empty(await db.Set<MegaStream.Server.Models.DevicePolicyAudit>().ToListAsync());
    }

    [Fact]
    public async Task Existing_admin_prevents_changed_seed_environment_creating_another_account()
    {
        using var app = new TestApplication();
        using var scope = app.Services.CreateScope();
        var users = scope.ServiceProvider.GetRequiredService<UserManager<IdentityUser>>();
        var created = await users.CreateAsync(new IdentityUser { UserName = "original@example.invalid", Email = "original@example.invalid" }, "Original-Test-Password9!");
        Assert.True(created.Succeeded, string.Join(", ", created.Errors.Select(x => x.Description)));
        var configuration = new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string, string?>
        {
            ["ADMIN_EMAIL"] = "changed@example.invalid",
            ["ADMIN_PASSWORD"] = "Changed-Test-Password8!"
        }).Build();
        await AdminSeeder.SeedAsync(scope.ServiceProvider, configuration, new TestEnvironment { EnvironmentName = "Production" });
        Assert.Equal("original@example.invalid", (await users.Users.SingleAsync()).Email);
    }

    [Fact]
    public async Task Invalid_registration_response_is_problem_details_and_cannot_be_cached()
    {
        using var app = new TestApplication();
        using var client = app.CreateClient(new WebApplicationFactoryClientOptions { AllowAutoRedirect = false, BaseAddress = new Uri("https://localhost") });
        var response = await client.PostAsJsonAsync("/api/v1/installations/register", new RegisterRequest(Guid.NewGuid().ToString("D"), "android", "1.0", "#EXTM3U", "14"));
        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        Assert.Equal("application/problem+json", response.Content.Headers.ContentType?.MediaType);
        Assert.True(response.Headers.CacheControl?.NoStore);
        var problem = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal(400, problem.GetProperty("status").GetInt32());
    }
}

internal sealed class TestApplication : WebApplicationFactory<Program>
{
    private readonly SqliteConnection connection = new("Data Source=:memory:");
    private readonly string updateStorage = Path.Combine(AppContext.BaseDirectory, "TestResults", $"updates-{Guid.NewGuid():N}");

    private readonly Dictionary<string, string?> settings;

    public TestApplication()
    {
        settings = new Dictionary<string, string?>
        {
            ["Updates:PublicOrigin"] = "https://updates.example.invalid",
            ["DEVICE_SECRET_PEPPER"] = Convert.ToBase64String(System.Security.Cryptography.RandomNumberGenerator.GetBytes(32)),
            ["PROVIDER_SECRET_KEY"] = Convert.ToBase64String(System.Security.Cryptography.RandomNumberGenerator.GetBytes(32)),
            ["PROVIDER_SECRET_KEY_VERSION"] = "1",
            ["DATA_PROTECTION_KEYS_PATH"] = Path.Combine(updateStorage, "data-protection"),
            ["Logging:LogLevel:Default"] = "Warning",
            ["Updates:StoragePath"] = updateStorage
        };
    }

    protected override void ConfigureWebHost(IWebHostBuilder builder)
    {
        builder.UseEnvironment("Testing");
        Directory.CreateDirectory(updateStorage);
        builder.ConfigureAppConfiguration((_, configuration) => configuration.AddInMemoryCollection(settings));
        connection.Open();
        builder.ConfigureServices(services =>
        {
            var retention = services.Where(descriptor => descriptor.ImplementationType == typeof(RetentionService)).ToList();
            foreach (var descriptor in retention) services.Remove(descriptor);
            services.RemoveAll<AppDbContext>();
            services.RemoveAll<DbContextOptions<AppDbContext>>();
            services.AddDbContext<AppDbContext>(options => options.UseSqlite(connection));
            services.RemoveAll<LeaseSigner>();
            services.AddSingleton(_ => new LeaseSigner(LeaseSignerTests.Configuration(), new TestEnvironment()));
            services.AddDataProtection().UseEphemeralDataProtectionProvider();
        });
    }

    protected override Microsoft.Extensions.Hosting.IHost CreateHost(Microsoft.Extensions.Hosting.IHostBuilder builder)
    {
        // Eager secret validation happens before the deferred web-host callbacks in minimal hosting.
        builder.ConfigureHostConfiguration(configuration => configuration.AddInMemoryCollection(settings));
        var host = base.CreateHost(builder);
        using var scope = host.Services.CreateScope();
        scope.ServiceProvider.GetRequiredService<AppDbContext>().Database.EnsureCreated();
        return host;
    }

    protected override void Dispose(bool disposing)
    {
        base.Dispose(disposing);
        if (disposing)
        {
            connection.Dispose();
            if (Directory.Exists(updateStorage)) Directory.Delete(updateStorage, recursive: true);
        }
    }
}
