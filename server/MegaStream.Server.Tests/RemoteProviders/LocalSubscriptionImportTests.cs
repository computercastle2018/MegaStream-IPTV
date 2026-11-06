using System.Net;
using System.Net.Http.Json;
using MegaStream.Server.Contracts;
using MegaStream.Server.Data;
using MegaStream.Server.Models;
using MegaStream.Server.RemoteProviders.Core;
using MegaStream.Server.Services;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Xunit;
using Session = MegaStream.Server.Tests.RemoteProviders.RemoteProviderServiceTests.Session;
using static MegaStream.Server.Tests.RemoteProviders.RemoteProviderHttpTests;

namespace MegaStream.Server.Tests.RemoteProviders;

public sealed class LocalSubscriptionImportTests
{
    [Fact]
    public async Task Admin_imports_actual_latest_report_once_without_overwriting_profiles_assigning_or_changing_license()
    {
        using var app = new TestApplication();
        using var admin = Client(app);
        using var device = Client(app);
        Guid deviceId, unrelatedId, licenseId;
        var original = RemoteProviderTestData.Profile();
        using (var scope = app.Services.CreateScope())
        {
            var registration = await scope.ServiceProvider.GetRequiredService<DeviceService>()
                .RegisterAsync(new(Guid.NewGuid().ToString("D"), "webos", "3.0.7", "LG", "webOS"));
            deviceId = registration.InstallationId;
            await RemoteProviderTestData.LicenseAsync(scope.ServiceProvider, deviceId);
            licenseId = (await scope.ServiceProvider.GetRequiredService<AppDbContext>().Installations.AsNoTracking().SingleAsync()).LicenseId!.Value;
            device.DefaultRequestHeaders.Authorization = new("Bearer", registration.BearerToken);
            unrelatedId = (await scope.ServiceProvider.GetRequiredService<IRemoteProviderService>().CreateProfileAsync(original)).Id;
        }
        var subscriptions = new[]
        {
            Subscription(1, RemoteProviderValues.XtreamCodes, "xtream_codes"),
            Subscription(2, RemoteProviderValues.M3u, "m3u"),
            Subscription(3, RemoteProviderValues.StalkerPortal, "stalker_portal")
        };
        using var report = await device.PostAsJsonAsync("/api/v1/devices/subscriptions", new LocalSubscriptionsRequest { Subscriptions = subscriptions.ToList() });
        Assert.Equal(HttpStatusCode.NoContent, report.StatusCode);
        var pagePath = $"/admin/providers/installations/{deviceId}";
        var importPath = pagePath + "/import-local-subscriptions";
        using var unauthorized = await device.PostAsync(importPath, null);
        Assert.Equal(HttpStatusCode.Redirect, unauthorized.StatusCode);
        await LoginAsync(app, admin);
        using var noCsrf = await admin.PostAsync(importPath, new FormUrlEncodedContent([]));
        Assert.Equal(HttpStatusCode.BadRequest, noCsrf.StatusCode);
        var page = WebUtility.HtmlDecode(await admin.GetStringAsync(pagePath));
        Assert.Contains("حفظ اشتراكات الجهاز في المكتبة", page);
        Assert.Contains(importPath, page);
        Assert.Contains("آخر تقرير محفوظ", page);
        foreach (var expectedCreated in new[] { 0, 0 })
        {
            using var imported = await admin.PostAsync(importPath, Csrf(page));
            Assert.Equal(HttpStatusCode.OK, imported.StatusCode);
            Assert.True(imported.Headers.CacheControl?.NoStore);
            var html = WebUtility.HtmlDecode(await imported.Content.ReadAsStringAsync());
            Assert.Contains($"حُفظ {expectedCreated} اشتراك", html);
            foreach (var subscription in subscriptions)
                foreach (var secret in RemoteProviderTestData.SecretValues(subscription.Credentials!)) Assert.DoesNotContain(secret, html);
        }
        using (var scope = app.Services.CreateScope())
        {
            var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();
            var profiles = await db.Set<RemoteProviderProfile>().AsNoTracking().Where(x => x.SourceInstallationId == deviceId).ToListAsync();
            Assert.Equal(new long?[] { 1, 2, 3 }, profiles.Select(x => x.SourceLocalId).Order().ToArray());
            Assert.Equal(new[] { "Living room (2)", "Living room (3)", "Living room (4)" }, profiles.Select(x => x.DisplayName).Order().ToArray());
            var service = scope.ServiceProvider.GetRequiredService<IRemoteProviderService>();
            foreach (var profile in profiles)
            {
                var configuration = await service.RevealConfigurationAsync(profile.Id);
                Assert.Equal(subscriptions.Single(x => x.LocalId == profile.SourceLocalId).Credentials!.Xtream?.Password, configuration.Xtream?.Password);
                Assert.Equal(subscriptions.Single(x => x.LocalId == profile.SourceLocalId).Credentials!.M3u?.M3uUrl, configuration.M3u?.M3uUrl);
                Assert.Equal(subscriptions.Single(x => x.LocalId == profile.SourceLocalId).Credentials!.Stalker?.PortalUrl, configuration.Stalker?.PortalUrl);
                foreach (var secret in RemoteProviderTestData.SecretValues(configuration))
                    Assert.DoesNotContain(secret, System.Text.Encoding.UTF8.GetString(profile.EncryptedPayload));
            }
            Assert.Equal(original.Configuration.Xtream!.Password, (await service.RevealConfigurationAsync(unrelatedId)).Xtream!.Password);
            var edited = RemoteProviderTestData.Profile();
            edited.Configuration.Xtream!.Password = "admin-edited-secret";
            await service.UpdateProfileAsync(profiles.Single(x => x.SourceLocalId == 1).Id, edited);
            Assert.Empty(await db.Set<RemoteProviderAssignment>().ToListAsync());
            Assert.Equal(licenseId, (await db.Installations.SingleAsync()).LicenseId);
        }
        subscriptions[0].Credentials!.Xtream!.Password = "new-device-secret";
        using var updated = await device.PostAsJsonAsync("/api/v1/devices/subscriptions", new LocalSubscriptionsRequest
        { Subscriptions = [subscriptions[0], Subscription(4, RemoteProviderValues.M3u, "m3u")] });
        Assert.Equal(HttpStatusCode.NoContent, updated.StatusCode);
        using var latest = await admin.PostAsync(importPath, Csrf(page));
        Assert.Equal(HttpStatusCode.OK, latest.StatusCode);
        Assert.Contains("حُفظ 0 اشتراك", WebUtility.HtmlDecode(await latest.Content.ReadAsStringAsync()));
        using var verify = app.Services.CreateScope();
        var database = verify.ServiceProvider.GetRequiredService<AppDbContext>();
        var saved = await database.Set<RemoteProviderProfile>().SingleAsync(x => x.SourceInstallationId == deviceId && x.SourceLocalId == 1);
        Assert.Equal("new-device-secret", (await verify.ServiceProvider.GetRequiredService<IRemoteProviderService>().RevealConfigurationAsync(saved.Id)).Xtream!.Password);
        Assert.Equal(5, await database.Set<RemoteProviderProfile>().CountAsync());
    }

