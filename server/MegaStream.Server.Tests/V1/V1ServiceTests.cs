using System.Text.Json;
using System.Text.Json.Nodes;
using MegaStream.Server.Data;
using MegaStream.Server.Models;
using MegaStream.Server.Services;
using MegaStream.Server.Updates;
using MegaStream.Server.V1;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using Xunit;

namespace MegaStream.Server.Tests;

public sealed class V1ServiceTests
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);

    [Fact]
    public async Task Admin_assignment_is_received_by_waiting_v1_activation_screen()
    {
        await using var scenario = await Scenario.CreateAsync();
        var registered = await scenario.Service.RegisterAsync(Registration(), Guid.NewGuid().ToString("D"));
        var issued = await scenario.Service.RequestCodeAsync(registered.InstallationId);
        var admin = new AdminService(scenario.Db, scenario.Store.Hasher);
        var license = await admin.CreateLicenseAsync("Panel license", DateTime.UtcNow.AddDays(-1), DateTime.UtcNow.AddDays(10), 1);
        await admin.AssignInstallationLicenseAsync(registered.InstallationId, license.License.Id);
        var response = Assert.IsType<V1EntitlementResponse>(await scenario.Service.PollCodeAsync(registered.InstallationId, issued.Code, issued.PollToken));
        Assert.Equal("allowed", response.Decision.State);
        Assert.NotNull(response.Lease);
        Assert.Equal(license.License.Id, response.Decision.LicenseId);
        Assert.Equal(ActivationCodeStatus.Consumed, (await scenario.Db.ActivationCodes.SingleAsync()).Status);
    }

    [Fact]
    public async Task Registration_retry_preserves_timestamp_and_presence_but_rejects_a_different_credential()
    {
        await using var scenario = await Scenario.CreateAsync();
        var request = Registration();
        var key = Guid.NewGuid().ToString("D");
        var registered = await scenario.Service.RegisterAsync(request, key);
        var presence = (await scenario.Db.Installations.SingleAsync()).LastSeenAt;
        scenario.Db.ChangeTracker.Clear();
        var replay = await scenario.Service.RegisterAsync(request with { AppVersionCode = 2 }, key);
        Assert.Equal(registered, replay);
        Assert.Equal(DateTimeKind.Utc, replay.RegisteredAt.Kind);
        Assert.Equal(presence, (await scenario.Db.Installations.SingleAsync()).LastSeenAt);
        Assert.Equal(1, (await scenario.Db.Set<V1InstallationMetadata>().SingleAsync()).AppVersionCode);
        Assert.True(scenario.Store.Hasher.Matches("device", request.Credential, (await scenario.Db.Installations.SingleAsync()).TokenHash));
        Assert.Equal(SecretHasher.CredentialBinding(request.Credential), (await scenario.Db.Installations.SingleAsync()).CredentialBinding);
        var conflict = await Assert.ThrowsAsync<DomainException>(() => scenario.Service.RegisterAsync(
            request with { Credential = Secrets.RandomToken() }, key));
        Assert.Equal(409, conflict.StatusCode);
        Assert.Equal(1, await scenario.Db.Installations.CountAsync());
    }

    [Fact]
    public async Task Concurrent_registration_has_one_row_and_the_same_registered_at()
    {
        await using var scenario = await Scenario.CreateAsync();
        var request = Registration();
        using var start = new Barrier(2);
        var attempts = Enumerable.Range(0, 2).Select(_ => Task.Run(async () =>
        {
            await using var db = scenario.Store.NewContext();
            if (!start.SignalAndWait(TimeSpan.FromSeconds(10))) throw new TimeoutException("Registration contenders did not start.");
            return await scenario.For(db).RegisterAsync(request, Guid.NewGuid().ToString("D"));
        })).ToArray();
        var responses = await Task.WhenAll(attempts);
        Assert.Equal(responses[0], responses[1]);
        Assert.Equal(1, await scenario.Db.Installations.CountAsync());
        Assert.Equal(1, await scenario.Db.Set<V1InstallationMetadata>().CountAsync());
        Assert.Equal(1, await scenario.Db.Set<DeviceUpdateState>().CountAsync());
    }

    [Theory]
    [InlineData("credential_alias")]
    [InlineData("credential_padded")]
    [InlineData("version")]
    [InlineData("api")]
    [InlineData("abi")]
    [InlineData("package_channel")]
    [InlineData("name_long")]
    [InlineData("metadata_secret")]
    [InlineData("idempotency")]
    public async Task Invalid_registration_is_rejected_before_persisting(string invalidField)
    {
        await using var scenario = await Scenario.CreateAsync();
        var request = Registration();
        request = invalidField switch
        {
            "credential_alias" => request with { Credential = new string('A', 42) + "B" },
            "credential_padded" => request with { Credential = request.Credential + "=" },
            "version" => request with { AppVersionCode = 0 },
            "api" => request with { AndroidApi = 26 },
            "abi" => request with { Abi = "arm64" },
            "package_channel" => request with { Channel = "beta" },
            "name_long" => request with { AppVersionName = new string('a', 33) },
            "metadata_secret" => request with { Model = "https://provider.example/live/user/pass" },
            _ => request
        };
        var error = await Assert.ThrowsAsync<DomainException>(() => scenario.Service.RegisterAsync(request,
            invalidField == "idempotency" ? "not-a-uuid" : Guid.NewGuid().ToString("D")));
        Assert.Equal(400, error.StatusCode);
        Assert.Empty(await scenario.Db.Installations.ToListAsync());
    }

    [Fact]
    public void Canonical_request_contracts_reject_missing_fields_and_unmapped_nested_data()
    {
        var registration = JsonSerializer.SerializeToNode(Registration(), JsonOptions)!.AsObject();
        foreach (var field in registration.Select(property => property.Key).ToArray())
        {
            var missing = registration.DeepClone().AsObject();
            missing.Remove(field);
            Assert.Throws<JsonException>(() => JsonSerializer.Deserialize<V1RegisterRequest>(missing.ToJsonString(), JsonOptions));
        }
        registration["fingerprint"] = "hardware-id";
        Assert.Throws<JsonException>(() => JsonSerializer.Deserialize<V1RegisterRequest>(registration.ToJsonString(), JsonOptions));
        var heartbeat = JsonSerializer.SerializeToNode(Heartbeat(), JsonOptions)!.AsObject();
        foreach (var field in heartbeat.Select(property => property.Key).Where(field => field is not ("memory" or "recoveredExit")).ToArray())
        {
            var missing = heartbeat.DeepClone().AsObject();
            missing.Remove(field);
            Assert.Throws<JsonException>(() => JsonSerializer.Deserialize<V1HeartbeatRequest>(missing.ToJsonString(), JsonOptions));
        }
        heartbeat["memory"] = JsonSerializer.SerializeToNode(Memory(), JsonOptions);
        heartbeat["memory"]!["providerUrl"] = "https://provider.example";
        Assert.Throws<JsonException>(() => JsonSerializer.Deserialize<V1HeartbeatRequest>(heartbeat.ToJsonString(), JsonOptions));
        heartbeat["memory"] = JsonSerializer.SerializeToNode(Memory(), JsonOptions);
        heartbeat["memory"]!.AsObject().Remove("lowMemory");
        Assert.Throws<JsonException>(() => JsonSerializer.Deserialize<V1HeartbeatRequest>(heartbeat.ToJsonString(), JsonOptions));
    }

    [Theory]
    [InlineData("2030-01-02T12:00:00Z", true)]
    [InlineData("2030-01-02T12:00:00+00:00", true)]
    [InlineData("2030-01-02T12:00:00", false)]
    [InlineData("2030-01-02T15:00:00+03:00", false)]
    [InlineData("2030-01-02T12:00:00-00:00", false)]
    public void Recovered_exit_timestamp_requires_explicit_utc_and_serializes_with_z(string timestamp, bool accepted)
    {
        var json = JsonSerializer.Serialize(new { reason = "oom", evidence = "inferred", occurredAt = timestamp });
        if (!accepted)
        {
            Assert.Throws<JsonException>(() => JsonSerializer.Deserialize<V1RecoveredExit>(json, JsonOptions));
            return;
        }
        var exit = JsonSerializer.Deserialize<V1RecoveredExit>(json, JsonOptions)!;
        Assert.Equal(TimeSpan.Zero, exit.OccurredAt!.Value.Offset);
        var serialized = JsonSerializer.SerializeToElement(exit, JsonOptions);
        Assert.EndsWith("Z", serialized.GetProperty("occurredAt").GetString());
    }

    [Fact]
    public async Task Duplicate_and_stale_sequences_do_not_rewrite_presence_telemetry_or_update_targeting()
    {
        await using var scenario = await Scenario.CreateAsync();
        var registered = await scenario.RegisterAsync();
        var heartbeat = Heartbeat() with { Sequence = 4, Memory = Memory() };
        await scenario.Service.HeartbeatAsync(registered.InstallationId, heartbeat);
        var acceptedPresence = (await scenario.Db.Installations.SingleAsync()).LastSeenAt;
        var updateRevision = (await scenario.Db.Set<DeviceUpdateState>().SingleAsync()).Revision;
        foreach (var sequence in new long[] { 4, 3 })
            await scenario.Service.HeartbeatAsync(registered.InstallationId, heartbeat with
            {
                Sequence = sequence, AppVersionCode = 99, AppVersionName = "99", ManagedDevice = true,
                Memory = Memory() with { PssBytes = 9000 },
                RecoveredExit = new V1RecoveredExit { Reason = "oom", Evidence = "inferred" }
            });
        scenario.Db.ChangeTracker.Clear();
        Assert.Equal(acceptedPresence, (await scenario.Db.Installations.SingleAsync()).LastSeenAt);
        Assert.Equal(acceptedPresence, (await scenario.Db.DeviceSessions.SingleAsync()).LastHeartbeatAt);
        var state = await scenario.Db.Set<V1SessionState>().SingleAsync();
        Assert.Equal(4, state.LastSequence);
        Assert.Equal(100, state.PssBytes);
        Assert.Null(state.RecoveredExitReason);
        var targeting = await scenario.Db.Set<DeviceUpdateState>().SingleAsync();
        Assert.Equal(updateRevision, targeting.Revision);
        Assert.Equal(1, targeting.VersionCode);
        Assert.Equal("prompt", targeting.Mode);
        await scenario.Service.HeartbeatAsync(registered.InstallationId, heartbeat with { Sequence = 5, AppVersionCode = 2 });
        Assert.Equal(5, (await scenario.Db.Set<V1SessionState>().SingleAsync()).LastSequence);
        Assert.Equal(2, (await scenario.Db.Set<DeviceUpdateState>().SingleAsync()).VersionCode);
        await scenario.Service.HeartbeatAsync(registered.InstallationId, heartbeat with { AppSessionId = Guid.NewGuid(), Sequence = 0 });
        Assert.Equal(2, await scenario.Db.DeviceSessions.CountAsync());
    }

    [Fact]
    public async Task Concurrent_heartbeats_keep_the_highest_sequence_and_its_metadata()
    {
        await using var scenario = await Scenario.CreateAsync();
        var registered = await scenario.RegisterAsync();
        var heartbeat = Heartbeat();
        using var start = new Barrier(2);
        var attempts = new[] { 10, 20 }.Select(sequence => Task.Run(async () =>
        {
            await using var db = scenario.Store.NewContext();
            if (!start.SignalAndWait(TimeSpan.FromSeconds(10))) throw new TimeoutException("Heartbeat contenders did not start.");
            await scenario.For(db).HeartbeatAsync(registered.InstallationId,
                heartbeat with { Sequence = sequence, AppVersionCode = sequence, AppVersionName = sequence.ToString() });
        })).ToArray();
        await Task.WhenAll(attempts);
        scenario.Db.ChangeTracker.Clear();
        Assert.Equal(20, (await scenario.Db.Set<V1SessionState>().SingleAsync()).LastSequence);
        Assert.Equal("20", (await scenario.Db.Installations.SingleAsync()).AppVersion);
        Assert.Equal(20, (await scenario.Db.Set<DeviceUpdateState>().SingleAsync()).VersionCode);
        Assert.Equal(1, await scenario.Db.DeviceSessions.CountAsync());
    }

    [Theory]
    [InlineData("sequence")]
    [InlineData("mode")]
    [InlineData("memory")]
    [InlineData("exit")]
    [InlineData("time")]
    [InlineData("offset")]
    [InlineData("build")]
    public async Task Invalid_heartbeat_cannot_advance_presence_or_create_session(string invalidField)
    {
        await using var scenario = await Scenario.CreateAsync();
        var registered = await scenario.RegisterAsync();
        var before = (await scenario.Db.Installations.SingleAsync()).LastSeenAt;
        var request = Heartbeat();
        request = invalidField switch
        {
            "sequence" => request with { Sequence = -1 },
            "mode" => request with { Mode = "unknown" },
            "memory" => request with { Memory = Memory() with { PssBytes = -1 } },
            "exit" => request with { RecoveredExit = new V1RecoveredExit { Reason = "not_an_enum", Evidence = "reported" } },
            "time" => request with { RecoveredExit = new V1RecoveredExit { Reason = "oom", Evidence = "inferred", OccurredAt = DateTimeOffset.UtcNow.AddDays(1) } },
            "offset" => request with { RecoveredExit = new V1RecoveredExit { Reason = "oom", Evidence = "inferred", OccurredAt = DateTimeOffset.UtcNow.ToOffset(TimeSpan.FromHours(3)) } },
            "build" => request with { PackageName = "com.megastream.app.beta", Channel = "beta" },
            _ => request
        };
        var error = await Assert.ThrowsAsync<DomainException>(() => scenario.Service.HeartbeatAsync(registered.InstallationId, request));
        Assert.Equal(invalidField == "build" ? 409 : 400, error.StatusCode);
        scenario.Db.ChangeTracker.Clear();
        Assert.Equal(before, (await scenario.Db.Installations.SingleAsync()).LastSeenAt);
        Assert.Empty(await scenario.Db.DeviceSessions.ToListAsync());
    }

    [Theory]
    [InlineData("allowed")]
    [InlineData("not_started")]
    [InlineData("expired")]
    [InlineData("suspended")]
    [InlineData("revoked")]
    [InlineData("installation_disabled")]
    [InlineData("unlicensed")]
    [InlineData("verification_required")]
    public async Task Entitlement_reports_current_decision_without_changing_presence(string expectedState)
    {
        await using var scenario = await Scenario.CreateAsync();
        var registered = await scenario.RegisterAsync();
        var installation = await scenario.Db.Installations.SingleAsync();
        var license = (await scenario.LicenseAsync()).License;
        if (expectedState != "unlicensed") installation.LicenseId = license.Id;
        if (expectedState == "not_started") license.ValidFrom = DateTime.UtcNow.AddDays(1);
        if (expectedState == "expired") license.ValidUntil = DateTime.UtcNow.AddMinutes(-1);
        if (expectedState == "suspended") license.Status = LicenseStatus.Suspended;
        if (expectedState == "revoked") license.Status = LicenseStatus.Revoked;
        if (expectedState == "verification_required") license.Status = (LicenseStatus)99;
        if (expectedState == "installation_disabled") installation.Status = InstallationStatus.Revoked;
        await scenario.Db.SaveChangesAsync();
        var before = installation.LastSeenAt;
        scenario.Db.ChangeTracker.Clear();
        var entitlement = await scenario.Service.EntitlementAsync(registered.InstallationId);
        Assert.Null(entitlement.DevicePolicy);
        Assert.False(JsonSerializer.SerializeToElement(entitlement, JsonOptions).TryGetProperty("devicePolicy", out _));
        Assert.Equal(expectedState, entitlement.Decision.State);
        Assert.Equal(before, (await scenario.Db.Installations.SingleAsync()).LastSeenAt);
        Assert.Equal(expectedState == "allowed", entitlement.Lease is not null);
        Assert.Equal(expectedState == "allowed", entitlement.Decision.OfflineUntil is not null);
        if (expectedState != "unlicensed")
        {
            Assert.Equal(license.Id, entitlement.Decision.LicenseId);
            Assert.Equal(license.Revision, entitlement.Decision.LicenseRevision);
            Assert.Equal(DateTimeKind.Utc, entitlement.Decision.StartsAt!.Value.Kind);
            Assert.Equal(DateTimeKind.Utc, entitlement.Decision.EndsAt!.Value.Kind);
        }
        else Assert.Null(entitlement.Decision.LicenseRevision);
    }

    [Theory]
    [InlineData("not_started")]
    [InlineData("expired")]
    [InlineData("suspended")]
    [InlineData("revoked")]
    public async Task Candidate_denial_does_not_assign_a_seat(string expectedState)
    {
        await using var scenario = await Scenario.CreateAsync();
        var registered = await scenario.RegisterAsync();
        var created = await scenario.LicenseAsync();
        if (expectedState == "not_started") created.License.ValidFrom = DateTime.UtcNow.AddDays(1);
        if (expectedState == "expired") created.License.ValidUntil = DateTime.UtcNow.AddMinutes(-1);
        if (expectedState == "suspended") created.License.Status = LicenseStatus.Suspended;
        if (expectedState == "revoked") created.License.Status = LicenseStatus.Revoked;
        await scenario.Db.SaveChangesAsync();
        var denied = await scenario.Service.ActivateAsync(registered.InstallationId, created.FullKey);
        Assert.Null(denied.DevicePolicy);
        Assert.False(JsonSerializer.SerializeToElement(denied, JsonOptions).TryGetProperty("devicePolicy", out _));
        Assert.Equal(expectedState, denied.Decision.State);
        Assert.Equal(created.License.Id, denied.Decision.LicenseId);
        Assert.Null(denied.Lease);
        scenario.Db.ChangeTracker.Clear();
        Assert.Null((await scenario.Db.Installations.SingleAsync()).LicenseId);
        Assert.Null((await scenario.Db.Installations.SingleAsync()).LastLeaseExpiresAt);
    }

    [Fact]
    public async Task Approved_code_with_a_newly_suspended_license_returns_denial_without_consumption()
    {
        await using var scenario = await Scenario.CreateAsync();
        var registered = await scenario.RegisterAsync();
        var created = await scenario.LicenseAsync();
        var issued = await scenario.Service.RequestCodeAsync(registered.InstallationId);
        var admin = new AdminService(scenario.Db, scenario.Store.Hasher);
        await admin.ApproveActivationAsync((await scenario.Db.ActivationCodes.SingleAsync()).Id, created.License.Id);
        await admin.SetLicenseStatusAsync(created.License.Id, LicenseStatus.Suspended);
        var denied = Assert.IsType<V1EntitlementResponse>(await scenario.Service.PollCodeAsync(registered.InstallationId, issued.Code, issued.PollToken));
        Assert.Equal("suspended", denied.Decision.State);
        Assert.Null(denied.Lease);
        scenario.Db.ChangeTracker.Clear();
        Assert.Equal(ActivationCodeStatus.Approved, (await scenario.Db.ActivationCodes.SingleAsync()).Status);
        Assert.Null((await scenario.Db.ActivationCodes.SingleAsync()).ConsumedAt);
        Assert.Null((await scenario.Db.Installations.SingleAsync()).LicenseId);
    }

    [Fact]
    public async Task Consumed_poll_replays_current_entitlement_without_another_seat_or_presence_change()
    {
        await using var scenario = await Scenario.CreateAsync();
        var registered = await scenario.RegisterAsync();
        var created = await scenario.LicenseAsync();
        var issued = await scenario.Service.RequestCodeAsync(registered.InstallationId);
        var pending = Assert.IsType<V1ActivationPendingResponse>(await scenario.Service.PollCodeAsync(registered.InstallationId, issued.Code, issued.PollToken));
        Assert.Equal(5, pending.RetryAfterSeconds);
        await new AdminService(scenario.Db, scenario.Store.Hasher).ApproveActivationAsync((await scenario.Db.ActivationCodes.SingleAsync()).Id, created.License.Id);
        var granted = Assert.IsType<V1EntitlementResponse>(await scenario.Service.PollCodeAsync(registered.InstallationId, issued.Code, issued.PollToken));
        Assert.Equal("allowed", granted.Decision.State);
        Assert.Null(granted.DevicePolicy);
        Assert.False(JsonSerializer.SerializeToElement(granted, JsonOptions).TryGetProperty("devicePolicy", out _));
        var consumedAt = (await scenario.Db.ActivationCodes.SingleAsync()).ConsumedAt;
        var presence = (await scenario.Db.Installations.SingleAsync()).LastSeenAt;
        await new AdminService(scenario.Db, scenario.Store.Hasher).SetLicenseStatusAsync(created.License.Id, LicenseStatus.Suspended);
        var replay = Assert.IsType<V1EntitlementResponse>(await scenario.Service.PollCodeAsync(registered.InstallationId, issued.Code, issued.PollToken));
        Assert.Equal("suspended", replay.Decision.State);
        Assert.Null(replay.Lease);
        Assert.Equal(consumedAt, (await scenario.Db.ActivationCodes.SingleAsync()).ConsumedAt);
        Assert.Equal(presence, (await scenario.Db.Installations.SingleAsync()).LastSeenAt);
        Assert.Equal(1, await scenario.Db.Installations.CountAsync(x => x.LicenseId == created.License.Id));
        var wrongSecret = await Assert.ThrowsAsync<DomainException>(() => scenario.Service.PollCodeAsync(registered.InstallationId, issued.Code, Secrets.RandomToken()));
        Assert.Equal(404, wrongSecret.StatusCode);
    }

    [Fact]
    public async Task Expired_poll_returns_gone_without_assigning_a_license()
    {
        await using var scenario = await Scenario.CreateAsync();
        var registered = await scenario.RegisterAsync();
        var issued = await scenario.Service.RequestCodeAsync(registered.InstallationId);
        (await scenario.Db.ActivationCodes.SingleAsync()).ExpiresAt = DateTime.UtcNow.AddSeconds(-1);
        await scenario.Db.SaveChangesAsync();
        var expired = await Assert.ThrowsAsync<DomainException>(() => scenario.Service.PollCodeAsync(registered.InstallationId, issued.Code, issued.PollToken));
        Assert.Equal(410, expired.StatusCode);
        Assert.Equal("activation_expired", expired.Code);
        Assert.Null((await scenario.Db.Installations.SingleAsync()).LicenseId);
    }

    [Fact]
    public async Task Disabled_heartbeat_never_updates_presence()
    {
        await using var scenario = await Scenario.CreateAsync();
        var registered = await scenario.RegisterAsync();
        var installation = await scenario.Db.Installations.SingleAsync();
        installation.KioskMode = "always";
        installation.AllowLocalExit = false;
        await scenario.Db.SaveChangesAsync();
        await new AdminService(scenario.Db, scenario.Store.Hasher).RevokeInstallationAsync(registered.InstallationId);
        var before = (await scenario.Db.Installations.SingleAsync()).LastSeenAt;
        var denied = await scenario.Service.HeartbeatAsync(registered.InstallationId, Heartbeat());
        Assert.Equal("installation_disabled", denied.Decision.State);
        Assert.Null(denied.Lease);
        Assert.Equal(new V1DevicePolicy("off", true), denied.DevicePolicy);
        var safePolicy = JsonSerializer.SerializeToElement(denied, JsonOptions).GetProperty("devicePolicy");
        Assert.Equal("off", safePolicy.GetProperty("kioskMode").GetString());
        Assert.True(safePolicy.GetProperty("allowLocalExit").GetBoolean());
        Assert.Equal("always", installation.KioskMode);
        Assert.False(installation.AllowLocalExit);
        Assert.Equal(before, (await scenario.Db.Installations.SingleAsync()).LastSeenAt);
        Assert.Empty(await scenario.Db.DeviceSessions.ToListAsync());
    }

    [Fact]
    public async Task Heartbeat_returns_default_server_policy_for_an_unmanaged_installation()
    {
        await using var scenario = await Scenario.CreateAsync();
        var registered = await scenario.RegisterAsync();
        var heartbeat = await scenario.Service.HeartbeatAsync(registered.InstallationId, Heartbeat());
        Assert.Equal(new V1DevicePolicy("off", true), heartbeat.DevicePolicy);
        var response = JsonSerializer.SerializeToElement(heartbeat, JsonOptions);
        Assert.Equal("off", response.GetProperty("devicePolicy").GetProperty("kioskMode").GetString());
        Assert.True(response.GetProperty("devicePolicy").GetProperty("allowLocalExit").GetBoolean());
    }

    [Theory]
    [InlineData("off", false, false)]
    [InlineData("playback", true, false)]
    [InlineData("always", false, false)]
    [InlineData("always", true, true)]
    public async Task Heartbeat_returns_configured_policy_independently_of_managed_device_claim(
        string kioskMode, bool allowLocalExit, bool managedDevice)
    {
        await using var scenario = await Scenario.CreateAsync();
        var registered = await scenario.RegisterAsync();
        var installation = await scenario.Db.Installations.SingleAsync();
        installation.KioskMode = kioskMode;
        installation.AllowLocalExit = allowLocalExit;
        await scenario.Db.SaveChangesAsync();
        var heartbeat = await scenario.Service.HeartbeatAsync(registered.InstallationId,
            Heartbeat() with { ManagedDevice = managedDevice });
        Assert.Equal(new V1DevicePolicy(kioskMode, allowLocalExit), heartbeat.DevicePolicy);
    }

    [Fact]
    public async Task Stale_heartbeat_returns_reloaded_policy_without_advancing_presence()
    {
        await using var scenario = await Scenario.CreateAsync();
        var registered = await scenario.RegisterAsync();
        var request = Heartbeat() with { Sequence = 4 };
        await scenario.Service.HeartbeatAsync(registered.InstallationId, request);
        var presence = (await scenario.Db.Installations.SingleAsync()).LastSeenAt;
        await using (var administrator = scenario.Store.NewContext())
        {
            var installation = await administrator.Installations.SingleAsync();
            installation.KioskMode = "always";
            installation.AllowLocalExit = false;
            await administrator.SaveChangesAsync();
        }
        foreach (var sequence in new long[] { 4, 3 })
        {
            var replay = await scenario.Service.HeartbeatAsync(registered.InstallationId, request with { Sequence = sequence });
            Assert.Equal(new V1DevicePolicy("always", false), replay.DevicePolicy);
            Assert.Equal(presence, (await scenario.Db.Installations.SingleAsync()).LastSeenAt);
        }
        Assert.Equal(4, (await scenario.Db.Set<V1SessionState>().SingleAsync()).LastSequence);
    }

    [Fact]
    public async Task Diagnostics_inferred_session_accepts_first_heartbeat_sequence_zero_without_duplicate_session()
    {
        await using var scenario = await Scenario.CreateAsync();
        var registered = await scenario.RegisterAsync();
        var heartbeat = Heartbeat();
        var before = (await scenario.Db.Installations.SingleAsync()).LastSeenAt;
        var batch = JsonSerializer.SerializeToElement(new
        {
            schemaVersion = 1,
            events = new[] { new { eventId = Guid.NewGuid(), appSessionId = heartbeat.AppSessionId, sequence = 1,
                occurredAt = DateTime.UtcNow.AddMinutes(-1), kind = "app_started", payload = new { appVersionCode = 1, appVersionName = "1.0" } } }
        });
        var diagnostics = await new V1Diagnostics(scenario.Db).BatchAsync(registered.InstallationId, batch);
        Assert.Single(diagnostics.AcceptedEventIds);
        Assert.Equal(-1, (await scenario.Db.Set<V1SessionState>().SingleAsync()).LastSequence);
        Assert.Equal(before, (await scenario.Db.Installations.SingleAsync()).LastSeenAt);
        await scenario.Service.HeartbeatAsync(registered.InstallationId, heartbeat);
        Assert.Equal(0, (await scenario.Db.Set<V1SessionState>().SingleAsync()).LastSequence);
        Assert.Equal(1, await scenario.Db.DeviceSessions.CountAsync());
    }

    [Fact]
    public async Task Session_uuid_claimed_by_another_installation_cannot_be_reused()
    {
        await using var scenario = await Scenario.CreateAsync();
        var first = await scenario.RegisterAsync();
        var second = await scenario.RegisterAsync();
        var heartbeat = Heartbeat();
        await scenario.Service.HeartbeatAsync(first.InstallationId, heartbeat);
        var before = (await scenario.Db.Installations.SingleAsync(x => x.Id == second.InstallationId)).LastSeenAt;
        var conflict = await Assert.ThrowsAsync<DomainException>(() => scenario.Service.HeartbeatAsync(second.InstallationId, heartbeat));
        Assert.Equal(409, conflict.StatusCode);
        Assert.Equal("invalid_session", conflict.Code);
        scenario.Db.ChangeTracker.Clear();
        Assert.Equal(before, (await scenario.Db.Installations.SingleAsync(x => x.Id == second.InstallationId)).LastSeenAt);
        Assert.Equal(first.InstallationId, (await scenario.Db.Set<V1SessionState>().SingleAsync()).InstallationId);
        Assert.Equal(1, await scenario.Db.DeviceSessions.CountAsync());
    }

    private static V1RegisterRequest Registration() => new()
    {
        InstallationId = Guid.NewGuid(), Credential = Secrets.RandomToken(), AppVersionCode = 1, AppVersionName = "1.0",
        PackageName = "com.megastream.app", Channel = "stable", Manufacturer = "Test", Model = "TV",
        AndroidApi = 27, AndroidRelease = "8.1", Abi = "arm64_v8a", Locale = "en-US", ManagedDevice = false
    };

    private static V1HeartbeatRequest Heartbeat() => new()
    {
        AppSessionId = Guid.NewGuid(), Sequence = 0, Mode = "foreground", AppVersionCode = 1,
        AppVersionName = "1.0", PackageName = "com.megastream.app", Channel = "stable", ManagedDevice = false
    };

    private static V1Memory Memory() => new()
    {
        JavaUsedBytes = 100, JavaMaxBytes = 200, NativeHeapBytes = 50, PssBytes = 100,
        AvailableSystemBytes = 500, LowMemory = false
    };

    private sealed class Scenario : IAsyncDisposable
    {
        public TestDatabase Store { get; }
        public AppDbContext Db => Store.Db;
        public V1Service Service { get; }
        private readonly LeaseSigner signer = new(LeaseSignerTests.Configuration(), new TestEnvironment());
        private readonly string storagePath = Path.Combine(AppContext.BaseDirectory, "TestResults", "v1-" + Guid.NewGuid().ToString("N"));
        private readonly ApkStorage storage;

        private Scenario(TestDatabase store)
        {
            Store = store;
            storage = new ApkStorage(Options.Create(new UpdateOptions { PublicOrigin = "https://updates.example", StoragePath = storagePath }));
            Service = For(store.Db);
        }
        public static async Task<Scenario> CreateAsync() => new(await TestDatabase.CreateAsync());
        public V1Service For(AppDbContext db) => new(db, new DeviceService(db, signer, Store.Hasher), signer, Store.Hasher, new UpdateService(db, storage));
        public Task<V1RegisterResponse> RegisterAsync() => Service.RegisterAsync(Registration(), Guid.NewGuid().ToString("D"));
        public Task<CreatedLicense> LicenseAsync() => new AdminService(Db, Store.Hasher).CreateLicenseAsync("Test", DateTime.UtcNow.AddDays(-1), DateTime.UtcNow.AddDays(10), 1);
        public async ValueTask DisposeAsync()
        {
            await Store.DisposeAsync();
            signer.Dispose();
            Directory.Delete(storagePath, recursive: true);
        }
    }
}
