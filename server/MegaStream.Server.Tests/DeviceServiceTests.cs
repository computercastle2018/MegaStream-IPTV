using MegaStream.Server.Contracts;
using MegaStream.Server.Models;
using MegaStream.Server.Services;
using Microsoft.EntityFrameworkCore;
using Xunit;

namespace MegaStream.Server.Tests;

public class DeviceServiceTests
{
    private static Task<RegisterResponse> Register(DeviceService service) => service.RegisterAsync(new RegisterRequest(Guid.NewGuid().ToString("D"), "android", "1.0", "Test TV", "14"));
    private static Task<CreatedLicense> License(AdminService admin, int capacity = 1) => admin.CreateLicenseAsync("Test license", DateTime.UtcNow.AddDays(-1), DateTime.UtcNow.AddDays(10), capacity);
    private static LeaseSigner Signer() => new(LeaseSignerTests.Configuration(), new TestEnvironment());

    [Fact]
    public async Task Admin_assignment_licenses_device_and_completes_pending_poll_without_fake_presence()
    {
        await using var store = await TestDatabase.CreateAsync();
        using var signer = Signer();
        var devices = new DeviceService(store.Db, signer, store.Hasher);
        var admin = new AdminService(store.Db, store.Hasher);
        var license = await License(admin);
        var registered = await Register(devices);
        var code = await devices.RequestCodeAsync(registered.InstallationId);
        var lastSeen = (await store.Db.Installations.SingleAsync()).LastSeenAt;
        await admin.AssignInstallationLicenseAsync(registered.InstallationId, license.License.Id);
        await admin.AssignInstallationLicenseAsync(registered.InstallationId, license.License.Id);
        store.Db.ChangeTracker.Clear();
        var device = await store.Db.Installations.Include(x => x.License).SingleAsync();
        Assert.Equal(license.License.Id, device.LicenseId);
        Assert.NotNull(device.ActivatedAt);
        Assert.Equal(lastSeen, device.LastSeenAt);
        Assert.Equal("مرخص", MegaStream.Server.Models.Admin.AdminDisplay.DeviceLicenseState(device));
        Assert.NotNull((await devices.PollCodeAsync(registered.InstallationId, code.Code, code.PollToken)).Lease);
        Assert.NotNull((await devices.GetEntitlementAsync(registered.InstallationId)).Lease);
    }

    [Theory]
    [InlineData("capacity", "capacity_exceeded")]
    [InlineData("suspended", "license_unavailable")]
    [InlineData("expired", "license_unavailable")]
    [InlineData("revoked", "installation_disabled")]
    [InlineData("assigned", "already_licensed")]
    public async Task Admin_assignment_rejects_invalid_grants_without_changing_device_or_pending_code(string scenario, string expected)
    {
        await using var store = await TestDatabase.CreateAsync();
        using var signer = Signer();
        var devices = new DeviceService(store.Db, signer, store.Hasher);
        var admin = new AdminService(store.Db, store.Hasher);
        var license = await License(admin);
        var registered = await Register(devices);
        await devices.RequestCodeAsync(registered.InstallationId);
        Guid? previous = null;
        if (scenario == "capacity") await devices.ActivateAsync((await Register(devices)).InstallationId, license.FullKey);
        if (scenario == "suspended") await admin.SetLicenseStatusAsync(license.License.Id, LicenseStatus.Suspended);
        if (scenario == "expired") { license.License.ValidUntil = DateTime.UtcNow.AddMinutes(-1); await store.Db.SaveChangesAsync(); }
        if (scenario == "revoked") await admin.RevokeInstallationAsync(registered.InstallationId);
        if (scenario == "assigned")
        {
            var other = await License(admin);
            await devices.ActivateAsync(registered.InstallationId, other.FullKey);
            previous = other.License.Id;
        }
        var error = await Assert.ThrowsAsync<DomainException>(() => admin.AssignInstallationLicenseAsync(registered.InstallationId, license.License.Id));
        Assert.Equal(expected, error.Code);
        store.Db.ChangeTracker.Clear();
        Assert.Equal(previous, (await store.Db.Installations.SingleAsync(x => x.Id == registered.InstallationId)).LicenseId);
        Assert.Equal(ActivationCodeStatus.Pending, (await store.Db.ActivationCodes.SingleAsync()).Status);
    }

