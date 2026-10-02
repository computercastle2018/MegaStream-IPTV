using MegaStream.Server.RemoteProviders.Core;
using MegaStream.Server.Services;
using MegaStream.Server.Data;
using MegaStream.Server.Models;
using Microsoft.EntityFrameworkCore;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using System.ComponentModel.DataAnnotations;
using System.Text.Json;
using MegaStream.Server.Contracts;
using MegaStream.Server.Controllers;

namespace MegaStream.Server.RemoteProviders.Http;

[Route("admin/providers")]
[Authorize(Policy = "Admin")]
[AutoValidateAntiforgeryToken]
[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
[RequestSizeLimit(32768)]
[RequestFormLimits(ValueCountLimit = 40, KeyLengthLimit = 64, ValueLengthLimit = 16384, MultipartBodyLengthLimit = 32768)]
public sealed class ProvidersAdminController(IRemoteProviderService service, AppDbContext db, IConfiguration configuration) : Controller
{
    private const string Views = "~/RemoteProviders/Views/";

    [HttpGet("")]
    public async Task<IActionResult> Index(CancellationToken ct)
    {
        var now = DateTime.UtcNow;
        var window = AdminController.OnlineWindowMinutes(configuration);
        var cutoff = now.AddMinutes(-window);
        var devices = await db.Installations.AsNoTracking().Include(x => x.License)
            .Where(x => x.Status == InstallationStatus.Active).OrderByDescending(x => x.LastSeenAt)
            .ThenBy(x => x.Id).Take(500).ToListAsync(ct);
        var subscriptions = new List<ReportedSubscription>();
        var invalidReports = false;
        foreach (var device in devices.Where(x => x.LastSeenAt >= cutoff && x.LocalSubscriptionsReportedAt >= cutoff && x.LocalSubscriptionsJson != null))
        {
            try { subscriptions.AddRange(ActiveSubscriptions(device, now)); }
            catch (JsonException) { invalidReports = true; }
        }
        return View(Views + "Index.cshtml", new ProviderIndex(await service.ListProfilesAsync(ct),
            devices.Select(x => new ProviderDevice(x.Id, Sanitizer.CleanDiagnostic(x.DeviceModel, 128))).ToList(), subscriptions, window, invalidReports));
    }

    private static IReadOnlyList<ReportedSubscription> ActiveSubscriptions(Installation device, DateTime now)
    {
        var snapshot = JsonSerializer.Deserialize<List<LocalSubscription?>>(device.LocalSubscriptionsJson!)
            ?? throw new JsonException();
        if (snapshot.Count > 100 || snapshot.Any(x => x is null || !Validator.TryValidateObject(x, new ValidationContext(x), null, true)))
            throw new JsonException();
        return snapshot.Where(x => x!.Enabled && x.Status == "active" &&
                (!x.ExpiresAt.HasValue || x.ExpiresAt > new DateTimeOffset(now).ToUnixTimeMilliseconds()))
            .Select(x => new ReportedSubscription(device.Id, Sanitizer.CleanDiagnostic(device.DeviceModel, 128),
                device.LicenseId, Sanitizer.CleanDiagnostic(device.License?.Label, 128), device.LocalSubscriptionsReportedAt!.Value,
                Sanitizer.CleanDiagnostic(x!.Name, 128), x.Type, x.StartedAt, x.ExpiresAt, x.MaxConnections)).ToList();
    }

    [HttpGet("installations")]
    public async Task<IActionResult> SelectDevice([FromQuery] Guid installationId, CancellationToken ct)
    {
        if (!ModelState.IsValid || !await db.Installations.AnyAsync(x => x.Id == installationId && x.Status == InstallationStatus.Active, ct))
            return BadRequest();
        return RedirectToAction(nameof(Assignments), new { installationId });
    }

    [HttpGet("create")]
    public async Task<IActionResult> Create([FromQuery] string? type, CancellationToken ct) =>
        View(Views + "Editor.cshtml", await NewEditor(SafeType(type), false, ct));

    [HttpGet("{profileId:guid}")]
    public async Task<IActionResult> Details([FromRoute] Guid profileId, CancellationToken ct)
    {
        var profile = await service.GetProfileAsync(profileId, ct);
        return View(Views + "Editor.cshtml", new ProviderEditor(profile.Id, profile.Type, profile, false));
    }

    // No posted fields are MVC action parameters: configuration must never enter ModelState.
    [HttpPost("create")]
    public Task<IActionResult> CreatePost(CancellationToken ct) => Save(null, ct);

    [HttpPost("{profileId:guid}")]
    public Task<IActionResult> EditPost([FromRoute] Guid profileId, CancellationToken ct) => Save(profileId, ct);

    private async Task<IActionResult> Save(Guid? profileId, CancellationToken ct)
    {
        ModelState.Clear();
        try
        {
            var form = await ReadForm(ct);
            var request = ProviderFormParser.Parse(form);
            var target = profileId.HasValue ? null : ProviderFormParser.Optional(form, "installationId", 36);
            Guid? installationId = target is null ? null : Guid.ParseExact(target, "D");
            var policy = installationId.HasValue ? ProviderFormParser.Field(form, "assignmentPolicy", 16) : "auto_enabled";
            var saved = profileId.HasValue
                ? await service.UpdateProfileAsync(profileId.Value, request, ct)
                : await service.CreateProfileAsync(request, ct, installationId, policy);
            if (installationId.HasValue)
                return RedirectToAction(nameof(Assignments), new { installationId });
            return RedirectToAction(nameof(Details), new { profileId = saved.Id });
        }
        catch (Exception error) when (error is DomainException { StatusCode: 400 } or
            DomainException { Code: "invalid_device" or "installation_disabled" } or InvalidDataException or BadHttpRequestException or FormatException)
        {
            ModelState.Clear();
            // Never use the posted configuration, exception text, or attempted values in a view.
            Response.StatusCode = 400;
            return View(Views + "Editor.cshtml", profileId.HasValue
                ? new ProviderEditor(profileId, RemoteProviderValues.XtreamCodes, null, true)
                : await NewEditor(RemoteProviderValues.XtreamCodes, true, ct));
        }
    }

    [HttpPost("{profileId:guid}/revoke")]
    public async Task<IActionResult> RevokeProfile([FromRoute] Guid profileId, CancellationToken ct)
    {
        ModelState.Clear();
        await service.RevokeProfileAsync(profileId, ct);
        return RedirectToAction(nameof(Index));
    }

    [HttpGet("installations/{installationId:guid}")]
    public async Task<IActionResult> Assignments([FromRoute] Guid installationId, CancellationToken ct) =>
        View(Views + "Assignments.cshtml", new ProviderAssignments(installationId,
            await service.ListProfilesAsync(ct), await service.ListAssignmentsAsync(installationId, ct),
            await service.ListReportsAsync(installationId, ct)));

    [HttpPost("installations/{installationId:guid}/assign")]
    public async Task<IActionResult> Assign([FromRoute] Guid installationId, CancellationToken ct)
    {
        ModelState.Clear();
        try
        {
            var form = await ReadForm(ct);
            var profileId = Guid.Parse(ProviderFormParser.Field(form, "profileId", 36));
            var policy = ProviderFormParser.Field(form, "policy", 16);
            var enabled = ProviderFormParser.Field(form, "enabled", 5);
            if (profileId == Guid.Empty || policy is not ("optional" or "auto_enabled" or "required") || enabled is not ("true" or "false"))
                throw new FormatException();
            await service.AssignAsync(profileId, installationId, new AssignRemoteProvider { Policy = policy, Enabled = enabled == "true" }, ct);
            return RedirectToAction(nameof(Assignments), new { installationId });
        }
        catch (Exception error) when (error is InvalidDataException or BadHttpRequestException or FormatException)
        {
            ModelState.Clear();
            throw new DomainException(400, "invalid_provider_assignment", "Assignment fields are missing or invalid.");
        }
    }

    [HttpPost("assignments/{assignmentId:guid}/revoke")]
    public async Task<IActionResult> RevokeAssignment([FromRoute] Guid assignmentId, CancellationToken ct)
    {
        ModelState.Clear();
        await service.RevokeAssignmentAsync(assignmentId, ct);
        return RedirectToAction(nameof(Index));
    }

    private async Task<IFormCollection> ReadForm(CancellationToken ct)
    {
        if (!Request.HasFormContentType) throw new FormatException();
        var form = await Request.ReadFormAsync(ct);
        ModelState.Clear();
        if (form.Files.Count != 0) throw new FormatException();
        return form;
    }

    private static string SafeType(string? type) => type is RemoteProviderValues.M3u or RemoteProviderValues.StalkerPortal
        ? type : RemoteProviderValues.XtreamCodes;

    private async Task<ProviderEditor> NewEditor(string type, bool hasError, CancellationToken ct) =>
        new(null, type, null, hasError)
        {
            Devices = await db.Installations.AsNoTracking().Where(x => x.Status == InstallationStatus.Active)
                .OrderBy(x => x.DeviceModel).Select(x => new ProviderDevice(x.Id, x.DeviceModel)).ToListAsync(ct)
        };
}

public sealed record ProviderEditor(Guid? ProfileId, string Type, RemoteProviderProfileMetadata? Metadata, bool HasError)
{
    public IReadOnlyList<ProviderDevice> Devices { get; init; } = Array.Empty<ProviderDevice>();
}
public sealed record ProviderDevice(Guid Id, string DeviceModel);
public sealed record ProviderIndex(IReadOnlyList<RemoteProviderProfileMetadata> Profiles, IReadOnlyList<ProviderDevice> Devices,
    IReadOnlyList<ReportedSubscription> Subscriptions, int OnlineWindowMinutes, bool HasInvalidReports);
public sealed record ReportedSubscription(Guid InstallationId, string DeviceModel, Guid? LicenseId, string LicenseLabel,
    DateTime ReportedAt, string Name, string Type, long? StartedAt, long? ExpiresAt, int MaxConnections);
public sealed record ProviderAssignments(Guid InstallationId, IReadOnlyList<RemoteProviderProfileMetadata> Profiles,
    IReadOnlyList<RemoteProviderAssignment> Assignments, IReadOnlyList<ProviderAssignmentReport> Reports);
