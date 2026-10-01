using System.Text.Json;
using MegaStream.Server.Data;
using MegaStream.Server.Models;
using MegaStream.Server.RemoteProviders;
using MegaStream.Server.RemoteProviders.Core;
using MegaStream.Server.Services;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Xunit;

namespace MegaStream.Server.Tests.RemoteProviders;

public sealed class RemoteProviderServiceTests
{
    [Fact]
    public async Task Creating_for_revoked_device_leaves_no_profile_or_assignment()
    {
        await using var store = await RemoteProviderDatabase.CreateAsync();
        var installation = await store.AddInstallationAsync();
        await using var session = new Session(store);
        var device = await session.Db.Installations.SingleAsync(x => x.Id == installation);
        device.Status = InstallationStatus.Revoked;
        await session.Db.SaveChangesAsync();
        var error = await Assert.ThrowsAsync<DomainException>(() => session.Service.CreateProfileAsync(
            RemoteProviderTestData.Profile(), installationId: installation));
        Assert.Equal(403, error.StatusCode);
        Assert.Empty(await session.Service.ListProfilesAsync());
        Assert.Empty(await session.Db.Set<RemoteProviderAssignment>().ToListAsync());
    }

    [Theory]
    [InlineData(RemoteProviderValues.XtreamCodes)]
    [InlineData(RemoteProviderValues.M3u)]
    [InlineData(RemoteProviderValues.StalkerPortal)]
    public async Task Typed_credentials_round_trip_but_never_appear_in_database_dump(string type)
    {
        await using var store = await RemoteProviderDatabase.CreateAsync();
        var installation = await store.AddInstallationAsync();
        var request = RemoteProviderTestData.Profile(type);
        Guid profileId;
        await using (var session = new Session(store))
        {
            var profile = await session.Service.CreateProfileAsync(request);
            profileId = profile.Id;
            await session.Service.AssignAsync(profileId, installation, new());
        }
        await using (var fresh = new Session(store))
        {
            var item = Assert.Single((await fresh.Service.GetDeviceAsync(installation)).Items);
            Assert.Equal(profileId, item.ProfileId);
            Assert.Equal(type, item.Type);
            Assert.Equal(JsonSerializer.Serialize(request.Configuration), JsonSerializer.Serialize(item.Configuration));
            var metadata = JsonSerializer.Serialize(await fresh.Service.GetProfileAsync(profileId));
            var dump = await store.DumpAllPersistedValuesAsync();
            foreach (var secret in RemoteProviderTestData.SecretValues(request.Configuration))
            {
                Assert.DoesNotContain(secret, dump, StringComparison.Ordinal);
                Assert.DoesNotContain(secret, metadata, StringComparison.Ordinal);
            }
        }
    }

    [Fact]
    public async Task Get_and_report_are_scoped_to_authenticated_installation()
    {
        await using var store = await RemoteProviderDatabase.CreateAsync();
        var owner = await store.AddInstallationAsync();
        var other = await store.AddInstallationAsync();
        await using var session = new Session(store);
        var profile = await session.Service.CreateProfileAsync(RemoteProviderTestData.Profile());
        var assignment = await session.Service.AssignAsync(profile.Id, owner, new());
        Assert.Single((await session.Service.GetDeviceAsync(owner)).Items);
        Assert.Empty((await session.Service.GetDeviceAsync(other)).Items);
        var error = await Assert.ThrowsAsync<DomainException>(() => session.Service.ReportAsync(other, assignment.Id,
            new() { ProfileRevision = profile.Revision, State = "applied" }));
        Assert.Equal(404, error.StatusCode);
        Assert.Empty(await session.Service.ListReportsAsync(owner));
        Assert.Empty(await session.Service.ListReportsAsync(other));
        await session.Service.ReportAsync(owner, assignment.Id, new() { ProfileRevision = profile.Revision, State = "applied" });
        Assert.Single(await session.Service.ListReportsAsync(owner));
        Assert.Empty(await session.Service.ListReportsAsync(other));
    }

