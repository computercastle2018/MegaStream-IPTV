using System.Security.Claims;
using System.Text.Json;
using System.Text.Json.Serialization;
using MegaStream.Server.Services;
using MegaStream.Server.Updates;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace MegaStream.Server.V1;

[ApiController]
[Route("api/v1")]
[Authorize(AuthenticationSchemes = DeviceAuthenticationHandler.SchemeName)]
public sealed class V1Controller(V1Service service, V1Diagnostics diagnostics, UpdateService updates, LeaseSigner signer) : ControllerBase
{
    [AllowAnonymous]
    [HttpPost("installations/register")]
    public async Task<IActionResult> Register([FromBody] V1RegisterRequest request, CancellationToken ct)
    {
        var keys = Request.Headers["Idempotency-Key"];
        if (keys.Count != 1 || !Guid.TryParseExact(keys[0], "D", out var key) || key == Guid.Empty)
            throw new DomainException(400, "invalid_idempotency_key", "A UUID Idempotency-Key is required.");
        return Ok(await service.RegisterAsync(request, keys[0]!, ct));
    }

    [HttpPost("licenses/activate")]
    public async Task<IActionResult> Activate([FromBody] V1ActivateRequest request, CancellationToken ct) =>
        Ok(await service.ActivateAsync(InstallationId(), request.LicenseKey, ct));

    [HttpPost("activation-codes/request")]
    public async Task<IActionResult> RequestCode([FromBody] V1EmptyBody request, CancellationToken ct) =>
        Ok(await service.RequestCodeAsync(InstallationId(), ct));

    [HttpPost("activation-codes/status")]
    public async Task<IActionResult> PollCode([FromBody] V1PollRequest request, CancellationToken ct) =>
        Ok(await service.PollCodeAsync(InstallationId(), request.Code, request.PollToken, ct));

    [HttpPost("devices/heartbeat")]
    public async Task<IActionResult> Heartbeat([FromBody] V1HeartbeatRequest request, CancellationToken ct)
    {
        var installationId = InstallationId();
        var entitlement = await service.HeartbeatAsync(installationId, request, ct);
        var update = entitlement.Decision.State == "installation_disabled"
            ? null : await updates.GetPendingAsync(installationId, ct);
        return Ok(new V1HeartbeatResponse(entitlement.Decision, entitlement.ServerTime, entitlement.RefreshAfterSeconds,
            entitlement.Lease, update, entitlement.DevicePolicy));
    }

    [HttpPost("diagnostics/batch")]
    [RequestSizeLimit(262144)]
    public async Task<IActionResult> Diagnostics([FromBody] JsonElement body, CancellationToken ct) =>
        Ok(await diagnostics.BatchAsync(InstallationId(), body, ct));

    [AllowAnonymous]
    [HttpGet("/.well-known/offline-lease-keys")]
    [HttpGet("public/signing-key")]
    public IActionResult SigningKeys() => Ok(new { keys = new[] { signer.PublicJwk } });

    private Guid InstallationId()
    {
        if (!Guid.TryParse(User.FindFirstValue(ClaimTypes.NameIdentifier), out var installationId) || installationId == Guid.Empty)
            throw new DomainException(401, "unauthorized", "Device authentication required.");
        return installationId;
    }
}

[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed record V1EmptyBody;

public sealed record V1HeartbeatResponse(V1Decision Decision, DateTime ServerTime, int RefreshAfterSeconds,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] string? Lease,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] PendingUpdate? UpdateCommand,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] V1DevicePolicy? DevicePolicy = null);
