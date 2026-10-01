using System.ComponentModel.DataAnnotations;
using System.Security.Claims;
using MegaStream.Server.Services;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace MegaStream.Server.Updates;

[ApiController]
[Route("api/v1/updates")]
[Authorize(AuthenticationSchemes = DeviceAuthenticationHandler.SchemeName)]
public sealed class DeviceUpdatesController(UpdateService service) : ControllerBase
{
    [HttpPost("check")]
    public async Task<IActionResult> Check([FromBody] UpdateCheckRequest request, CancellationToken ct)
    {
        if (!TryInstallationId(out var installationId)) return Unauthorized();
        return Ok(await service.CheckAsync(installationId, request, ct));
    }

    [HttpGet("pending")]
    public async Task<IActionResult> Pending(CancellationToken ct)
    {
        if (!TryInstallationId(out var installationId)) return Unauthorized();
        return Ok(await service.GetPendingAsync(installationId, ct));
    }

    [HttpPost("commands/{commandId:guid}/status")]
    public async Task<IActionResult> Report(Guid commandId, [FromBody] UpdateStatusRequest request, CancellationToken ct)
    {
        if (!TryInstallationId(out var installationId)) return Unauthorized();
        await service.ReportAsync(installationId, commandId, request.Status, request.ErrorCode, ct);
        return NoContent();
    }

    private bool TryInstallationId(out Guid installationId) =>
        Guid.TryParse(User.FindFirstValue(ClaimTypes.NameIdentifier), out installationId) && installationId != Guid.Empty;
}

[ApiController]
[Route("api/v1/public/updates")]
public sealed class PublicUpdatesController(UpdateService service, ApkStorage storage) : ControllerBase
{
    [AllowAnonymous]
    [HttpGet("/updates/files/{releaseId:guid}/release.apk")]
    public async Task<IActionResult> Download(Guid releaseId, CancellationToken ct)
    {
        var release = await service.GetPublishedAsync(releaseId, ct);
        var stream = storage.OpenRead(release.StorageKey);
        Response.Headers.CacheControl = "public,max-age=31536000,immutable";
        Response.Headers.Remove("Pragma");
        return File(stream, "application/vnd.android.package-archive", $"release-{release.VersionCode}.apk",
            lastModified: null, entityTag: new Microsoft.Net.Http.Headers.EntityTagHeaderValue($"\"{release.Sha256}\""),
            enableRangeProcessing: true);
    }

    [AllowAnonymous]
    [HttpGet("latest")]
    public async Task<IActionResult> Latest([FromQuery] string channel, [FromQuery] string abi, CancellationToken ct)
    {
        if (channel is not ("stable" or "beta"))
            throw new DomainException(400, "invalid_channel", "Channel must be stable or beta.");
        if (abi is not ("arm64_v8a" or "armeabi_v7a" or "x86_64" or "x86" or "other"))
            throw new DomainException(400, "invalid_abi", "A supported ABI is required.");
        var manifest = await service.GetLatestAsync(channel, abi, ct);
        return manifest is null ? NoContent() : Ok(manifest);
    }
}

public sealed record UpdateStatusRequest([Required] string Status, [MaxLength(128)] string? ErrorCode);

[Route("admin/updates")]
[Authorize(Policy = "Admin")]
[AutoValidateAntiforgeryToken]
public sealed class UpdatesAdminController(UpdateService service) : Controller
{
    [HttpGet("")]
    public async Task<IActionResult> Index(CancellationToken ct)
    {
        var releases = await service.ListAsync(ct);
        return View("~/Updates/Views/Index.cshtml", releases.Select(UpdateReleaseSummary.From).ToArray());
    }

