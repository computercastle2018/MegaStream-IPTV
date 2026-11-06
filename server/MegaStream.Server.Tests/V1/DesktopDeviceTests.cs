using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using MegaStream.Server.Data;
using MegaStream.Server.Models;
using MegaStream.Server.Services;
using MegaStream.Server.V1;
using MegaStream.Server.Updates;
using MegaStream.Server.RemoteProviders.Core;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Xunit;

namespace MegaStream.Server.Tests;

public sealed class DesktopDeviceTests
{
    [Theory]
    [InlineData("macos", "uk.megastation.megastream.desktop", "x86_64", "megastream-desktop")]
    [InlineData("windows", "uk.megastation.megastream.desktop", "x86_64", "megastream-desktop")]
    [InlineData("webos", "uk.megastation.megastream", "other", "megastream-webos")]
    public async Task Non_android_registration_upgrade_admin_license_and_remote_subscription_share_existing_device(
        string platform, string packageName, string abi, string audience)
    {
        using var app = new TestApplication();
        using var client = app.CreateClient();
        var registration = new V1RegisterRequest {
            InstallationId = Guid.NewGuid(), Credential = Secrets.RandomToken(), Platform = platform,
            PackageName = packageName, Channel = "stable", AppVersionName = "3.0.16",
            AppVersionCode = 49, Manufacturer = "PC", Model = "MegaStream Desktop", AndroidApi = 0,
            AndroidRelease = "Client OS", Abi = abi, Locale = "en-GB", ManagedDevice = false
        };
        client.DefaultRequestHeaders.Add("Origin", "null");
        client.DefaultRequestHeaders.Add("Idempotency-Key", registration.InstallationId.ToString());
        using var first = await client.PostAsJsonAsync("/api/v1/installations/register", registration);
        Assert.Equal(HttpStatusCode.OK, first.StatusCode);
        Assert.Equal("null", Assert.Single(first.Headers.GetValues("Access-Control-Allow-Origin")));
        var registered = await first.Content.ReadFromJsonAsync<V1RegisterResponse>();
        using var replay = await client.PostAsJsonAsync("/api/v1/installations/register", registration);
        Assert.Equal(HttpStatusCode.OK, replay.StatusCode);
        Assert.Equal(registered, await replay.Content.ReadFromJsonAsync<V1RegisterResponse>());
        using var conflict = await client.PostAsJsonAsync("/api/v1/installations/register", registration with { Credential = Secrets.RandomToken() });
        Assert.Equal(HttpStatusCode.Conflict, conflict.StatusCode);
        client.DefaultRequestHeaders.Authorization = new("Bearer", registration.Credential);
        var heartbeat = new V1HeartbeatRequest {
            AppSessionId = Guid.NewGuid(), Sequence = 0, Mode = "foreground", PackageName = registration.PackageName,
            Channel = "stable", AppVersionName = "3.0.16", AppVersionCode = 49, ManagedDevice = false
        };
        var initial = await (await client.PostAsJsonAsync("/api/v1/devices/heartbeat", heartbeat)).Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("unlicensed", initial.GetProperty("decision").GetProperty("state").GetString());
        using var wrongBuild = await client.PostAsJsonAsync("/api/v1/devices/heartbeat", heartbeat with { PackageName = "com.megastream.app" });
        Assert.Equal(HttpStatusCode.Conflict, wrongBuild.StatusCode);
        Guid licenseId;
        using (var scope = app.Services.CreateScope()) {
            var admin = scope.ServiceProvider.GetRequiredService<IAdminService>();
            licenseId = (await admin.CreateLicenseAsync("Client test", DateTime.UtcNow.AddMinutes(-1), DateTime.UtcNow.AddYears(1), 1)).License.Id;
            await admin.AssignInstallationLicenseAsync(registration.InstallationId, licenseId);
            var providers = scope.ServiceProvider.GetRequiredService<IRemoteProviderService>();
            var profile = await providers.CreateProfileAsync(new ReplaceRemoteProviderProfile {
                DisplayName = "Desktop subscription", Type = RemoteProviderValues.M3u,
                Configuration = new RemoteProviderConfiguration { M3u = new M3uConfiguration { M3uUrl = "https://example.com/legal.m3u" } }
            });
            await providers.AssignAsync(profile.Id, registration.InstallationId, new AssignRemoteProvider { Policy = "required" });
        }
        var licensed = await (await client.PostAsJsonAsync("/api/v1/devices/heartbeat", heartbeat with { Sequence = 1 })).Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("allowed", licensed.GetProperty("decision").GetProperty("state").GetString());
        var claims = LeaseSignerTests.Payload(licensed.GetProperty("lease").GetString()!);
        Assert.Equal(audience, claims.GetProperty("aud").GetString());
        Assert.Equal(registration.InstallationId, claims.GetProperty("sub").GetGuid());
        Assert.Equal(licenseId, claims.GetProperty("lid").GetGuid());
        Assert.Equal(SecretHasher.CredentialBinding(registration.Credential), claims.GetProperty("credentialBinding").GetString());
        Assert.False(licensed.TryGetProperty("updateCommand", out _));
        var assignments = await client.GetFromJsonAsync<JsonElement>("/api/v1/providers/assignments");
        Assert.Single(assignments.GetProperty("assignments").EnumerateArray());
        Assert.Equal("https://example.com/legal.m3u", assignments.GetProperty("assignments")[0].GetProperty("configuration").GetProperty("m3uUrl").GetString());
        Assert.Equal(HttpStatusCode.OK, (await client.PostAsJsonAsync("/api/v1/installations/register", registration with { AppVersionCode = 50, AppVersionName = "3.0.17" })).StatusCode);
        Assert.Equal(HttpStatusCode.OK, (await client.PostAsJsonAsync("/api/v1/devices/heartbeat", heartbeat with { Sequence = 2, AppVersionCode = 50, AppVersionName = "3.0.17" })).StatusCode);
        using (var scope = app.Services.CreateScope()) {
            var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();
            Assert.Empty(await db.Set<DeviceUpdateState>().ToListAsync());
            var device = Assert.Single(await db.Installations.ToListAsync());
            Assert.Equal(platform, device.Platform); Assert.Equal(licenseId, device.LicenseId); Assert.Equal("3.0.17", device.AppVersion);
            (await db.Licenses.SingleAsync()).Status = LicenseStatus.Revoked;
            await db.SaveChangesAsync();
        }
        var revoked = await (await client.PostAsJsonAsync("/api/v1/devices/heartbeat", heartbeat with { Sequence = 3 })).Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("revoked", revoked.GetProperty("decision").GetProperty("state").GetString());
        Assert.False(revoked.TryGetProperty("lease", out _));
    }

