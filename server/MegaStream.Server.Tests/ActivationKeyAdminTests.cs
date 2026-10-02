using System.Globalization;
using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
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

public sealed class ActivationKeyAdminTests
{
    [Fact]
    public async Task Codes_form_generates_one_time_hashed_key_usable_by_direct_activation_without_replacing_device_requests()
    {
        using var app = new TestApplication();
        using var client = Client(app);
        await HttpSecurityTests.LoginAdmin(app, client);
        RegisterResponse waiting, direct;
        ActivationCodeResponse pending;
        Guid requestId;
        using (var scope = app.Services.CreateScope())
        {
            var devices = scope.ServiceProvider.GetRequiredService<DeviceService>();
            waiting = await devices.RegisterAsync(new(Guid.NewGuid().ToString("D"), "android", "1.0", "Waiting TV", "14"));
            direct = await devices.RegisterAsync(new(Guid.NewGuid().ToString("D"), "android", "1.0", "Direct key TV", "14"));
            pending = await devices.RequestCodeAsync(waiting.InstallationId);
            requestId = (await scope.ServiceProvider.GetRequiredService<AppDbContext>().ActivationCodes.SingleAsync()).Id;
        }
        var page = WebUtility.HtmlDecode(await client.GetStringAsync("/Admin/Codes"));
        Assert.Contains("توليد مفتاح تفعيل", page);
        Assert.Contains("طلبات تفعيل الأجهزة", page);
        Assert.Contains("action=\"/Admin/CreateLicense\"", page);
        Assert.Contains("action=\"/Admin/ApproveCode\"", page);
        Assert.DoesNotContain("/Admin/CreateCode", page);
        var form = Form();
        form["__RequestVerificationToken"] = Token(page);
        using var created = await client.PostAsync("/Admin/CreateLicense", new FormUrlEncodedContent(form));
        Assert.Equal(HttpStatusCode.OK, created.StatusCode);
        Assert.True(created.Headers.CacheControl?.NoStore);
        var html = await created.Content.ReadAsStringAsync();
        var key = Regex.Match(html, "<pre[^>]*class=\"secret\"[^>]*>([^<]+)</pre>").Groups[1].Value;
        Assert.Matches("^[A-F0-9]{8}(-[A-F0-9]{8}){7}$", key);
        Guid licenseId;
        using (var scope = app.Services.CreateScope())
        {
            var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();
            var license = await db.Licenses.SingleAsync();
            licenseId = license.Id;
            var rawKey = Secrets.NormalizeKey(key);
            Assert.True(scope.ServiceProvider.GetRequiredService<SecretHasher>().Matches("license", rawKey, license.KeyHash));
            Assert.DoesNotContain(rawKey, license.KeyHash);
            Assert.Equal(rawKey[^4..], license.KeyLast4);
            Assert.Equal(1, license.MaxInstallations);
            var request = await db.ActivationCodes.SingleAsync();
            Assert.Equal(requestId, request.Id);
            Assert.Equal(ActivationCodeStatus.Pending, request.Status);
            Assert.Null(request.LicenseId);
            Assert.True(await db.Installations.AllAsync(x => x.LicenseId == null));
            var activated = await scope.ServiceProvider.GetRequiredService<DeviceService>().ActivateAsync(direct.InstallationId, key);
            Assert.Equal("active", activated.Status);
            Assert.False(string.IsNullOrEmpty(activated.Lease));
        }
        foreach (var path in new[] { "/Admin/Codes", "/Admin/Licenses", $"/Admin/LicenseDetails?id={licenseId}" })
        {
            var list = await client.GetStringAsync(path);
            Assert.DoesNotContain(key, list);
            Assert.DoesNotContain(Secrets.NormalizeKey(key), list);
        }
        using var deviceClient = Client(app);
        deviceClient.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", direct.BearerToken);
        using var activatedV1 = await deviceClient.PostAsJsonAsync("/api/v1/licenses/activate", new { licenseKey = key });
        Assert.Equal(HttpStatusCode.OK, activatedV1.StatusCode);
        var entitlement = await activatedV1.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("allowed", entitlement.GetProperty("decision").GetProperty("state").GetString());
        page = await client.GetStringAsync("/Admin/Codes");
        using var approved = await client.PostAsync("/Admin/ApproveCode", new FormUrlEncodedContent(new Dictionary<string, string>
        { ["Id"] = requestId.ToString(), ["LicenseId"] = licenseId.ToString(), ["__RequestVerificationToken"] = Token(page) }));
        Assert.Equal(HttpStatusCode.Redirect, approved.StatusCode);
        using (var scope = app.Services.CreateScope())
        {
            var error = await Assert.ThrowsAsync<DomainException>(() => scope.ServiceProvider.GetRequiredService<DeviceService>()
                .PollCodeAsync(waiting.InstallationId, pending.Code, pending.PollToken));
            Assert.Equal("capacity_exceeded", error.Code);
        }
        using var verify = app.Services.CreateScope();
        var finalDb = verify.ServiceProvider.GetRequiredService<AppDbContext>();
        Assert.Equal(licenseId, (await finalDb.Installations.SingleAsync(x => x.Id == direct.InstallationId)).LicenseId);
        Assert.Null((await finalDb.Installations.SingleAsync(x => x.Id == waiting.InstallationId)).LicenseId);
        Assert.Equal(ActivationCodeStatus.Approved, (await finalDb.ActivationCodes.SingleAsync()).Status);
        Assert.Null((await finalDb.ActivationCodes.SingleAsync()).ConsumedAt);
    }

    [Theory]
    [InlineData("anonymous", HttpStatusCode.Redirect)]
    [InlineData("device", HttpStatusCode.Redirect)]
    [InlineData("csrf", HttpStatusCode.BadRequest)]
    public async Task Activation_key_generation_requires_admin_cookie_and_antiforgery(string scenario, HttpStatusCode expected)
    {
        using var app = new TestApplication();
        using var client = Client(app);
        if (scenario == "csrf") await HttpSecurityTests.LoginAdmin(app, client);
        if (scenario == "device")
        {
            using var scope = app.Services.CreateScope();
            var device = await scope.ServiceProvider.GetRequiredService<DeviceService>().RegisterAsync(new(Guid.NewGuid().ToString("D"), "android", "1.0", "TV", "14"));
            client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", device.BearerToken);
        }
        using var response = await client.PostAsync("/Admin/CreateLicense", new FormUrlEncodedContent(Form()));
        Assert.Equal(expected, response.StatusCode);
        using var verify = app.Services.CreateScope();
        Assert.Empty(await verify.ServiceProvider.GetRequiredService<AppDbContext>().Licenses.ToListAsync());
    }

    private static Dictionary<string, string> Form() => new()
    {
        ["Label"] = "Generated activation key",
        ["ValidFrom"] = DateTime.UtcNow.AddDays(-1).ToString("yyyy-MM-ddTHH:mm", CultureInfo.InvariantCulture),
        ["ValidUntil"] = DateTime.UtcNow.AddDays(1).ToString("yyyy-MM-ddTHH:mm", CultureInfo.InvariantCulture),
        ["MaxInstallations"] = "1"
    };
    private static HttpClient Client(TestApplication app) => app.CreateClient(new WebApplicationFactoryClientOptions
    { AllowAutoRedirect = false, BaseAddress = new Uri("https://localhost") });
    private static string Token(string html) => WebUtility.HtmlDecode(Regex.Match(html, "name=\"__RequestVerificationToken\"[^>]*value=\"([^\"]+)\"").Groups[1].Value);
}