    [Theory]
    [InlineData("negative-start")]
    [InlineData("same-unix-second")]
    public async Task License_windows_unrepresentable_in_jwt_seconds_are_rejected_without_persistence(string scenario)
    {
        await using var store = await TestDatabase.CreateAsync();
        var admin = new AdminService(store.Db, store.Hasher);
        var second = new DateTime(2030, 1, 2, 12, 0, 0, DateTimeKind.Utc);
        var from = scenario == "negative-start" ? DateTime.UnixEpoch.AddMilliseconds(-1) : second.AddMilliseconds(400);
        var until = scenario == "negative-start" ? second : second.AddMilliseconds(600);
        var error = await Assert.ThrowsAsync<DomainException>(() => admin.CreateLicenseAsync("Invalid window", from, until, 1));
        Assert.Equal(400, error.StatusCode);
        store.Db.ChangeTracker.Clear();
        Assert.Empty(await store.Db.Licenses.ToListAsync());
    }

    [Fact]
    public async Task Repeated_activation_is_idempotent_and_capacity_failure_leaves_second_device_unassigned()
    {
        await using var store = await TestDatabase.CreateAsync();
        using var signer = Signer();
        var devices = new DeviceService(store.Db, signer, store.Hasher);
        var admin = new AdminService(store.Db, store.Hasher);
        var license = await License(admin);
        var first = await Register(devices);
        var second = await Register(devices);

        Assert.NotNull((await devices.ActivateAsync(first.InstallationId, license.FullKey)).Lease);
        Assert.NotNull((await devices.ActivateAsync(first.InstallationId, license.FullKey)).Lease);
        var error = await Assert.ThrowsAsync<DomainException>(() => devices.ActivateAsync(second.InstallationId, license.FullKey));
        Assert.Equal(409, error.StatusCode);
        store.Db.ChangeTracker.Clear();
        Assert.Equal(1, await store.Db.Installations.CountAsync(x => x.LicenseId == license.License.Id));
        Assert.Null((await store.Db.Installations.SingleAsync(x => x.Id == second.InstallationId)).LicenseId);
    }

    [Fact]
    public async Task Concurrent_activation_of_last_seat_allows_exactly_one_assignment()
    {
        await using var store = await TestDatabase.CreateAsync();
        using var signer = Signer();
        var devices = new DeviceService(store.Db, signer, store.Hasher);
        var license = await License(new AdminService(store.Db, store.Hasher));
        var candidates = new[] { await Register(devices), await Register(devices) };
        using var start = new Barrier(2);
        var attempts = candidates.Select(candidate => Task.Run(async () =>
        {
            await using var db = store.NewContext();
            if (!start.SignalAndWait(TimeSpan.FromSeconds(10))) throw new TimeoutException("Activation contenders did not start.");
            try
            {
                await new DeviceService(db, signer, store.Hasher).ActivateAsync(candidate.InstallationId, license.FullKey);
                return 200;
            }
            catch (DomainException error) { return error.StatusCode; }
        })).ToArray();
        var results = await Task.WhenAll(attempts);
        Assert.Equal(new[] { 200, 409 }, results.Order().ToArray());
        store.Db.ChangeTracker.Clear();
        Assert.Equal(1, await store.Db.Installations.CountAsync(x => x.LicenseId == license.License.Id));
    }

    [Fact]
    public async Task Revoked_installation_reserves_capacity_until_last_offline_lease_expires()
    {
        await using var store = await TestDatabase.CreateAsync();
        using var signer = Signer();
        var devices = new DeviceService(store.Db, signer, store.Hasher);
        var admin = new AdminService(store.Db, store.Hasher);
        var license = await License(admin);
        var first = await Register(devices);
        var second = await Register(devices);
        await devices.ActivateAsync(first.InstallationId, license.FullKey);
        await admin.RevokeInstallationAsync(first.InstallationId);

        var heldSeat = await Assert.ThrowsAsync<DomainException>(() => devices.ActivateAsync(second.InstallationId, license.FullKey));
        Assert.Equal(409, heldSeat.StatusCode);
        var error = await Assert.ThrowsAsync<DomainException>(() => devices.ActivateAsync(first.InstallationId, license.FullKey));
        Assert.Equal(403, error.StatusCode);
        var revoked = await store.Db.Installations.SingleAsync(x => x.Id == first.InstallationId);
        revoked.LastLeaseExpiresAt = DateTime.UtcNow.AddMinutes(-1);
        await store.Db.SaveChangesAsync();
        Assert.NotNull((await devices.ActivateAsync(second.InstallationId, license.FullKey)).Lease);
    }

