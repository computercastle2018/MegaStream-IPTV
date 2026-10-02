using System.Globalization;
using System.Security.Claims;
using MegaStream.Server.Contracts;
using MegaStream.Server.Data;
using MegaStream.Server.Models;
using MegaStream.Server.Services;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;

namespace MegaStream.Server.Controllers;

[ApiController]
[Route("api/v1/devices/experience")]
[Authorize(AuthenticationSchemes = DeviceAuthenticationHandler.SchemeName)]
[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
[RequestSizeLimit(1024)]
public sealed class DeviceExperienceController(AppDbContext db) : ControllerBase
{
    [HttpGet]
    public async Task<IActionResult> Get(CancellationToken ct)
    {
        if (!Guid.TryParse(User.FindFirstValue(ClaimTypes.NameIdentifier), out var id)) return Unauthorized();
        var device = await db.Installations.AsNoTracking().SingleOrDefaultAsync(x => x.Id == id && x.Status == InstallationStatus.Active, ct);
        if (device is null) throw Disabled();
        var now = DateTime.UtcNow;
        var notifications = await db.AdminNotifications.AsNoTracking()
            .Where(x => (x.TargetInstallationId == null || x.TargetInstallationId == id) &&
                x.CreatedAt <= now && (x.ExpiresAt == null || x.ExpiresAt > now))
            .OrderByDescending(x => x.CreatedAt).ThenByDescending(x => x.Id).Take(50).ToListAsync(ct);
        return Ok(new DeviceExperienceResponse(device.AllowSubscriptionDetails, notifications.Select(x =>
            new DeviceNotificationResponse(x.Id.ToString("D"), Sanitizer.CleanDiagnostic(x.Title, 128),
                Sanitizer.CleanDiagnostic(x.Message, 2000), DateTime.SpecifyKind(x.CreatedAt, DateTimeKind.Utc).ToString("O", CultureInfo.InvariantCulture))).ToList(), device.MacAddress));
    }

    [HttpPost]
    public async Task<IActionResult> Report(DeviceExperienceRequest request, CancellationToken ct)
    {
        if (!Guid.TryParse(User.FindFirstValue(ClaimTypes.NameIdentifier), out var id)) return Unauthorized();
        var mac = request.MacAddress?.Replace('-', ':').ToUpperInvariant();
        if (mac is not null && (mac.Length != 17 || mac == "00:00:00:00:00:00" || mac == "02:00:00:00:00:00" ||
            (Convert.ToByte(mac[..2], 16) & 1) != 0))
            throw new DomainException(400, "invalid_mac", "Report a valid unicast MAC address or null.");
        var active = db.Installations.Where(x => x.Id == id && x.Status == InstallationStatus.Active);
        if (mac is null)
        {
            if (!await active.AnyAsync(ct)) throw Disabled();
        }
        else if (await active.ExecuteUpdateAsync(set => set.SetProperty(x => x.MacAddress, mac), ct) != 1) throw Disabled();
        return NoContent();
    }

    private static DomainException Disabled() => new(403, "installation_disabled", "Installation is disabled.");
}
