using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using MegaStream.Server.Data;
using MegaStream.Server.Models;
using MegaStream.Server.Services;
using MegaStream.Server.V1;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Xunit;

namespace MegaStream.Server.Tests;

public sealed class V1HttpTests
{
    [Fact]
    public async Task Anonymous_registration_replays_original_utc_timestamp_without_returning_credentials()
    {
        using var app = new TestApplication();
        using var client = CreateClient(app);
        var registration = Registration();
        var key = Guid.NewGuid().ToString("D");

        var first = await Register(client, registration, key);
        Assert.Equal(registration.InstallationId, first.GetProperty("installationId").GetGuid());
        var registeredAt = first.GetProperty("registeredAt").GetString()!;
        Assert.EndsWith("Z", registeredAt);
        Assert.Equal(DateTimeKind.Utc, first.GetProperty("registeredAt").GetDateTime().Kind);
        Assert.False(first.TryGetProperty("bearerToken", out _));
        Assert.False(first.TryGetProperty("credential", out _));

        foreach (var replayKey in new[] { key, Guid.NewGuid().ToString("D") })
        {
            var replay = await Register(client, registration, replayKey);
            Assert.Equal(registration.InstallationId, replay.GetProperty("installationId").GetGuid());
            Assert.Equal(registeredAt, replay.GetProperty("registeredAt").GetString());
            Assert.False(replay.TryGetProperty("bearerToken", out _));
            Assert.False(replay.TryGetProperty("credential", out _));
        }

        using var scope = app.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();
        var persisted = await db.Installations.AsNoTracking().SingleAsync();
        Assert.Equal(registration.InstallationId, persisted.Id);
        var hasher = scope.ServiceProvider.GetRequiredService<SecretHasher>();
        Assert.Equal(hasher.Hash("device", registration.Credential), persisted.TokenHash);
        Assert.NotEqual(registration.Credential, persisted.TokenHash);
    }

    [Theory]
    [InlineData(null)]
    [InlineData("not-a-uuid")]
    [InlineData("00000000-0000-0000-0000-000000000000")]
    public async Task Registration_requires_nonempty_uuid_idempotency_key(string? key)
    {
        using var app = new TestApplication();
        using var client = CreateClient(app);
        var registration = Registration();
        using var request = RegistrationMessage(registration, key);
        using var response = await client.SendAsync(request);
        await Problem(response, HttpStatusCode.BadRequest, registration.Credential);
        using var scope = app.Services.CreateScope();
        Assert.Empty(await scope.ServiceProvider.GetRequiredService<AppDbContext>().Installations.ToListAsync());
    }

    [Fact]
    public async Task Registration_rejects_forbidden_fingerprint_even_with_otherwise_valid_contract()
    {
        using var app = new TestApplication();
        using var client = CreateClient(app);
        var registration = Registration();
        var body = JsonSerializer.SerializeToNode(registration, new JsonSerializerOptions(JsonSerializerDefaults.Web))!.AsObject();
        body["fingerprint"] = Guid.NewGuid().ToString("D");
        using var request = RegistrationMessage(body, Guid.NewGuid().ToString("D"));
        using var response = await client.SendAsync(request);
        await Problem(response, HttpStatusCode.BadRequest, registration.Credential);
        using var scope = app.Services.CreateScope();
        Assert.Empty(await scope.ServiceProvider.GetRequiredService<AppDbContext>().Installations.ToListAsync());
    }

    [Fact]
    public async Task Registration_with_different_credential_conflicts_and_preserves_original_authentication()
    {
        using var app = new TestApplication();
        using var client = CreateClient(app);
        var registration = Registration();
        await Register(client, registration);
        var replacement = registration with { Credential = Secrets.RandomToken() };
        using var request = RegistrationMessage(replacement, Guid.NewGuid().ToString("D"));
        using var response = await client.SendAsync(request);
        var problem = await Problem(response, HttpStatusCode.Conflict, registration.Credential, replacement.Credential);
        Assert.Equal("installation_conflict", problem.GetProperty("code").GetString());

        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", replacement.Credential);
        using var rejected = await client.PostAsJsonAsync("/api/v1/devices/heartbeat", Heartbeat());
        await Problem(rejected, HttpStatusCode.Unauthorized, replacement.Credential);

        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", registration.Credential);
        using var accepted = await client.PostAsJsonAsync("/api/v1/devices/heartbeat", Heartbeat());
        AssertUnlicensed(await Json(accepted, HttpStatusCode.OK, registration.Credential));
    }