    [Fact]
    public async Task Lowering_capacity_below_usage_is_rejected_without_changing_license()
    {
        await using var store = await TestDatabase.CreateAsync();
        using var signer = Signer();
        var devices = new DeviceService(store.Db, signer, store.Hasher);
        var admin = new AdminService(store.Db, store.Hasher);
        var license = await License(admin, 2);
        var originalValidUntil = license.License.ValidUntil;
        await devices.ActivateAsync((await Register(devices)).InstallationId, license.FullKey);
        await devices.ActivateAsync((await Register(devices)).InstallationId, license.FullKey);
        var error = await Assert.ThrowsAsync<DomainException>(() => admin.UpdateLicenseLimitsAsync(license.License.Id, DateTime.UtcNow.AddDays(20), 1));
        Assert.Equal(409, error.StatusCode);
        store.Db.ChangeTracker.Clear();
        var persisted = await store.Db.Licenses.SingleAsync();
        Assert.Equal(2, persisted.MaxInstallations);
        Assert.Equal(originalValidUntil, persisted.ValidUntil);
    }

    [Theory]
    [InlineData(-1, false)]
    [InlineData(0, false)]
    [InlineData(1, true)]
    [InlineData(3, true)]
    [InlineData(4, false)]
    public async Task Offline_grace_policy_accepts_only_one_to_three_days_and_revises_valid_updates(int days, bool valid)
    {
        await using var store = await TestDatabase.CreateAsync();
        var admin = new AdminService(store.Db, store.Hasher);
        var license = await License(admin);
        var originalRevision = license.License.Revision;
        if (valid)
            await admin.SetOfflineGraceAsync(license.License.Id, days);
        else
            Assert.Equal(400, (await Assert.ThrowsAsync<DomainException>(() => admin.SetOfflineGraceAsync(license.License.Id, days))).StatusCode);
        store.Db.ChangeTracker.Clear();
        var persisted = await store.Db.Licenses.SingleAsync();
        Assert.Equal(valid ? days : 3, persisted.OfflineGraceDays);
        Assert.Equal(valid ? originalRevision + 1 : originalRevision, persisted.Revision);
    }

    [Theory]
    [InlineData(LicenseStatus.Revoked, false)]
    [InlineData(LicenseStatus.Suspended, false)]
    [InlineData(LicenseStatus.Active, true)]
    public async Task Unavailable_license_is_denied_even_with_existing_assignment_and_offline_grace(LicenseStatus status, bool expired)
    {
        await using var store = await TestDatabase.CreateAsync();
        using var signer = Signer();
        var devices = new DeviceService(store.Db, signer, store.Hasher);
        var admin = new AdminService(store.Db, store.Hasher);
        var license = await License(admin);
        var device = await Register(devices);
        await devices.ActivateAsync(device.InstallationId, license.FullKey);
        if (expired)
            await admin.UpdateLicenseLimitsAsync(license.License.Id, DateTime.UtcNow.AddMinutes(-1), 1);
        else
            await admin.SetLicenseStatusAsync(license.License.Id, status);

        var error = await Assert.ThrowsAsync<DomainException>(() => devices.ActivateAsync(device.InstallationId, license.FullKey));
        Assert.Equal(403, error.StatusCode);
        var heartbeat = await devices.HeartbeatAsync(device.InstallationId, new HeartbeatRequest("session-1", 1000, null));
        Assert.Null(heartbeat.Lease);
    }

