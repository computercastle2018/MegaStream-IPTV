using System.ComponentModel.DataAnnotations;
using System.Security.Claims;
using System.Text.Json.Serialization;
using MegaStream.Server.RemoteProviders.Core;
using MegaStream.Server.Services;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace MegaStream.Server.RemoteProviders.Http;

[ApiController]
[Route("api/v1/providers/assignments")]
[Authorize(AuthenticationSchemes = DeviceAuthenticationHandler.SchemeName)]
[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
[RequestSizeLimit(8192)]
public sealed class DeviceProvidersController(IRemoteProviderService service) : ControllerBase
{
    [HttpGet]
    public async Task<IActionResult> Get([FromQuery, Range(0, long.MaxValue)] long afterRevision = 0, CancellationToken ct = default) =>
        Ok(ProviderWireResponse.From(await service.GetDeviceAsync(InstallationId(), afterRevision, ct)));

    [HttpPost("{assignmentId:guid}/status")]
    public async Task<IActionResult> Status([FromRoute] Guid assignmentId, [FromBody] ProviderStatusBody body, CancellationToken ct)
    {
        await service.ReportAsync(InstallationId(), assignmentId, new ReportRemoteProvider
        {
            ProfileRevision = body.ProfileRevision, State = body.State, SafeErrorCode = body.SafeErrorCode,
            ProviderReportedExpiresAt = body.ProviderReportedExpiresAt,
            ProviderReportedMaxConnections = body.ProviderReportedMaxConnections
        }, ct);
        return NoContent();
    }

    private Guid InstallationId()
    {
        if (!Guid.TryParse(User.FindFirstValue(ClaimTypes.NameIdentifier), out var id) || id == Guid.Empty)
            throw new DomainException(401, "unauthorized", "Device authentication required.");
        return id;
    }
}

[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed class ProviderStatusBody
{
    [Range(1, long.MaxValue)] public long ProfileRevision { get; set; }
    [Required, MaxLength(32)] public string State { get; set; } = "";
    [MaxLength(64)] public string? SafeErrorCode { get; set; }
    public DateTime? ProviderReportedExpiresAt { get; set; }
    [Range(0, 100000)] public int? ProviderReportedMaxConnections { get; set; }
}
