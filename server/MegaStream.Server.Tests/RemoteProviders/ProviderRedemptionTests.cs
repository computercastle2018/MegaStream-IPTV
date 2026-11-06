using MegaStream.Server.Models;
using MegaStream.Server.RemoteProviders.Core;
using MegaStream.Server.Services;
using Microsoft.EntityFrameworkCore;
using Xunit;
using Session = MegaStream.Server.Tests.RemoteProviders.RemoteProviderServiceTests.Session;

namespace MegaStream.Server.Tests.RemoteProviders;

public sealed class ProviderRedemptionTests
{
    [Fact]
    public async Task Code_is_hash_only_and_normalized_replay_does_not_restore_admin_revocation()
    {
        await using var store = await RemoteProviderDatabase.CreateAsync();
        var device = await store.AddInstallationAsync();
        await using var session = new Session(store);
        var profile = await session.Service.CreateProfileAsync(RemoteProviderTestData.Profile());
        var generated = await session.Service.CreateRedemptionCodeAsync(profile.Id);
        Assert.Matches("^[0-9A-F]{24}$", generated.Code);
        Assert.InRange(generated.ExpiresAt, DateTime.UtcNow.AddHours(23), DateTime.UtcNow.AddHours(25));
        var row = await session.Db.Set<ProviderRedemptionCode>().AsNoTracking().SingleAsync();
        Assert.Equal(profile.Id, row.ProfileId);
        Assert.Equal(64, row.CodeHash.Length);
        Assert.DoesNotContain(generated.Code, await store.DumpAllPersistedValuesAsync());
        await session.Service.RedeemAsync(device, "  " + generated.Code.ToLowerInvariant() + "  ");
        var result = await session.Service.GetDeviceAsync(device);
        var assignment = Assert.Single(result.Items);
        Assert.Equal(profile.Id, assignment.ProfileId);
        Assert.Equal("auto_enabled", assignment.Policy);
        Assert.NotNull(assignment.Configuration);
        await session.Service.RedeemAsync(device, generated.Code);
        Assert.Equal(result.Revision, (await session.Service.GetDeviceAsync(device)).Revision);
        await session.Service.RevokeAssignmentAsync(assignment.AssignmentId);
        await session.Service.RedeemAsync(device, generated.Code);
        Assert.True(Assert.Single((await session.Service.GetDeviceAsync(device)).Items).Revoked);
        row = await session.Db.Set<ProviderRedemptionCode>().AsNoTracking().SingleAsync();
        Assert.Equal(device, row.RedeemedInstallationId);
        Assert.NotNull(row.RedeemedAt);
        Assert.DoesNotContain(generated.Code, await store.DumpAllPersistedValuesAsync());
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task Independent_database_connections_consume_code_once_with_same_device_retry(bool sameDevice)
    {
        await using var store = await RemoteProviderDatabase.CreateAsync();
        var first = await store.AddInstallationAsync();
        var second = sameDevice ? first : await store.AddInstallationAsync();
        GeneratedProviderCode generated;
        await using (var setup = new Session(store))
        {
            var profile = await setup.Service.CreateProfileAsync(RemoteProviderTestData.Profile());
            generated = await setup.Service.CreateRedemptionCodeAsync(profile.Id);
        }
        using var start = new Barrier(2);
        var attempts = new[] { first, second }.Select(id => Task.Run(async () =>
        {
            await using var writer = new Session(store);
            if (!start.SignalAndWait(TimeSpan.FromSeconds(15))) throw new TimeoutException("Redemption contenders did not start.");
            try { await writer.Service.RedeemAsync(id, generated.Code); return (Id: id, Error: (DomainException?)null); }
            catch (DomainException error) { return (Id: id, Error: error); }
        })).ToArray();
        var outcomes = await Task.WhenAll(attempts);
        if (sameDevice) Assert.All(outcomes, x => Assert.Null(x.Error));
        else
        {
            Assert.Single(outcomes, x => x.Error is null);
            Assert.Equal("invalid_subscription_code", Assert.Single(outcomes, x => x.Error is not null).Error!.Code);
        }
        await using var verify = new Session(store);
        var code = await verify.Db.Set<ProviderRedemptionCode>().SingleAsync();
        var assignment = await verify.Db.Set<RemoteProviderAssignment>().SingleAsync();
        Assert.Equal(assignment.InstallationId, code.RedeemedInstallationId);
        Assert.NotNull(code.RedeemedAt);
        Assert.Contains(outcomes, x => x.Id == assignment.InstallationId && x.Error is null);
    }

    [Theory]
    [InlineData("expired")]
    [InlineData("unknown")]
    [InlineData("wrong-device")]
    [InlineData("deleted-profile")]
    [InlineData("malformed")]
    public async Task Unavailable_codes_have_same_generic_error_without_assigning_or_consuming(string scenario)
    {
        await using var store = await RemoteProviderDatabase.CreateAsync();
        var device = await store.AddInstallationAsync();
        await using var session = new Session(store);
        var profile = await session.Service.CreateProfileAsync(RemoteProviderTestData.Profile());
        var generated = await session.Service.CreateRedemptionCodeAsync(profile.Id);
        var input = generated.Code;
        if (scenario == "expired")
            await session.Db.Set<ProviderRedemptionCode>().ExecuteUpdateAsync(s => s.SetProperty(x => x.ExpiresAt, DateTime.UtcNow.AddMinutes(-1)));
        if (scenario == "deleted-profile") await session.Service.RevokeProfileAsync(profile.Id);
        if (scenario == "unknown") input = new string('0', 24);
        if (scenario == "malformed") input = "https://private.invalid/secret";
        if (scenario == "wrong-device")
            await session.Service.RedeemAsync(await store.AddInstallationAsync(), input);
        var error = await Assert.ThrowsAsync<DomainException>(() => session.Service.RedeemAsync(device, input));
        Assert.Equal(400, error.StatusCode);
        Assert.Equal("invalid_subscription_code", error.Code);
        Assert.Equal("Subscription code is invalid or unavailable.", error.Message);
        Assert.Empty(await session.Service.ListAssignmentsAsync(device));
        var row = await session.Db.Set<ProviderRedemptionCode>().AsNoTracking().SingleAsync();
        Assert.NotEqual(device, row.RedeemedInstallationId);
        if (scenario != "wrong-device") Assert.Null(row.RedeemedAt);
    }

    [Theory]
    [InlineData("unlicensed")]
    [InlineData("expired")]
    [InlineData("not-started")]
    [InlineData("suspended")]
    [InlineData("revoked")]
    [InlineData("disabled-device")]
    public async Task Invalid_license_or_device_cannot_redeem_or_download_existing_subscription(string scenario)
    {
        await using var store = await RemoteProviderDatabase.CreateAsync();
        var device = await store.AddInstallationAsync(scenario != "unlicensed");
        await using var session = new Session(store);
        var profile = await session.Service.CreateProfileAsync(RemoteProviderTestData.Profile());
        await session.Service.AssignAsync(profile.Id, device, new());
        var generated = await session.Service.CreateRedemptionCodeAsync(profile.Id);
        var installation = await session.Db.Installations.Include(x => x.License).SingleAsync();
        if (scenario == "disabled-device") installation.Status = InstallationStatus.Revoked;
        if (scenario == "expired") installation.License!.ValidUntil = DateTime.UtcNow.AddSeconds(-1);
        if (scenario == "not-started") installation.License!.ValidFrom = DateTime.UtcNow.AddDays(1);
        if (scenario == "suspended") installation.License!.Status = LicenseStatus.Suspended;
        if (scenario == "revoked") installation.License!.Status = LicenseStatus.Revoked;
        await session.Db.SaveChangesAsync();
        Assert.Equal(403, (await Assert.ThrowsAsync<DomainException>(() => session.Service.RedeemAsync(device, generated.Code))).StatusCode);
        Assert.Equal(403, (await Assert.ThrowsAsync<DomainException>(() => session.Service.GetDeviceAsync(device))).StatusCode);
        var row = await session.Db.Set<ProviderRedemptionCode>().AsNoTracking().SingleAsync();
        Assert.Null(row.RedeemedAt);
        Assert.Null(row.RedeemedInstallationId);
    }

    [Fact]
    public async Task Only_existing_active_profiles_can_generate_codes()
    {
        await using var store = await RemoteProviderDatabase.CreateAsync();
        await using var session = new Session(store);
        Assert.Equal(404, (await Assert.ThrowsAsync<DomainException>(() => session.Service.CreateRedemptionCodeAsync(Guid.NewGuid()))).StatusCode);
        var profile = await session.Service.CreateProfileAsync(RemoteProviderTestData.Profile());
        await session.Service.RevokeProfileAsync(profile.Id);
        Assert.Equal(409, (await Assert.ThrowsAsync<DomainException>(() => session.Service.CreateRedemptionCodeAsync(profile.Id))).StatusCode);
        Assert.Empty(await session.Db.Set<ProviderRedemptionCode>().ToListAsync());
    }
}