    [Theory]
    [InlineData("missing", "لم يصل تقرير")]
    [InlineData("stale", "آخر تقرير قديم")]
    [InlineData("invalid", "تعذر قراءة آخر تقرير")]
    public async Task Missing_stale_or_invalid_report_shows_safe_admin_message_and_creates_nothing(string scenario, string message)
    {
        using var app = new TestApplication();
        using var admin = Client(app);
        Guid id;
        using (var scope = app.Services.CreateScope())
        {
            var registration = await scope.ServiceProvider.GetRequiredService<DeviceService>()
                .RegisterAsync(new(Guid.NewGuid().ToString("D"), "android", "1.0", "TV", "14"));
            id = registration.InstallationId;
            await RemoteProviderTestData.LicenseAsync(scope.ServiceProvider, id);
            var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();
            var installation = await db.Installations.SingleAsync();
            installation.LocalSubscriptionsJson = scenario == "invalid" ? "{\"Subscriptions\":null,\"Credentials\":[null]}" : "[]";
            installation.LocalSubscriptionsReportedAt = scenario == "missing" ? null : scenario == "stale" ? DateTime.UtcNow.AddDays(-1) : DateTime.UtcNow;
            await db.SaveChangesAsync();
        }
        await LoginAsync(app, admin);
        var path = $"/admin/providers/installations/{id}";
        using var response = await admin.PostAsync(path + "/import-local-subscriptions", Csrf(await admin.GetStringAsync(path)));
        Assert.Equal(HttpStatusCode.Conflict, response.StatusCode);
        Assert.Contains(message, WebUtility.HtmlDecode(await response.Content.ReadAsStringAsync()));
        using var verify = app.Services.CreateScope();
        Assert.Empty(await verify.ServiceProvider.GetRequiredService<AppDbContext>().Set<RemoteProviderProfile>().ToListAsync());
    }