    [Fact]
    public async Task Raw_client_credential_authenticates_unlicensed_heartbeat_and_replay_does_not_advance_last_seen()
    {
        using var app = new TestApplication();
        using var client = CreateClient(app);
        var registration = Registration();
        await Register(client, registration);
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", registration.Credential);
        var heartbeat = Heartbeat();
        using var first = await client.PostAsJsonAsync("/api/v1/devices/heartbeat", heartbeat);
        var firstBody = await Json(first, HttpStatusCode.OK, registration.Credential);
        AssertUnlicensed(firstBody);
        var defaultPolicy = firstBody.GetProperty("devicePolicy");
        Assert.Equal("off", defaultPolicy.GetProperty("kioskMode").GetString());
        Assert.True(defaultPolicy.GetProperty("allowLocalExit").GetBoolean());

        // Persist a distinguishable timestamp after acceptance so the replay check cannot
        // pass merely because two requests happen within the clock's resolution.
        DateTime lastSeen;
        using (var scope = app.Services.CreateScope())
        {
            var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();
            var installation = await db.Installations.SingleAsync(x => x.Id == registration.InstallationId);
            lastSeen = installation.LastSeenAt.AddMinutes(-1);
            installation.LastSeenAt = lastSeen;
            await db.SaveChangesAsync();
        }

        using var replay = await client.PostAsJsonAsync("/api/v1/devices/heartbeat", heartbeat);
        AssertUnlicensed(await Json(replay, HttpStatusCode.OK, registration.Credential));
        using (var scope = app.Services.CreateScope())
        {
            var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();
            Assert.Equal(lastSeen, (await db.Installations.AsNoTracking().SingleAsync(x => x.Id == registration.InstallationId)).LastSeenAt);
        }

        using var next = await client.PostAsJsonAsync("/api/v1/devices/heartbeat", heartbeat with { Sequence = heartbeat.Sequence + 1 });
        AssertUnlicensed(await Json(next, HttpStatusCode.OK, registration.Credential));
        using var verify = app.Services.CreateScope();
        Assert.True((await verify.ServiceProvider.GetRequiredService<AppDbContext>().Installations.AsNoTracking()
            .SingleAsync(x => x.Id == registration.InstallationId)).LastSeenAt > lastSeen);
    }

    [Fact]
    public async Task Disabled_installation_receives_heartbeat_decision_but_cannot_request_activation_code()
    {
        using var app = new TestApplication();
        using var client = CreateClient(app);
        var registration = Registration();
        await Register(client, registration);
        DateTime lastSeen;
        using (var scope = app.Services.CreateScope())
        {
            var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();
            var installation = await db.Installations.SingleAsync(x => x.Id == registration.InstallationId);
            installation.Status = InstallationStatus.Revoked;
            installation.KioskMode = "always";
            installation.AllowLocalExit = false;
            lastSeen = installation.LastSeenAt.AddMinutes(-1);
            installation.LastSeenAt = lastSeen;
            await db.SaveChangesAsync();
        }
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", registration.Credential);

        using var heartbeat = await client.PostAsJsonAsync("/api/v1/devices/heartbeat", Heartbeat());
        var body = await Json(heartbeat, HttpStatusCode.OK, registration.Credential);
        Assert.Equal("installation_disabled", body.GetProperty("decision").GetProperty("state").GetString());
        Assert.False(body.TryGetProperty("lease", out _));
        Assert.False(body.TryGetProperty("updateCommand", out _));
        var safePolicy = body.GetProperty("devicePolicy");
        Assert.Equal(JsonValueKind.Object, safePolicy.ValueKind);
        Assert.Equal("off", safePolicy.GetProperty("kioskMode").GetString());
        Assert.True(safePolicy.GetProperty("allowLocalExit").GetBoolean());

        using var requestCode = await client.PostAsJsonAsync("/api/v1/activation-codes/request", new { });
        var problem = await Problem(requestCode, HttpStatusCode.Forbidden, registration.Credential);
        Assert.Equal("installation_disabled", problem.GetProperty("code").GetString());
        using var verify = app.Services.CreateScope();
        var persisted = verify.ServiceProvider.GetRequiredService<AppDbContext>();
        Assert.Equal(lastSeen, (await persisted.Installations.AsNoTracking()
            .SingleAsync(x => x.Id == registration.InstallationId)).LastSeenAt);
        Assert.Empty(await persisted.ActivationCodes.ToListAsync());
    }

