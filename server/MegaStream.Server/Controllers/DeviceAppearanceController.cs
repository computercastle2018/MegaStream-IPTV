using System.ComponentModel.DataAnnotations;
using System.Security.Claims;
using System.Text.Json.Serialization;
using MegaStream.Server.Data;
using MegaStream.Server.Services;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;

namespace MegaStream.Server.Controllers;

[ApiController]
[Route("api/v1/devices/appearance")]
[Authorize(AuthenticationSchemes = DeviceAuthenticationHandler.SchemeName)]
[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
[RequestSizeLimit(1024)]
public sealed class DeviceAppearanceController(AppDbContext db) : ControllerBase
{
    [HttpPost]
    public async Task<IActionResult> Set([FromBody] DeviceAppearanceRequest request, CancellationToken ct)
    {
        if (!Guid.TryParse(User.FindFirstValue(ClaimTypes.NameIdentifier), out var id)) return Unauthorized();
        if (Request.Query.Count != 0)
            throw new DomainException(400, "invalid_request", "Appearance changes accept only a JSON body.");
        await using var transaction = await db.Database.BeginTransactionAsync(DomainRules.Isolation(db), ct);
        await DomainRules.LockDevice(db, id, ct);
        var device = await DomainRules.Device(db, id, ct);
        var effectiveStyle = device.UiStyle;
        if (effectiveStyle is null && device.LicenseId is { } licenseId)
        {
            await DomainRules.LockLicense(db, licenseId, ct);
            effectiveStyle = await db.Licenses.AsNoTracking().Where(x => x.Id == licenseId).Select(x => x.UiStyle).SingleOrDefaultAsync(ct);
        }
        if (!string.Equals(request.ExpectedUiStyle, effectiveStyle, StringComparison.Ordinal))
            throw new DomainException(409, "appearance_conflict", "Appearance has changed; refresh before retrying.");
        device.UiStyle = request.UiStyle;
        await db.SaveChangesAsync(ct);
        await transaction.CommitAsync(ct);
        return NoContent();
    }
}

[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed class DeviceAppearanceRequest
{
    [Required, RegularExpression("classic|modern|studio")]
    public string UiStyle { get; init; } = "";

    [JsonRequired]
    public string? ExpectedUiStyle { get; init; }
}