    [Fact]
    public async Task Summary_only_and_unsupported_entries_are_skipped_while_valid_credentials_are_saved()
    {
        await using var store = await RemoteProviderDatabase.CreateAsync();
        var id = await store.AddInstallationAsync();
        await using var session = new Session(store);
        var valid = Subscription(1, RemoteProviderValues.M3u, "m3u");
        var summaryOnly = Subscription(2, RemoteProviderValues.XtreamCodes, "xtream_codes");
        summaryOnly.Credentials = null;
        await Report(session, id, valid, summaryOnly);
        var result = await session.Service.ImportLocalSubscriptionsAsync(id, DateTime.UtcNow.AddMinutes(-10));
        Assert.Equal(1, result.Created); Assert.Equal(1, result.Unavailable);
        Assert.DoesNotContain("m3u-secret-value", await store.DumpAllPersistedValuesAsync());
        var json = await session.Db.Installations.Select(x => x.LocalSubscriptionsJson).SingleAsync();
        await session.Db.Installations.ExecuteUpdateAsync(s => s.SetProperty(x => x.LocalSubscriptionsJson, json!.Replace("\"Type\":\"m3u\"", "\"Type\":\"unsupported\"")));
        var invalid = await session.Service.ImportLocalSubscriptionsAsync(id, DateTime.UtcNow.AddMinutes(-10));
        Assert.Equal(0, invalid.Created); Assert.Equal(2, invalid.Unavailable);
        Assert.Single(await session.Service.ListProfilesAsync());
    }

    [Fact]
    public async Task Concurrent_imports_deduplicate_per_device_local_id_but_do_not_merge_other_devices()
    {
        await using var store = await RemoteProviderDatabase.CreateAsync();
        var first = await store.AddInstallationAsync();
        var second = await store.AddInstallationAsync();
        await using (var setup = new Session(store))
        {
            await Report(setup, first, Subscription(1, RemoteProviderValues.XtreamCodes, "xtream_codes"));
            await Report(setup, second, Subscription(1, RemoteProviderValues.XtreamCodes, "xtream_codes"));
        }
        using var start = new Barrier(2);
        var attempts = Enumerable.Range(0, 2).Select(_ => Task.Run(async () =>
        {
            await using var writer = new Session(store);
            if (!start.SignalAndWait(TimeSpan.FromSeconds(15))) throw new TimeoutException("Import contenders did not start.");
            return await writer.Service.ImportLocalSubscriptionsAsync(first, DateTime.UtcNow.AddMinutes(-10));
        })).ToArray();
        var results = await Task.WhenAll(attempts);
        Assert.Equal(1, results.Sum(x => x.Created)); Assert.Equal(1, results.Sum(x => x.AlreadySaved));
        await using var verify = new Session(store);
        Assert.Equal(1, (await verify.Service.ImportLocalSubscriptionsAsync(second, DateTime.UtcNow.AddMinutes(-10))).Created);
        var savedFirst = await verify.Db.Set<RemoteProviderProfile>().AsNoTracking().SingleAsync(x => x.SourceInstallationId == first);
        await verify.Service.RevokeProfileAsync(savedFirst.Id);
        var replay = await verify.Service.ImportLocalSubscriptionsAsync(first, DateTime.UtcNow.AddMinutes(-10));
        Assert.Equal(0, replay.Created); Assert.Equal(1, replay.AlreadySaved);
        Assert.NotNull((await verify.Service.GetProfileAsync(savedFirst.Id)).DeletedAt);
        Assert.Equal(2, await verify.Db.Set<RemoteProviderProfile>().CountAsync());
        Assert.Empty(await verify.Db.Set<RemoteProviderAssignment>().ToListAsync());
        Assert.All(await verify.Db.Installations.ToListAsync(), x => Assert.NotNull(x.LicenseId));
    }

    private static LocalSubscription Subscription(long id, string type, string localType) => new()
    {
        LocalId = id, Name = "Living room", Type = localType, Status = "active", Enabled = true,
        MaxConnections = 1, Credentials = RemoteProviderTestData.Profile(type).Configuration
    };