    [Fact]
    public async Task Activation_code_requires_owner_and_poll_secret_and_is_consumed_only_once()
    {
        await using var store = await TestDatabase.CreateAsync();
        using var signer = Signer();
        var devices = new DeviceService(store.Db, signer, store.Hasher);
        var admin = new AdminService(store.Db, store.Hasher);
        var license = await License(admin);
        var owner = await Register(devices);
        var intruder = await Register(devices);
        var issued = await devices.RequestCodeAsync(owner.InstallationId);
        var codeId = (await store.Db.ActivationCodes.SingleAsync()).Id;
        await admin.ApproveActivationAsync(codeId, license.License.Id);

        await Assert.ThrowsAsync<DomainException>(() => devices.PollCodeAsync(intruder.InstallationId, issued.Code, issued.PollToken));
        await Assert.ThrowsAsync<DomainException>(() => devices.PollCodeAsync(owner.InstallationId, issued.Code, "wrong-secret"));
        var redeemed = await devices.PollCodeAsync(owner.InstallationId, issued.Code, issued.PollToken);
        Assert.NotNull(redeemed.Lease);
        Assert.Equal("approved", redeemed.Status);
        var replay = await devices.PollCodeAsync(owner.InstallationId, issued.Code, issued.PollToken);
        Assert.Null(replay.Lease);
        store.Db.ChangeTracker.Clear();
        var persisted = await store.Db.ActivationCodes.SingleAsync();
        Assert.Equal(ActivationCodeStatus.Consumed, persisted.Status);
        Assert.NotNull(persisted.ConsumedAt);
        Assert.Null((await store.Db.Installations.SingleAsync(x => x.Id == intruder.InstallationId)).LicenseId);
    }

    [Fact]
    public async Task Expired_approved_code_cannot_assign_license_or_issue_lease()
    {
        await using var store = await TestDatabase.CreateAsync();
        using var signer = Signer();
        var devices = new DeviceService(store.Db, signer, store.Hasher);
        var admin = new AdminService(store.Db, store.Hasher);
        var license = await License(admin);
        var owner = await Register(devices);
        var issued = await devices.RequestCodeAsync(owner.InstallationId);
        var code = await store.Db.ActivationCodes.SingleAsync();
        await admin.ApproveActivationAsync(code.Id, license.License.Id);
        code.ExpiresAt = DateTime.UtcNow.AddMinutes(-1);
        await store.Db.SaveChangesAsync();
        var response = await devices.PollCodeAsync(owner.InstallationId, issued.Code, issued.PollToken);
        Assert.Equal("expired", response.Status);
        Assert.Null(response.Lease);
        store.Db.ChangeTracker.Clear();
        Assert.Null((await store.Db.Installations.SingleAsync()).LicenseId);
        Assert.Null((await store.Db.ActivationCodes.SingleAsync()).ConsumedAt);
    }

    [Fact]
    public async Task Capacity_failure_during_code_redemption_rolls_back_consumption_and_assignment()
    {
        await using var store = await TestDatabase.CreateAsync();
        using var signer = Signer();
        var devices = new DeviceService(store.Db, signer, store.Hasher);
        var admin = new AdminService(store.Db, store.Hasher);
        var license = await License(admin);
        var waiting = await Register(devices);
        var issued = await devices.RequestCodeAsync(waiting.InstallationId);
        await admin.ApproveActivationAsync((await store.Db.ActivationCodes.SingleAsync()).Id, license.License.Id);
        await devices.ActivateAsync((await Register(devices)).InstallationId, license.FullKey);

        var error = await Assert.ThrowsAsync<DomainException>(() => devices.PollCodeAsync(waiting.InstallationId, issued.Code, issued.PollToken));
        Assert.Equal(409, error.StatusCode);
        store.Db.ChangeTracker.Clear();
        var code = await store.Db.ActivationCodes.SingleAsync();
        Assert.Equal(ActivationCodeStatus.Approved, code.Status);
        Assert.Null(code.ConsumedAt);
        Assert.Null((await store.Db.Installations.SingleAsync(x => x.Id == waiting.InstallationId)).LicenseId);
    }

    [Fact]
    public async Task Existing_session_accepts_end_time_without_requiring_start_time_again()
    {
        await using var store = await TestDatabase.CreateAsync();
        using var signer = Signer();
        var devices = new DeviceService(store.Db, signer, store.Hasher);
        var device = await Register(devices);
        var started = DateTime.UtcNow.AddMinutes(-10);
        var ended = DateTime.UtcNow.AddMinutes(-1);
        await devices.HeartbeatAsync(device.InstallationId, new HeartbeatRequest("session-1", 1000, null, StartedAt: started));
        await devices.HeartbeatAsync(device.InstallationId, new HeartbeatRequest("session-1", 900, "normal", EndedAt: ended));
        store.Db.ChangeTracker.Clear();
        var persisted = await store.Db.DeviceSessions.SingleAsync();
        Assert.Equal(started, persisted.StartedAt);
        Assert.Equal(ended, persisted.EndedAt);
    }

