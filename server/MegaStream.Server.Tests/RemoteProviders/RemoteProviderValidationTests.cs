using MegaStream.Server.RemoteProviders.Core;
using MegaStream.Server.Services;
using Microsoft.EntityFrameworkCore;
using Xunit;
using Session = MegaStream.Server.Tests.RemoteProviders.RemoteProviderServiceTests.Session;

namespace MegaStream.Server.Tests.RemoteProviders;

public sealed class RemoteProviderValidationTests
{
    [Theory]
    [InlineData("url-scheme")]
    [InlineData("url-userinfo")]
    [InlineData("url-fragment")]
    [InlineData("password-too-long")]
    [InlineData("password-control")]
    [InlineData("header-control")]
    [InlineData("user-agent-too-long")]
    [InlineData("display-name-url")]
    [InlineData("display-name-credential")]
    [InlineData("header-too-long")]
    public async Task Invalid_secret_fields_are_rejected_atomically_and_errors_do_not_echo_them(string scenario)
    {
        await using var store = await RemoteProviderDatabase.CreateAsync();
        await using var session = new Session(store);
        var input = RemoteProviderTestData.Profile();
        var badValue = scenario switch
        {
            "url-scheme" => "file:///private-provider-secret",
            "url-userinfo" => "https://private-user:private-password@provider.invalid",
            "url-fragment" => "https://provider.invalid/#private-fragment",
            "password-too-long" => "private-password-" + new string('x', 1025),
            "password-control" => "private-password\r\nInjected: secret",
            "header-control" => "Bearer private-secret\r\nInjected: secret",
            "display-name-url" => "https://provider.invalid/?token=display-secret",
            "display-name-credential" => "password=display-password-secret",
            "header-too-long" => "Bearer " + new string('x', 513),
            _ => "private-agent-" + new string('x', 513)
        };
        if (scenario.StartsWith("url-")) input.Configuration.ServerUrl = badValue;
        else if (scenario.StartsWith("password-")) input.Configuration.Xtream!.Password = badValue;
        else if (scenario.StartsWith("header-")) input.Configuration.HttpHeaders!["Authorization"] = badValue;
        else if (scenario.StartsWith("display-name-")) input.DisplayName = badValue;
        else input.Configuration.HttpUserAgent = badValue;
        var error = await Assert.ThrowsAsync<DomainException>(() => session.Service.CreateProfileAsync(input));
        Assert.Equal(400, error.StatusCode);
        Assert.Equal("invalid_provider_configuration", error.Code);
        Assert.DoesNotContain(badValue, error.ToString(), StringComparison.Ordinal);
        await using var fresh = store.NewContext();
        Assert.Empty(await fresh.Set<RemoteProviderProfile>().ToListAsync());
        Assert.DoesNotContain(badValue, await store.DumpAllPersistedValuesAsync(), StringComparison.Ordinal);
    }

    [Theory]
    [InlineData(RemoteProviderValues.XtreamCodes)]
    [InlineData(RemoteProviderValues.M3u)]
    [InlineData(RemoteProviderValues.StalkerPortal)]
    public async Task Invalid_common_epg_mode_is_rejected_for_every_provider_without_persisting_profile(string type)
    {
        await using var store = await RemoteProviderDatabase.CreateAsync();
        await using var session = new Session(store);
        var input = RemoteProviderTestData.Profile(type);
        input.Configuration.EpgSyncMode = "invalid-mode";
        var error = await Assert.ThrowsAsync<DomainException>(() => session.Service.CreateProfileAsync(input));
        Assert.Equal(400, error.StatusCode);
        Assert.Equal("invalid_provider_configuration", error.Code);
        Assert.DoesNotContain("invalid-mode", error.ToString(), StringComparison.Ordinal);
        foreach (var secret in RemoteProviderTestData.SecretValues(input.Configuration))
            Assert.DoesNotContain(secret, error.ToString(), StringComparison.Ordinal);
        await using var fresh = store.NewContext();
        Assert.Empty(await fresh.Set<RemoteProviderProfile>().ToListAsync());
    }

    [Fact]
    public async Task Raw_provider_error_is_rejected_without_overwriting_last_safe_report()
    {
        await using var store = await RemoteProviderDatabase.CreateAsync();
        var installation = await store.AddInstallationAsync();
        await using var session = new Session(store);
        var profile = await session.Service.CreateProfileAsync(RemoteProviderTestData.Profile());
        var assignment = await session.Service.AssignAsync(profile.Id, installation, new());
        await session.Service.ReportAsync(installation, assignment.Id,
            new() { ProfileRevision = profile.Revision, State = "error", SafeErrorCode = "connection_timeout" });
        const string raw = "https://provider.invalid/live?password=raw-secret-value";
        var error = await Assert.ThrowsAsync<DomainException>(() => session.Service.ReportAsync(installation, assignment.Id,
            new() { ProfileRevision = profile.Revision, State = "error", SafeErrorCode = raw }));
        Assert.Equal(400, error.StatusCode);
        Assert.DoesNotContain(raw, error.ToString(), StringComparison.Ordinal);
        await using var fresh = new Session(store);
        var persisted = Assert.Single(await fresh.Service.ListReportsAsync(installation));
        Assert.Equal("connection_timeout", persisted.SafeErrorCode);
        Assert.DoesNotContain(raw, await store.DumpAllPersistedValuesAsync(), StringComparison.Ordinal);
    }
}