    [Theory]
    [InlineData("offline", HttpStatusCode.Conflict)]
    [InlineData("unlicensed", HttpStatusCode.Forbidden)]
    [InlineData("expired", HttpStatusCode.Forbidden)]
    public async Task Reports_from_ineligible_devices_are_retained_but_not_imported_and_manual_action_is_disabled(string scenario, HttpStatusCode expected)
    {
        using var app = new TestApplication();
        using var device = Client(app);
        using var admin = Client(app);
        Guid id;
        using (var scope = app.Services.CreateScope())
        {
            var registration = await scope.ServiceProvider.GetRequiredService<DeviceService>()
                .RegisterAsync(new(Guid.NewGuid().ToString("D"), "webos", "3.0.7", "TV", "webOS"));
            id = registration.InstallationId;
            device.DefaultRequestHeaders.Authorization = new("Bearer", registration.BearerToken);
            if (scenario != "unlicensed") await RemoteProviderTestData.LicenseAsync(scope.ServiceProvider, id);
            var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();
            if (scenario == "offline") await db.Installations.ExecuteUpdateAsync(s => s.SetProperty(x => x.LastSeenAt, DateTime.UtcNow.AddHours(-1)));
            if (scenario == "expired") await db.Licenses.ExecuteUpdateAsync(s => s.SetProperty(x => x.ValidUntil, DateTime.UtcNow.AddSeconds(-1)));
        }
        using var reported = await device.PostAsJsonAsync("/api/v1/devices/subscriptions", new LocalSubscriptionsRequest
        { Subscriptions = [Subscription(1, RemoteProviderValues.XtreamCodes, "xtream_codes")] });
        Assert.Equal(HttpStatusCode.NoContent, reported.StatusCode);
        await LoginAsync(app, admin);
        var path = $"/admin/providers/installations/{id}";
        var page = WebUtility.HtmlDecode(await admin.GetStringAsync(path));
        Assert.Matches("<button[^>]*disabled[^>]*>حفظ اشتراكات الجهاز في المكتبة</button>", page);
        using var manual = await admin.PostAsync(path + "/import-local-subscriptions", Csrf(page));
        Assert.Equal(expected, manual.StatusCode);
        using var scope2 = app.Services.CreateScope();
        var database = scope2.ServiceProvider.GetRequiredService<AppDbContext>();
        Assert.Empty(await database.Set<RemoteProviderProfile>().ToListAsync());
        Assert.NotNull((await database.Installations.SingleAsync()).LocalSubscriptionsReportedAt);
    }

    [Fact]
    public async Task Source_names_are_preserved_and_case_insensitive_collisions_get_next_available_suffix()
    {
        await using var store = await RemoteProviderDatabase.CreateAsync();
        var id = await store.AddInstallationAsync();
        await using var session = new Session(store);
        foreach (var name in new[] { "SPORTS", "Sports (2)" })
        {
            var existing = RemoteProviderTestData.Profile(); existing.DisplayName = name;
            await session.Service.CreateProfileAsync(existing);
        }
        var first = Subscription(1, RemoteProviderValues.XtreamCodes, "xtream_codes"); first.Name = "Sports";
        var second = Subscription(2, RemoteProviderValues.XtreamCodes, "xtream_codes"); second.Name = "sports";
        var original = Subscription(3, RemoteProviderValues.XtreamCodes, "xtream_codes"); original.Name = "My exact TV connection";
        await Report(session, id, first, second, original);
        Assert.Equal(3, (await session.Service.ImportLocalSubscriptionsAsync(id, DateTime.UtcNow.AddMinutes(-10))).Created);
        var profiles = await session.Db.Set<RemoteProviderProfile>().AsNoTracking().Where(x => x.SourceInstallationId == id).OrderBy(x => x.SourceLocalId).ToListAsync();
        Assert.Equal(new[] { "Sports (3)", "sports (4)", "My exact TV connection" }, profiles.Select(x => x.DisplayName).ToArray());
        var retry = await session.Service.ImportLocalSubscriptionsAsync(id, DateTime.UtcNow.AddMinutes(-10));
        Assert.Equal(0, retry.Created); Assert.Equal(0, retry.Updated); Assert.Equal(3, retry.AlreadySaved);
    }