    [Theory]
    [InlineData("off", false)]
    [InlineData("playback", true)]
    [InlineData("always", false)]
    public async Task Unmanaged_installation_heartbeat_returns_persisted_device_policy(string kioskMode, bool allowLocalExit)
    {
        using var app = new TestApplication();
        using var client = CreateClient(app);
        var registration = Registration();
        await Register(client, registration);
        using (var scope = app.Services.CreateScope())
        {
            var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();
            var installation = await db.Installations.SingleAsync(x => x.Id == registration.InstallationId);
            installation.KioskMode = kioskMode;
            installation.AllowLocalExit = allowLocalExit;
            await db.SaveChangesAsync();
        }
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", registration.Credential);
        using var response = await client.PostAsJsonAsync("/api/v1/devices/heartbeat", Heartbeat() with { ManagedDevice = false });
        var body = await Json(response, HttpStatusCode.OK, registration.Credential);
        AssertUnlicensed(body);
        var policy = body.GetProperty("devicePolicy");
        Assert.Equal(kioskMode, policy.GetProperty("kioskMode").GetString());
        Assert.Equal(allowLocalExit, policy.GetProperty("allowLocalExit").GetBoolean());
    }

    [Theory]
    [InlineData("devicePolicy")]
    [InlineData("kioskMode")]
    [InlineData("allowLocalExit")]
    public async Task Heartbeat_rejects_client_supplied_policy_without_mutating_server_policy(string property)
    {
        using var app = new TestApplication();
        using var client = CreateClient(app);
        var registration = Registration();
        await Register(client, registration);
        DateTime lastSeen;
        using (var scope = app.Services.CreateScope())
        {
            var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();
            var installation = await db.Installations.SingleAsync(x => x.Id == registration.InstallationId);
            installation.KioskMode = "always";
            installation.AllowLocalExit = false;
            lastSeen = installation.LastSeenAt.AddMinutes(-1);
            installation.LastSeenAt = lastSeen;
            await db.SaveChangesAsync();
        }
        var body = JsonSerializer.SerializeToNode(Heartbeat(), new JsonSerializerOptions(JsonSerializerDefaults.Web))!.AsObject();
        body[property] = property switch
        {
            "devicePolicy" => JsonSerializer.SerializeToNode(new { kioskMode = "off", allowLocalExit = true }),
            "kioskMode" => JsonSerializer.SerializeToNode("off"),
            _ => JsonSerializer.SerializeToNode(true)
        };
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", registration.Credential);
        using var response = await client.PostAsJsonAsync("/api/v1/devices/heartbeat", body);
        await Problem(response, HttpStatusCode.BadRequest, registration.Credential);
        using var verify = app.Services.CreateScope();
        var persisted = await verify.ServiceProvider.GetRequiredService<AppDbContext>().Installations.AsNoTracking()
            .SingleAsync(x => x.Id == registration.InstallationId);
        Assert.Equal("always", persisted.KioskMode);
        Assert.False(persisted.AllowLocalExit);
        Assert.Equal(lastSeen, persisted.LastSeenAt);
    }

    [Theory]
    [InlineData("/.well-known/offline-lease-keys")]
    [InlineData("/api/v1/public/signing-key")]
    public async Task Anonymous_signing_keys_are_public_jwks_without_private_key_material(string path)
    {
        using var app = new TestApplication();
        using var client = CreateClient(app);
        using var response = await client.GetAsync(path);
        var document = await Json(response, HttpStatusCode.OK);
        var key = Assert.Single(document.GetProperty("keys").EnumerateArray());
        Assert.Equal("EC", key.GetProperty("kty").GetString());
        Assert.Equal("P-256", key.GetProperty("crv").GetString());
        Assert.Equal("ES256", key.GetProperty("alg").GetString());
        Assert.False(string.IsNullOrWhiteSpace(key.GetProperty("kid").GetString()));
        Assert.False(string.IsNullOrWhiteSpace(key.GetProperty("x").GetString()));
        Assert.False(string.IsNullOrWhiteSpace(key.GetProperty("y").GetString()));
        Assert.False(key.TryGetProperty("d", out _));
    }

    [Fact]
    public async Task Activation_poll_accepts_post_body_and_returns_pending_without_echoing_secrets()
    {
        using var app = new TestApplication();
        using var client = CreateClient(app);
        var registration = Registration();
        await Register(client, registration);
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", registration.Credential);
        var poll = await RequestCode(client, registration.Credential);
        using var response = await client.PostAsJsonAsync("/api/v1/activation-codes/status", poll);
        var body = await Json(response, HttpStatusCode.OK, registration.Credential, poll.PollToken);
        Assert.Equal("pending", body.GetProperty("status").GetString());
        Assert.Equal(5, body.GetProperty("retryAfterSeconds").GetInt32());
        Assert.False(body.TryGetProperty("lease", out _));
    }