    [HttpPost("upload")]
    [RequestSizeLimit(210763776)]
    [RequestFormLimits(MultipartBodyLengthLimit = 209715200)]
    public async Task<IActionResult> Upload([FromForm] UpdateUploadForm form, CancellationToken ct)
    {
        if (!ModelState.IsValid) return BadRequest("Release metadata is missing or invalid.");
        if (form.Apk is null || form.Apk.Length == 0 || form.Apk.Length > 209715200)
            return BadRequest("An APK of at most 200 MiB is required.");

        await using var content = form.Apk.OpenReadStream();
        await service.UploadAsync(new ReleaseUpload(form.VersionCode, form.VersionName, form.Channel,
            form.PackageName, form.MinSdk, form.Mandatory, form.SigningCertificateSha256, form.Notes, Abi: form.Abi),
            form.Apk.FileName, content, ct);
        return RedirectToAction(nameof(Index));
    }

    [HttpPost("releases/{releaseId:guid}/publish")]
    public async Task<IActionResult> Publish(Guid releaseId, CancellationToken ct)
    {
        await service.PublishAsync(releaseId, ct);
        return RedirectToAction(nameof(Index));
    }

    [HttpPost("send")]
    public async Task<IActionResult> Send([FromForm] UpdateSendForm form, CancellationToken ct)
    {
        if (!ModelState.IsValid || form.ReleaseId == Guid.Empty)
            return BadRequest("A release and installation IDs are required.");
        if (form.Mode is not ("prompt" or "managed"))
            return BadRequest("Mode must be prompt or managed.");

        var installationIds = new List<Guid>();
        foreach (var value in form.InstallationIds.Split(new[] { ',', ';', ' ', '\r', '\n', '\t' }, StringSplitOptions.RemoveEmptyEntries))
        {
            if (!Guid.TryParse(value, out var id) || id == Guid.Empty)
                return BadRequest("Each installation ID must be a non-empty GUID.");
            installationIds.Add(id);
        }
        if (installationIds.Count == 0) return BadRequest("At least one installation ID is required.");
        await service.SendAsync(form.ReleaseId, installationIds.Distinct().ToArray(), form.Mode, ct);
        return RedirectToAction(nameof(Index));
    }

    [HttpGet("releases/{releaseId:guid}/outdated")]
    public async Task<IActionResult> Outdated(Guid releaseId, CancellationToken ct) =>
        Ok(await service.OutdatedAsync(releaseId, ct));
}

public sealed class UpdateUploadForm
{
    [Required] public IFormFile? Apk { get; set; }
    [Range(1, long.MaxValue)] public long VersionCode { get; set; }
    [Required] public string VersionName { get; set; } = "";
    [Required] public string Channel { get; set; } = "stable";
    [Required, RegularExpression("^(com\\.megastream\\.app|com\\.megastream\\.app\\.beta)$")]
    public string PackageName { get; set; } = "com.megastream.app";
    [Required, RegularExpression("^(universal|arm64_v8a|armeabi_v7a|x86_64|x86)$")]
    public string Abi { get; set; } = "universal";
    [Range(1, int.MaxValue)] public int MinSdk { get; set; }
    public bool Mandatory { get; set; }
    public string? SigningCertificateSha256 { get; set; }
    public string? Notes { get; set; }
}

public sealed class UpdateSendForm
{
    public Guid ReleaseId { get; set; }
    [Required] public string InstallationIds { get; set; } = "";
    [Required] public string Mode { get; set; } = "prompt";
}

// Deliberately excludes the internal storage key and entity navigations.
public sealed record UpdateReleaseSummary(Guid Id, long VersionCode, string VersionName, string Channel,
    string PackageName, string Abi, int MinSdk, bool Mandatory, long SizeBytes, string Sha256,
    string? SigningCertificateSha256, string? Notes, string Status)
{
    public static UpdateReleaseSummary From(UpdateRelease release) => new(release.Id, release.VersionCode,
        release.VersionName, release.Channel, release.PackageName, release.Abi, release.MinSdk, release.Mandatory,
        release.SizeBytes, release.Sha256, release.SigningCertificateSha256, release.Notes, release.Status.ToString());
}