    [Fact]
    public async Task Identical_reports_do_not_rewrite_profiles_but_changed_source_updates_same_profile_and_status()
    {
        await using var store = await RemoteProviderDatabase.CreateAsync();
        var id = await store.AddInstallationAsync();
        await using var session = new Session(store);
        await Report(session, id, Subscription(1, RemoteProviderValues.XtreamCodes, "xtream_codes"));
        await session.Service.ImportLocalSubscriptionsAsync(id, DateTime.UtcNow.AddMinutes(-10));
        var before = await session.Db.Set<RemoteProviderProfile>().AsNoTracking().SingleAsync();
        await Report(session, id, Subscription(1, RemoteProviderValues.XtreamCodes, "xtream_codes"));
        var same = await session.Service.ImportLocalSubscriptionsAsync(id, DateTime.UtcNow.AddMinutes(-10));
        Assert.Equal(1, same.AlreadySaved); Assert.Equal(0, same.Updated);
        var unchanged = await session.Db.Set<RemoteProviderProfile>().AsNoTracking().SingleAsync();
        Assert.Equal(before.EncryptedPayload, unchanged.EncryptedPayload);
        Assert.Equal(before.Revision, unchanged.Revision); Assert.Equal(before.UpdatedAt, unchanged.UpdatedAt);
        var changed = Subscription(1, RemoteProviderValues.XtreamCodes, "xtream_codes");
        changed.Name = "Renamed TV connection"; changed.Status = "expired"; changed.Enabled = false;
        changed.Credentials!.Xtream!.Password = "changed-source-secret";
        await Report(session, id, changed);
        Assert.Equal(1, (await session.Service.ImportLocalSubscriptionsAsync(id, DateTime.UtcNow.AddMinutes(-10))).Updated);
        var after = await session.Db.Set<RemoteProviderProfile>().AsNoTracking().SingleAsync();
        Assert.Equal(before.Id, after.Id); Assert.True(after.Revision > before.Revision);
        Assert.Equal("Renamed TV connection", after.DisplayName); Assert.Equal("expired", after.SourceStatus); Assert.False(after.SourceEnabled);
        Assert.Equal("changed-source-secret", (await session.Service.RevealConfigurationAsync(after.Id)).Xtream!.Password);
        Assert.DoesNotContain("changed-source-secret", await store.DumpAllPersistedValuesAsync());
    }

    private static async Task Report(Session session, Guid id, params LocalSubscription[] subscriptions)
    {
        var json = session.LocalCredentials.Encode(id, subscriptions);
        await session.Db.Installations.Where(x => x.Id == id).ExecuteUpdateAsync(s => s.SetProperty(x => x.LocalSubscriptionsJson, json)
            .SetProperty(x => x.LocalSubscriptionsReportedAt, DateTime.UtcNow));
    }

    private static FormUrlEncodedContent Csrf(string page) => new(new Dictionary<string, string> { ["__RequestVerificationToken"] = Token(page) });

    [Theory]
    [InlineData(RemoteProviderValues.XtreamCodes, "xtream_codes")]
    [InlineData(RemoteProviderValues.M3u, "m3u")]
    [InlineData(RemoteProviderValues.StalkerPortal, "stalker_portal")]
    public async Task Unknown_provider_status_is_visible_on_index_and_local_device_table_without_claiming_active(string type, string localType)
    {
        using var app = new TestApplication();
        using var device = Client(app);
        using var admin = Client(app);
        Guid id;
        using (var scope = app.Services.CreateScope())
        {
            var registration = await scope.ServiceProvider.GetRequiredService<DeviceService>()
                .RegisterAsync(new(Guid.NewGuid().ToString("D"), "macos", "3.0.25", "Mac 8827", "macOS"));
            id = registration.InstallationId;
            await RemoteProviderTestData.LicenseAsync(scope.ServiceProvider, id);
            device.DefaultRequestHeaders.Authorization = new("Bearer", registration.BearerToken);
        }
        var subscription = Subscription(7, type, localType);
        subscription.Name = "Actual reported connection"; subscription.Status = "unknown";
        using var report = await device.PostAsJsonAsync("/api/v1/devices/subscriptions", new LocalSubscriptionsRequest { Subscriptions = [subscription] });
        Assert.Equal(HttpStatusCode.NoContent, report.StatusCode);
        await LoginAsync(app, admin);
        var index = WebUtility.HtmlDecode(await admin.GetStringAsync("/admin/providers"));
        Assert.Contains("Actual reported connection", index);
        Assert.Contains("<td>غير معروف</td>", index);
        var page = WebUtility.HtmlDecode(await admin.GetStringAsync($"/admin/providers/installations/{id}"));
        var localTable = System.Text.RegularExpressions.Regex.Match(page,
            "<table id=\"local-subscription-reports\">[\\s\\S]*?</table>").Value;
        Assert.Contains("Actual reported connection", localTable);
        Assert.Contains("<td>غير معروف</td>", localTable);
        Assert.DoesNotContain("مفعّل</td>", localTable);
        foreach (var secret in RemoteProviderTestData.SecretValues(subscription.Credentials!))
        {
            Assert.DoesNotContain(secret, page); Assert.DoesNotContain(secret, index);
        }
        using var verify = app.Services.CreateScope();
        Assert.Equal("unknown", (await verify.ServiceProvider.GetRequiredService<AppDbContext>().Set<RemoteProviderProfile>().SingleAsync()).SourceStatus);
    }
}