    [Theory]
    [InlineData("oversized")]
    [InlineData("null-list")]
    [InlineData("null-entry")]
    [InlineData("secret")]
    public async Task Invalid_diagnostic_batch_is_rejected_atomically(string scenario)
    {
        await using var store = await TestDatabase.CreateAsync();
        using var signer = Signer();
        var devices = new DeviceService(store.Db, signer, store.Hasher);
        var device = await Register(devices);
        var valid = new DiagnosticItem("info", "player", "Playback started");
        var entries = scenario switch
        {
            "oversized" => Enumerable.Repeat(valid, 51).ToList(),
            "null-list" => null!,
            "null-entry" => new List<DiagnosticItem> { valid, null! },
            _ => new List<DiagnosticItem> { valid, new("error", "player", "Failed https://provider.invalid/live?token=secret") }
        };
        var error = await Assert.ThrowsAsync<DomainException>(() => devices.DiagnosticsAsync(device.InstallationId, new DiagnosticsRequest(entries)));
        Assert.Equal(400, error.StatusCode);
        store.Db.ChangeTracker.Clear();
        Assert.Empty(await store.Db.DiagnosticEvents.ToListAsync());
    }

    [Fact]
    public async Task Diagnostic_event_retries_are_idempotent_within_installation_not_globally()
    {
        await using var store = await TestDatabase.CreateAsync();
        using var signer = Signer();
        var devices = new DeviceService(store.Db, signer, store.Hasher);
        var first = await Register(devices);
        var second = await Register(devices);
        var item = new DiagnosticItem("info", "player", "Playback started", EventId: Guid.NewGuid());
        var batch = new DiagnosticsRequest([item]);
        await devices.DiagnosticsAsync(first.InstallationId, batch);
        await devices.DiagnosticsAsync(first.InstallationId, batch);
        await devices.DiagnosticsAsync(second.InstallationId, batch);
        store.Db.ChangeTracker.Clear();
        var persisted = await store.Db.DiagnosticEvents.ToListAsync();
        Assert.Equal(2, persisted.Count);
        Assert.Equal(2, persisted.Select(x => x.InstallationId).Distinct().Count());
        Assert.All(persisted, entry => Assert.Equal(item.EventId, entry.EventId));
    }

    [Fact]
    public async Task Diagnostic_session_owned_by_another_installation_rejects_entire_batch()
    {
        await using var store = await TestDatabase.CreateAsync();
        using var signer = Signer();
        var devices = new DeviceService(store.Db, signer, store.Hasher);
        var owner = await Register(devices);
        var other = await Register(devices);
        await devices.HeartbeatAsync(owner.InstallationId, new HeartbeatRequest("private-session", 1000, null));
        var batch = new DiagnosticsRequest([new("info", "player", "Safe event"), new("info", "player", "Event", SessionId: "private-session")]);
        var error = await Assert.ThrowsAsync<DomainException>(() => devices.DiagnosticsAsync(other.InstallationId, batch));
        Assert.Equal(400, error.StatusCode);
        store.Db.ChangeTracker.Clear();
        Assert.Empty(await store.Db.DiagnosticEvents.ToListAsync());
    }

    [Fact]
    public async Task Fifty_safe_diagnostic_events_are_accepted_and_bounded()
    {
        await using var store = await TestDatabase.CreateAsync();
        using var signer = Signer();
        var devices = new DeviceService(store.Db, signer, store.Hasher);
        var device = await Register(devices);
        var entries = Enumerable.Range(0, 50).Select(i => new DiagnosticItem("info", "player", $"Event {i}: " + new string('x', 3000))).ToList();
        await devices.DiagnosticsAsync(device.InstallationId, new DiagnosticsRequest(entries));
        store.Db.ChangeTracker.Clear();
        var persisted = await store.Db.DiagnosticEvents.ToListAsync();
        Assert.Equal(50, persisted.Count);
        Assert.All(persisted, entry => { Assert.Equal(device.InstallationId, entry.InstallationId); Assert.InRange(entry.Message.Length, 1, 2048); });
    }
}