    [Theory]
    [InlineData("uk.megastation.megastream", "android", 0, "other", "stable")]
    [InlineData("uk.megastation.megastream", "macos", 0, "other", "stable")]
    [InlineData("uk.megastation.megastream", "windows", 0, "other", "stable")]
    [InlineData("uk.megastation.megastream", null, 0, "other", "stable")]
    [InlineData("uk.megastation.megastream", "webos", 27, "other", "stable")]
    [InlineData("uk.megastation.megastream", "webos", -1, "other", "stable")]
    [InlineData("uk.megastation.megastream", "webos", 0, "x86_64", "stable")]
    [InlineData("uk.megastation.megastream", "webos", 0, "other", "beta")]
    [InlineData("uk.megastation.megastream.desktop", "webos", 0, "other", "stable")]
    [InlineData("uk.megastation.megastream.desktop", "android", 27, "other", "stable")]
    [InlineData("uk.megastation.megastream.desktop", "macos", 27, "other", "stable")]
    [InlineData("com.megastream.app", "webos", 0, "other", "stable")]
    [InlineData("com.megastream.app.beta", "webos", 0, "other", "beta")]
    [InlineData("com.megastream.app", "android", 0, "other", "stable")]
    [InlineData("com.megastream.app", "android", 26, "other", "stable")]
    [InlineData("com.megastream.app", "android", 27, "arm64", "stable")]
    public async Task Registration_rejects_mismatched_platform_build_and_metadata(
        string packageName, string? platform, int api, string abi, string channel)
    {
        using var app = new TestApplication();
        using var client = app.CreateClient();
        var request = new V1RegisterRequest {
            InstallationId = Guid.NewGuid(), Credential = Secrets.RandomToken(), Platform = platform,
            PackageName = packageName, Channel = channel, AppVersionName = "3.0.16", AppVersionCode = 49,
            Manufacturer = "LG", Model = "TV", AndroidApi = api, AndroidRelease = "webOS",
            Abi = abi, Locale = "en-GB", ManagedDevice = false
        };
        client.DefaultRequestHeaders.Add("Idempotency-Key", request.InstallationId.ToString());
        using var response = await client.PostAsJsonAsync("/api/v1/installations/register", request);
        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        using var scope = app.Services.CreateScope();
        Assert.Empty(await scope.ServiceProvider.GetRequiredService<AppDbContext>().Installations.ToListAsync());
    }
}
