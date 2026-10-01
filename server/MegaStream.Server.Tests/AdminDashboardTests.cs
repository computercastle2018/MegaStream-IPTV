using MegaStream.Server.Controllers;
using MegaStream.Server.Models;
using MegaStream.Server.Models.Admin;
using MegaStream.Server.Services;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.Configuration;
using Xunit;

namespace MegaStream.Server.Tests;

public class AdminDashboardTests
{
    [Theory]
    [InlineData(null, 2)]
    [InlineData("invalid", 2)]
    [InlineData("0", 2)]
    [InlineData("5", 1)]
    public async Task Dashboard_online_count_uses_ten_minute_default_and_excludes_revoked_devices(string? configuredMinutes, int expectedOnline)
    {
        await using var store = await TestDatabase.CreateAsync();
        var now = DateTime.UtcNow;
        foreach (var age in new[] { 4, 9, 11 })
            store.Db.Installations.Add(new Installation { TokenHash = Guid.NewGuid().ToString("N"), LastSeenAt = now.AddMinutes(-age) });
        store.Db.Installations.Add(new Installation { TokenHash = Guid.NewGuid().ToString("N"), LastSeenAt = now, Status = InstallationStatus.Revoked });
        await store.Db.SaveChangesAsync();
        var configuration = new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string, string?> { ["ONLINE_WINDOW_MINUTES"] = configuredMinutes }).Build();
        var controller = new AdminController(store.Db, new AdminService(store.Db, store.Hasher), configuration);
        var view = Assert.IsType<ViewResult>(await controller.Index(CancellationToken.None));
        var model = Assert.IsType<DashboardViewModel>(view.Model);
        Assert.Equal(expectedOnline, model.OnlineDeviceCount);
        Assert.Equal(4, model.DeviceCount);
    }
}