    [Fact]
    public async Task Revoked_installation_cannot_fetch_or_report_even_with_assignment()
    {
        await using var store = await RemoteProviderDatabase.CreateAsync();
        var installation = await store.AddInstallationAsync();
        Guid assignmentId;
        long revision;
        await using (var setup = new Session(store))
        {
            var profile = await setup.Service.CreateProfileAsync(RemoteProviderTestData.Profile());
            revision = profile.Revision;
            assignmentId = (await setup.Service.AssignAsync(profile.Id, installation, new())).Id;
            var device = await setup.Db.Installations.SingleAsync(x => x.Id == installation);
            device.Status = InstallationStatus.Revoked;
            await setup.Db.SaveChangesAsync();
        }
        await using var fresh = new Session(store);
        Assert.Equal(403, (await Assert.ThrowsAsync<DomainException>(() => fresh.Service.GetDeviceAsync(installation))).StatusCode);
        Assert.Equal(403, (await Assert.ThrowsAsync<DomainException>(() => fresh.Service.ReportAsync(installation, assignmentId,
            new() { ProfileRevision = revision, State = "applied" }))).StatusCode);
        Assert.Empty(await fresh.Db.Set<ProviderAssignmentReport>().ToListAsync());
    }

    [Fact]
    public async Task Profile_edit_advances_each_assignment_and_incremental_fetch_returns_new_credentials()
    {
        await using var store = await RemoteProviderDatabase.CreateAsync();
        var first = await store.AddInstallationAsync();
        var second = await store.AddInstallationAsync();
        await using var session = new Session(store);
        var profile = await session.Service.CreateProfileAsync(RemoteProviderTestData.Profile());
        var firstAssignment = await session.Service.AssignAsync(profile.Id, first, new());
        var secondAssignment = await session.Service.AssignAsync(profile.Id, second, new());
        var beforeFirst = await session.Service.GetDeviceAsync(first);
        var beforeSecond = await session.Service.GetDeviceAsync(second);
        var replacement = RemoteProviderTestData.Profile();
        replacement.Configuration.Xtream!.Password = "updated-password-secret";
        var updated = await session.Service.UpdateProfileAsync(profile.Id, replacement);
        Assert.True(updated.Revision > profile.Revision);
        foreach (var (installation, cursor, assignment) in new[]
        {
            (first, beforeFirst.Revision, firstAssignment.Id), (second, beforeSecond.Revision, secondAssignment.Id)
        })
        {
            await using var fresh = new Session(store);
            var delta = await fresh.Service.GetDeviceAsync(installation, cursor);
            var item = Assert.Single(delta.Items);
            Assert.Equal(assignment, item.AssignmentId);
            Assert.True(item.Revision > cursor);
            Assert.Equal(updated.Revision, item.ProfileRevision);
            Assert.Equal("updated-password-secret", item.Configuration!.Xtream!.Password);
            Assert.Empty((await fresh.Service.GetDeviceAsync(installation, delta.Revision)).Items);
        }
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task Revocation_is_an_explicit_replayable_tombstone_without_credentials(bool revokeProfile)
    {
        await using var store = await RemoteProviderDatabase.CreateAsync();
        var installation = await store.AddInstallationAsync();
        await using var session = new Session(store);
        var profile = await session.Service.CreateProfileAsync(RemoteProviderTestData.Profile());
        var assignment = await session.Service.AssignAsync(profile.Id, installation, new());
        var cursor = (await session.Service.GetDeviceAsync(installation)).Revision;
        if (revokeProfile) await session.Service.RevokeProfileAsync(profile.Id);
        else await session.Service.RevokeAssignmentAsync(assignment.Id);
        await using var fresh = new Session(store);
        var delta = await fresh.Service.GetDeviceAsync(installation, cursor);
        var tombstone = Assert.Single(delta.Items);
        Assert.Equal(assignment.Id, tombstone.AssignmentId);
        Assert.True(tombstone.Revoked);
        Assert.False(tombstone.Enabled);
        Assert.Null(tombstone.Configuration);
        Assert.True(tombstone.Revision > cursor);
        Assert.Equal(JsonSerializer.Serialize(delta), JsonSerializer.Serialize(await fresh.Service.GetDeviceAsync(installation, cursor)));
        Assert.Empty((await fresh.Service.GetDeviceAsync(installation, delta.Revision)).Items);
    }

    [Fact]
    public async Task Duplicate_report_is_one_persisted_row_and_stale_profile_report_does_not_overwrite_it()
    {
        await using var store = await RemoteProviderDatabase.CreateAsync();
        var installation = await store.AddInstallationAsync();
        await using var session = new Session(store);
        var profile = await session.Service.CreateProfileAsync(RemoteProviderTestData.Profile());
        var assignment = await session.Service.AssignAsync(profile.Id, installation, new());
        var report = new ReportRemoteProvider { ProfileRevision = profile.Revision, State = "applied", ProviderReportedMaxConnections = 2 };
        await session.Service.ReportAsync(installation, assignment.Id, report);
        var firstReportedAt = Assert.Single(await session.Service.ListReportsAsync(installation)).LastReportedAt;
        await using (var retry = new Session(store))
        {
            await retry.Service.ReportAsync(installation, assignment.Id, report);
            Assert.Equal(firstReportedAt, Assert.Single(await retry.Service.ListReportsAsync(installation)).LastReportedAt);
        }
        Assert.Single(await session.Service.ListReportsAsync(installation));
        var updated = await session.Service.UpdateProfileAsync(profile.Id, RemoteProviderTestData.Profile());
        var current = new ReportRemoteProvider { ProfileRevision = updated.Revision, State = "active", ProviderReportedMaxConnections = 3 };
        await session.Service.ReportAsync(installation, assignment.Id, current);
        var error = await Assert.ThrowsAsync<DomainException>(() => session.Service.ReportAsync(installation, assignment.Id, report));
        Assert.Equal(409, error.StatusCode);
        await using var fresh = new Session(store);
        var persisted = Assert.Single(await fresh.Service.ListReportsAsync(installation));
        Assert.Equal(updated.Revision, persisted.ProfileRevision);
        Assert.Equal("active", persisted.State);
        Assert.Equal(3, persisted.ProviderReportedMaxConnections);
        Assert.Single(await fresh.Db.Set<ProviderAssignmentReport>().ToListAsync());
    }

    [Fact]
    public async Task Administratively_disabled_assignment_withholds_credentials_until_reenabled_with_new_revision()
    {
        await using var store = await RemoteProviderDatabase.CreateAsync();
        var installation = await store.AddInstallationAsync();
        await using var session = new Session(store);
        var profile = await session.Service.CreateProfileAsync(RemoteProviderTestData.Profile());
        var assignment = await session.Service.AssignAsync(profile.Id, installation, new() { Enabled = false });
        var disabled = await session.Service.GetDeviceAsync(installation);
        var item = Assert.Single(disabled.Items);
        Assert.False(item.Enabled);
        Assert.Null(item.Configuration);
        Assert.Equal(409, (await Assert.ThrowsAsync<DomainException>(() => session.Service.ReportAsync(installation, assignment.Id,
            new() { ProfileRevision = profile.Revision, State = "active" }))).StatusCode);
        await session.Service.AssignAsync(profile.Id, installation, new() { Enabled = true });
        await using var fresh = new Session(store);
        var restored = Assert.Single((await fresh.Service.GetDeviceAsync(installation, disabled.Revision)).Items);
        Assert.Equal(assignment.Id, restored.AssignmentId);
        Assert.True(restored.Enabled);
        Assert.False(restored.Revoked);
        Assert.True(restored.Revision > disabled.Revision);
        Assert.Equal("private-xtream-password", restored.Configuration!.Xtream!.Password);
    }

    [Fact]
    public async Task Required_assignment_cannot_be_reported_disabled_by_user()
    {
        await using var store = await RemoteProviderDatabase.CreateAsync();
        var installation = await store.AddInstallationAsync();
        await using var session = new Session(store);
        var profile = await session.Service.CreateProfileAsync(RemoteProviderTestData.Profile());
        var assignment = await session.Service.AssignAsync(profile.Id, installation, new() { Policy = "required" });
        var error = await Assert.ThrowsAsync<DomainException>(() => session.Service.ReportAsync(installation, assignment.Id,
            new() { ProfileRevision = profile.Revision, State = "disabled_by_user" }));
        Assert.Equal(409, error.StatusCode);
        Assert.Equal("provider_required", error.Code);
        await using var fresh = new Session(store);
        Assert.Empty(await fresh.Service.ListReportsAsync(installation));
        var item = Assert.Single((await fresh.Service.GetDeviceAsync(installation)).Items);
        Assert.True(item.Enabled);
        Assert.Equal("required", item.Policy);
    }

    internal sealed class Session : IAsyncDisposable
    {
        private readonly ServiceProvider provider;
        public AppDbContext Db { get; }
        public IRemoteProviderService Service { get; }
        public Session(RemoteProviderDatabase store, byte[]? key = null)
        {
            Db = store.NewContext();
            var config = new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string, string?>
            {
                ["PROVIDER_SECRET_KEY"] = Convert.ToBase64String(key ?? Enumerable.Range(1, 32).Select(x => (byte)x).ToArray()),
                ["PROVIDER_SECRET_KEY_VERSION"] = "1"
            }).Build();
            var services = new ServiceCollection();
            services.AddSingleton(Db);
            services.AddLogging();
            services.AddRemoteProviders(config);
            provider = services.BuildServiceProvider();
            Service = provider.GetRequiredService<IRemoteProviderService>();
        }
        public async ValueTask DisposeAsync()
        {
            await provider.DisposeAsync();
            await Db.DisposeAsync();
        }
    }
}