    [Theory]
    [InlineData("GET", "query", HttpStatusCode.MethodNotAllowed)]
    [InlineData("POST", "query", HttpStatusCode.BadRequest)]
    [InlineData("POST", "header", HttpStatusCode.BadRequest)]
    public async Task Activation_poll_does_not_accept_secrets_outside_post_json_body(string method, string location, HttpStatusCode expected)
    {
        using var app = new TestApplication();
        using var client = CreateClient(app);
        var registration = Registration();
        await Register(client, registration);
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", registration.Credential);
        var poll = await RequestCode(client, registration.Credential);
        var path = "/api/v1/activation-codes/status";
        if (location == "query") path += $"?code={Uri.EscapeDataString(poll.Code)}&pollToken={Uri.EscapeDataString(poll.PollToken)}";
        using var request = new HttpRequestMessage(new HttpMethod(method), path);
        if (method == "POST") request.Content = JsonContent.Create(new { });
        if (location == "header")
        {
            request.Headers.Add("X-Activation-Code", poll.Code);
            request.Headers.Add("X-Poll-Token", poll.PollToken);
        }
        using var response = await client.SendAsync(request);
        await Problem(response, expected, registration.Credential, poll.PollToken);
    }

    private static HttpClient CreateClient(TestApplication app) => app.CreateClient(new WebApplicationFactoryClientOptions
    {
        AllowAutoRedirect = false, BaseAddress = new Uri("https://localhost")
    });

    private static V1RegisterRequest Registration() => new()
    {
        InstallationId = Guid.NewGuid(), Credential = Secrets.RandomToken(), AppVersionCode = 1,
        AppVersionName = "1.0", PackageName = "com.megastream.app", Channel = "stable",
        Manufacturer = "Example", Model = "TV", AndroidApi = 34, AndroidRelease = "14",
        Abi = "arm64_v8a", Locale = "en-US", ManagedDevice = false
    };

    private static V1HeartbeatRequest Heartbeat() => new()
    {
        AppSessionId = Guid.NewGuid(), Sequence = 0, Mode = "foreground", AppVersionCode = 1,
        AppVersionName = "1.0", PackageName = "com.megastream.app", Channel = "stable", ManagedDevice = false
    };

    private static HttpRequestMessage RegistrationMessage<T>(T registration, string? key)
    {
        var request = new HttpRequestMessage(HttpMethod.Post, "/api/v1/installations/register") { Content = JsonContent.Create(registration) };
        if (key is not null) request.Headers.Add("Idempotency-Key", key);
        return request;
    }

    private static async Task<JsonElement> Register(HttpClient client, V1RegisterRequest registration, string? key = null)
    {
        using var request = RegistrationMessage(registration, key ?? Guid.NewGuid().ToString("D"));
        using var response = await client.SendAsync(request);
        return await Json(response, HttpStatusCode.OK, registration.Credential);
    }

    private static async Task<V1PollRequest> RequestCode(HttpClient client, string credential)
    {
        using var response = await client.PostAsJsonAsync("/api/v1/activation-codes/request", new { });
        var body = await Json(response, HttpStatusCode.OK, credential);
        return new V1PollRequest { Code = body.GetProperty("code").GetString()!, PollToken = body.GetProperty("pollToken").GetString()! };
    }

    private static void AssertUnlicensed(JsonElement body)
    {
        Assert.Equal(JsonValueKind.Object, body.GetProperty("devicePolicy").ValueKind);
        var decision = body.GetProperty("decision");
        Assert.Equal("unlicensed", decision.GetProperty("state").GetString());
        foreach (var property in new[] { "licenseId", "licenseRevision", "startsAt", "endsAt" })
            Assert.Equal(JsonValueKind.Null, decision.GetProperty(property).ValueKind);
        Assert.False(body.TryGetProperty("lease", out _));
        Assert.EndsWith("Z", body.GetProperty("serverTime").GetString()!);
        Assert.True(body.GetProperty("refreshAfterSeconds").GetInt32() > 0);
    }

    private static async Task<JsonElement> Problem(HttpResponseMessage response, HttpStatusCode expected, params string[] secrets)
    {
        var body = await Json(response, expected, secrets);
        Assert.Equal("application/problem+json", response.Content.Headers.ContentType?.MediaType);
        Assert.Equal((int)expected, body.GetProperty("status").GetInt32());
        foreach (var property in new[] { "title", "code", "traceId" })
            Assert.False(string.IsNullOrWhiteSpace(body.GetProperty(property).GetString()));
        return body;
    }

    private static async Task<JsonElement> Json(HttpResponseMessage response, HttpStatusCode expected, params string[] secrets)
    {
        Assert.Equal(expected, response.StatusCode);
        Assert.True(response.Headers.CacheControl?.NoStore);
        var text = await response.Content.ReadAsStringAsync();
        foreach (var secret in secrets) Assert.DoesNotContain(secret, text);
        using var document = JsonDocument.Parse(text);
        return document.RootElement.Clone();
    }
}
