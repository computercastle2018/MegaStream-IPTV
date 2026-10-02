using System.Security.Claims;
using System.Text.Json;
using MegaStream.Server.Contracts;
using MegaStream.Server.Data;
using MegaStream.Server.Models;
using MegaStream.Server.Services;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using MegaStream.Server.RemoteProviders.Core;

namespace MegaStream.Server.Controllers;

[ApiController]
[Route("api/v1/devices/subscriptions")]
[Authorize(AuthenticationSchemes = DeviceAuthenticationHandler.SchemeName)]
[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
[RequestSizeLimit(65536)]
public sealed class LocalSubscriptionsController(AppDbContext db, LocalSubscriptionCredentialsStore credentials) : ControllerBase
{
    [HttpPost]
    public async Task<IActionResult> Report(LocalSubscriptionsRequest request, CancellationToken ct)
    {
        if (!Guid.TryParse(User.FindFirstValue(ClaimTypes.NameIdentifier), out var id)) return Unauthorized();
        if (request.Subscriptions.Any(x => x is null))
            throw new DomainException(400, "invalid_subscriptions", "Subscription entries must not be null.");
        if (request.Subscriptions.Select(x => x.LocalId).Distinct().Count() != request.Subscriptions.Count)
            throw new DomainException(400, "invalid_subscriptions", "Duplicate local subscription IDs.");
        foreach (var subscription in request.Subscriptions)
            subscription.Name = Sanitizer.CleanDiagnostic(subscription.Name, 128);
        // Replace the complete snapshot, including removals, without changing licensing or presence.
        var json = credentials.Encode(id, request.Subscriptions);
        var updated = await db.Installations.Where(x => x.Id == id && x.Status == InstallationStatus.Active)
            .ExecuteUpdateAsync(set => set.SetProperty(x => x.LocalSubscriptionsJson, json)
                .SetProperty(x => x.LocalSubscriptionsReportedAt, DateTime.UtcNow), ct);
        if (updated != 1) throw new DomainException(403, "installation_disabled", "Installation is disabled.");
        return NoContent();
    }
}
