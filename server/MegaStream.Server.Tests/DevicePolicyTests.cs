using MegaStream.Server.Models;
using MegaStream.Server.Services;
using Microsoft.EntityFrameworkCore;
using Xunit;

namespace MegaStream.Server.Tests;

public class DevicePolicyTests
{
    private static async Task<Guid> AddDevice(TestDatabase store, string mode = "off", bool allowExit = true)
    {
        var device = new Installation { TokenHash = Guid.NewGuid().ToString("N"), KioskMode = mode, AllowLocalExit = allowExit };
        store.Db.Installations.Add(device);
        await store.Db.SaveChangesAsync();
        return device.Id;
    }

    [Theory]
    [InlineData("unknown", "administrator")]
    [InlineData("always", "")]
    [InlineData("always", "   ")]
    [InlineData("always", null)]
    [InlineData("always", "https://administrator.invalid/private")]
    public async Task Invalid_policy_or_missing_actor_leaves_policy_and_audit_unchanged(string mode, string? actor)
    {
        await using var store = await TestDatabase.CreateAsync();
        var installationId = await AddDevice(store);
        var admin = new AdminService(store.Db, store.Hasher);
        var error = await Assert.ThrowsAsync<DomainException>(() => admin.SetDevicePolicyAsync(installationId, mode, false, actor!));
        Assert.Equal(400, error.StatusCode);
        store.Db.ChangeTracker.Clear();
        var device = await store.Db.Installations.SingleAsync();
        Assert.Equal("off", device.KioskMode);
        Assert.True(device.AllowLocalExit);
        Assert.Empty(await store.Db.DevicePolicyAudits.ToListAsync());
    }

    [Theory]
    [InlineData("off", true, "playback", false)]
    [InlineData("playback", false, "always", false)]
    [InlineData("always", false, "off", true)]
    public async Task Policy_change_persists_old_and_new_values_with_actor_and_server_time(string oldMode, bool oldAllowExit, string newMode, bool newAllowExit)
    {
        await using var store = await TestDatabase.CreateAsync();
        var installationId = await AddDevice(store, oldMode, oldAllowExit);
        var admin = new AdminService(store.Db, store.Hasher);
        var actorId = Guid.NewGuid().ToString("D");
        var before = DateTime.UtcNow;
        await admin.SetDevicePolicyAsync(installationId, newMode, newAllowExit, actorId);
        var after = DateTime.UtcNow;
        store.Db.ChangeTracker.Clear();
        var device = await store.Db.Installations.SingleAsync();
        Assert.Equal(newMode, device.KioskMode);
        Assert.Equal(newAllowExit, device.AllowLocalExit);
        var audit = await store.Db.DevicePolicyAudits.SingleAsync();
        Assert.Equal(installationId, audit.InstallationId);
        Assert.Equal(actorId, audit.ActorId);
        Assert.Equal(oldMode, audit.OldKioskMode);
        Assert.Equal(oldAllowExit, audit.OldAllowLocalExit);
        Assert.Equal(newMode, audit.NewKioskMode);
        Assert.Equal(newAllowExit, audit.NewAllowLocalExit);
        Assert.InRange(audit.CreatedAt, before, after);
    }

    [Fact]
    public async Task Unknown_installation_policy_change_returns_not_found_without_orphan_audit()
    {
        await using var store = await TestDatabase.CreateAsync();
        var admin = new AdminService(store.Db, store.Hasher);
        var error = await Assert.ThrowsAsync<DomainException>(() => admin.SetDevicePolicyAsync(Guid.NewGuid(), "always", false, "administrator"));
        Assert.Equal(404, error.StatusCode);
        store.Db.ChangeTracker.Clear();
        Assert.Empty(await store.Db.Installations.ToListAsync());
        Assert.Empty(await store.Db.DevicePolicyAudits.ToListAsync());
    }

    [Fact]
    public async Task Audit_persistence_failure_rolls_back_policy_change()
    {
        await using var store = await TestDatabase.CreateAsync();
        var installationId = await AddDevice(store);
        await store.Db.Database.ExecuteSqlRawAsync("CREATE TRIGGER reject_policy_audit BEFORE INSERT ON DevicePolicyAudits BEGIN SELECT RAISE(ABORT, 'test audit failure'); END;");
        var admin = new AdminService(store.Db, store.Hasher);
        await Assert.ThrowsAsync<DbUpdateException>(() => admin.SetDevicePolicyAsync(installationId, "always", false, "administrator"));
        store.Db.ChangeTracker.Clear();
        var device = await store.Db.Installations.SingleAsync();
        Assert.Equal("off", device.KioskMode);
        Assert.True(device.AllowLocalExit);
        Assert.Empty(await store.Db.DevicePolicyAudits.ToListAsync());
    }
}
