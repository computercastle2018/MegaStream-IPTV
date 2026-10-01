using MegaStream.Server.RemoteProviders.Core;
using MegaStream.Server.Services;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace MegaStream.Server.RemoteProviders.Http;

[Route("admin/providers")]
[Authorize(Policy = "Admin")]
[AutoValidateAntiforgeryToken]
[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
[RequestSizeLimit(32768)]
[RequestFormLimits(ValueCountLimit = 40, KeyLengthLimit = 64, ValueLengthLimit = 16384, MultipartBodyLengthLimit = 32768)]
public sealed class ProvidersAdminController(IRemoteProviderService service) : Controller
{
    private const string Views = "~/RemoteProviders/Views/";

    [HttpGet("")]
    public async Task<IActionResult> Index(CancellationToken ct) => View(Views + "Index.cshtml", await service.ListProfilesAsync(ct));

    [HttpGet("create")]
    public IActionResult Create([FromQuery] string? type) => View(Views + "Editor.cshtml", new ProviderEditor(null, SafeType(type), null, false));

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
            var saved = profileId.HasValue
                ? await service.UpdateProfileAsync(profileId.Value, request, ct)
                : await service.CreateProfileAsync(request, ct);
            return RedirectToAction(nameof(Details), new { profileId = saved.Id });
        }
        catch (Exception error) when (error is DomainException { StatusCode: 400 } or InvalidDataException or BadHttpRequestException or FormatException)
        {
            ModelState.Clear();
            // Never use the posted configuration, exception text, or attempted values in a view.
            Response.StatusCode = 400;
            return View(Views + "Editor.cshtml", new ProviderEditor(profileId, RemoteProviderValues.XtreamCodes, null, true));
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
}

public sealed record ProviderEditor(Guid? ProfileId, string Type, RemoteProviderProfileMetadata? Metadata, bool HasError);
public sealed record ProviderAssignments(Guid InstallationId, IReadOnlyList<RemoteProviderProfileMetadata> Profiles,
    IReadOnlyList<RemoteProviderAssignment> Assignments, IReadOnlyList<ProviderAssignmentReport> Reports);
