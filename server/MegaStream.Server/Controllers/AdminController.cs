using MegaStream.Server.Data;
using MegaStream.Server.Models;
using MegaStream.Server.Models.Admin;
using MegaStream.Server.Services;
using Microsoft.AspNetCore.Authorization;
using System.Security.Claims;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;

namespace MegaStream.Server.Controllers;

[Authorize(Policy = "Admin")]
[AutoValidateAntiforgeryToken]
[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
public sealed class AdminController(AppDbContext db, IAdminService admin, IConfiguration configuration) : Controller
{
    public static int OnlineWindowMinutes(IConfiguration config) => int.TryParse(config["ONLINE_WINDOW_MINUTES"], out var minutes) && minutes > 0 && minutes <= 525600 ? minutes : 10;
    private DateTime OnlineCutoff => DateTime.UtcNow.AddMinutes(-OnlineWindowMinutes(configuration));

    [HttpGet]
    public async Task<IActionResult> Index(CancellationToken ct)
    {
        var cutoff = OnlineCutoff;
        var incidents = db.DiagnosticEvents.AsNoTracking().Where(x => x.Level == "error" || x.Level == "fatal" || x.Level == "critical" || x.Category == "crash");
        return View(new DashboardViewModel(await db.Licenses.CountAsync(ct), await db.Installations.CountAsync(ct),
            await db.Installations.CountAsync(x => x.Status == InstallationStatus.Active && x.LastSeenAt >= cutoff, ct),
            await incidents.CountAsync(ct), OnlineWindowMinutes(configuration), Clean(await incidents.OrderByDescending(x => x.CreatedAt).Take(20).ToListAsync(ct))));
    }
    [HttpGet] public async Task<IActionResult> Licenses(CancellationToken ct) => View(await db.Licenses.AsNoTracking().OrderByDescending(x => x.CreatedAt).Take(500).ToListAsync(ct));
    [HttpGet] public IActionResult CreateLicense() => View(new CreateLicenseForm());
    [HttpPost]
    public async Task<IActionResult> CreateLicense(CreateLicenseForm form, CancellationToken ct)
    {
        if (!ModelState.IsValid) return View(form);
        try
        {
            var created = await admin.CreateLicenseAsync(form.Label, form.ValidFrom, form.ValidUntil, form.MaxInstallations, ct);
            return View("LicenseCreated", new LicenseCreatedViewModel(created.License, created.FullKey));
        }
        catch (DomainException e) { AddError(e); return View(form); }
    }
    [HttpGet]
    public async Task<IActionResult> LicenseDetails(Guid id, CancellationToken ct)
    {
        var model = await LicenseModel(id, ct);
        return model is null ? NotFound() : View(model);
    }
    private async Task<LicenseDetailsViewModel?> LicenseModel(Guid id, CancellationToken ct)
    {
        var license = await db.Licenses.AsNoTracking().SingleOrDefaultAsync(x => x.Id == id, ct);
        return license is null ? null : new(license, await db.Installations.AsNoTracking().Include(x => x.License).Where(x => x.LicenseId == id).OrderByDescending(x => x.CreatedAt).ToListAsync(ct),
            new LicenseLimitsForm { Id = id, ValidUntil = license.ValidUntil, MaxInstallations = license.MaxInstallations });
    }
    [HttpPost]
    public async Task<IActionResult> SetLicenseStatus(LicenseStatusForm form, CancellationToken ct)
    {
        if (form.Id == Guid.Empty || !ModelState.IsValid) return BadRequest();
        try { await admin.SetLicenseStatusAsync(form.Id, form.Status, ct); }
        catch (DomainException e) { return await LicenseError(form.Id, e, ct); }
        return RedirectToAction(nameof(LicenseDetails), new { id = form.Id });
    }
    [HttpPost]
    public async Task<IActionResult> UpdateLicenseLimits(LicenseLimitsForm form, CancellationToken ct)
    {
        var model = await LicenseModel(form.Id, ct);
        if (model is null) return NotFound();
        if (form.ValidUntil <= model.License.ValidFrom) ModelState.AddModelError(nameof(form.ValidUntil), "نهاية الصلاحية يجب أن تكون بعد بدايتها");
        if (!ModelState.IsValid) return View("LicenseDetails", model with { Limits = form });
        try { await admin.UpdateLicenseLimitsAsync(form.Id, form.ValidUntil, form.MaxInstallations, ct); }
        catch (DomainException e) { AddError(e); return View("LicenseDetails", model with { Limits = form }); }
        return RedirectToAction(nameof(LicenseDetails), new { id = form.Id });
    }
    [HttpPost]
    public async Task<IActionResult> SetOfflineGrace(OfflineGraceForm form, CancellationToken ct)
    {
        if (form.Id == Guid.Empty) return BadRequest();
        if (!ModelState.IsValid)
        {
            var model = await LicenseModel(form.Id, ct);
            return model is null ? NotFound() : View("LicenseDetails", model);
        }
        try { await admin.SetOfflineGraceAsync(form.Id, form.OfflineGraceDays, ct); }
        catch (DomainException e) { return await LicenseError(form.Id, e, ct); }
        return RedirectToAction(nameof(LicenseDetails), new { id = form.Id });
    }
    private async Task<IActionResult> LicenseError(Guid id, DomainException error, CancellationToken ct)
    {
        if (error.StatusCode == 404) return NotFound();
        AddError(error);
        var model = await LicenseModel(id, ct);
        return model is null ? NotFound() : View("LicenseDetails", model);
    }
    [HttpGet] public async Task<IActionResult> Devices(CancellationToken ct) => View(new DevicesViewModel(await db.Installations.AsNoTracking().Include(x => x.License).OrderByDescending(x => x.LastSeenAt).Take(500).ToListAsync(ct), OnlineCutoff));
    [HttpGet]
    public async Task<IActionResult> DeviceDetails(Guid id, CancellationToken ct)
    {
        var device = await db.Installations.AsNoTracking().Include(x => x.License).SingleOrDefaultAsync(x => x.Id == id, ct);
        if (device is null) return NotFound();
        var sessions = await db.DeviceSessions.AsNoTracking().Where(x => x.InstallationId == id).OrderByDescending(x => x.StartedAt).Take(100).ToListAsync(ct);
        foreach (var session in sessions)
        {
            session.ExitReason = Sanitizer.CleanDiagnostic(session.ExitReason, 256);
            session.AppVersion = Sanitizer.CleanDiagnostic(session.AppVersion, 64);
        }
        return View("DeviceDetails", new DeviceDetailsViewModel(device,
            Clean(await db.DiagnosticEvents.AsNoTracking().Where(x => x.InstallationId == id).OrderByDescending(x => x.OccurredAt).Take(100).ToListAsync(ct)), OnlineCutoff, sessions)
        {
            LocalSubscriptions = device.LocalSubscriptionsJson is null ? [] :
                System.Text.Json.JsonSerializer.Deserialize<List<MegaStream.Server.Contracts.LocalSubscription>>(device.LocalSubscriptionsJson) ?? [],
            AvailableLicenses = await db.Licenses.AsNoTracking().Where(x => x.Status == LicenseStatus.Active &&
                x.ValidFrom <= DateTime.UtcNow && x.ValidUntil > DateTime.UtcNow).OrderBy(x => x.Label).ToListAsync(ct),
            ManagedDevice = await db.Set<MegaStream.Server.V1.V1InstallationMetadata>().AsNoTracking().AnyAsync(x => x.InstallationId == id && x.ManagedDevice, ct),
            PolicyAudit = await db.Set<DevicePolicyAudit>().AsNoTracking().Where(x => x.InstallationId == id).OrderByDescending(x => x.CreatedAt).Take(100).ToListAsync(ct)
        });
    }
    [HttpPost]
    public async Task<IActionResult> SetSubscriptionDetailsPolicy(SubscriptionDetailsPolicyForm form, CancellationToken ct)
    {
        if (form.Id == Guid.Empty || !ModelState.IsValid) return BadRequest();
        var changed = await db.Installations.Where(x => x.Id == form.Id && x.Status == InstallationStatus.Active)
            .ExecuteUpdateAsync(set => set.SetProperty(x => x.AllowSubscriptionDetails, form.AllowSubscriptionDetails!.Value), ct);
        if (changed != 1) return BadRequest();
        return RedirectToAction(nameof(DeviceDetails), new { id = form.Id });
    }
    [HttpGet]
    public async Task<IActionResult> Notifications(Guid? targetInstallationId, CancellationToken ct)
    {
        if (!ModelState.IsValid) return BadRequest();
        return View(await NotificationsModel(new AdminNotificationForm { TargetInstallationId = targetInstallationId }, ct));
    }
    private async Task<NotificationsViewModel> NotificationsModel(AdminNotificationForm form, CancellationToken ct) => new(form,
        await db.Installations.AsNoTracking().Where(x => x.Status == InstallationStatus.Active).OrderBy(x => x.DeviceModel).ToListAsync(ct),
        await db.AdminNotifications.AsNoTracking().OrderByDescending(x => x.CreatedAt).Take(100).ToListAsync(ct));
    [HttpPost]
    public async Task<IActionResult> SendNotification(AdminNotificationForm form, CancellationToken ct)
    {
        var title = Sanitizer.CleanDiagnostic(form.Title, 128);
        var message = Sanitizer.CleanDiagnostic(form.Message, 2000);
        if (string.IsNullOrWhiteSpace(title)) ModelState.AddModelError(nameof(form.Title), "أدخل عنواناً صالحاً");
        if (string.IsNullOrWhiteSpace(message)) ModelState.AddModelError(nameof(form.Message), "أدخل رسالة صالحة");
        if (form.TargetInstallationId is { } target &&
            !await db.Installations.AnyAsync(x => x.Id == target && x.Status == InstallationStatus.Active, ct))
            ModelState.AddModelError(nameof(form.TargetInstallationId), "اختر جهازاً نشطاً");
        if (!ModelState.IsValid)
        {
            Response.StatusCode = 400;
            return View("Notifications", await NotificationsModel(form, ct));
        }
        db.AdminNotifications.Add(new AdminNotification { TargetInstallationId = form.TargetInstallationId,
            Title = title, Message = message,
            ExpiresAt = form.ExpiresAt.HasValue ? DateTime.SpecifyKind(form.ExpiresAt.Value, DateTimeKind.Utc) : DateTime.UtcNow.AddDays(30) });
        await db.SaveChangesAsync(ct);
        return RedirectToAction(nameof(Notifications));
    }
    [HttpPost]
    public async Task<IActionResult> AssignDeviceLicense(AssignDeviceLicenseForm form, CancellationToken ct)
    {
        if (form.Id == Guid.Empty) return BadRequest();
        if (form.LicenseId == Guid.Empty) ModelState.AddModelError("", "اختر ترخيصاً صالحاً");
        if (!ModelState.IsValid) return await DeviceDetails(form.Id, ct);
        try { await admin.AssignInstallationLicenseAsync(form.Id, form.LicenseId, ct); }
        catch (DomainException e)
        {
            if (e.StatusCode == 404) return NotFound();
            AddError(e);
            return await DeviceDetails(form.Id, ct);
        }
        return RedirectToAction(nameof(DeviceDetails), new { id = form.Id });
    }
    [HttpPost]
    public async Task<IActionResult> SetDevicePolicy(DevicePolicyForm form, CancellationToken ct)
    {
        if (form.Id == Guid.Empty) return BadRequest();
        if (!ModelState.IsValid) return await DeviceDetails(form.Id, ct);
        var actorId = User.FindFirstValue(ClaimTypes.NameIdentifier);
        if (string.IsNullOrWhiteSpace(actorId)) return Forbid();
        try { await admin.SetDevicePolicyAsync(form.Id, form.KioskMode!.Value.ToString(), form.AllowLocalExit, actorId, ct); }
        catch (DomainException e)
        {
            if (e.StatusCode == 404) return NotFound();
            AddError(e);
            return await DeviceDetails(form.Id, ct);
        }
        return RedirectToAction(nameof(DeviceDetails), new { id = form.Id });
    }
    [HttpPost]
    public async Task<IActionResult> RevokeDevice(RevokeDeviceForm form, CancellationToken ct)
    {
        if (form.Id == Guid.Empty || !ModelState.IsValid) return BadRequest();
        try { await admin.RevokeInstallationAsync(form.Id, ct); }
        catch (DomainException e) { if (e.StatusCode == 404) return NotFound(); AddError(e); return await DeviceDetails(form.Id, ct); }
        return RedirectToAction(nameof(DeviceDetails), new { id = form.Id });
    }
    [HttpGet] public async Task<IActionResult> Codes(CancellationToken ct) => View(await CodeModel(ct));
    private async Task<CodesViewModel> CodeModel(CancellationToken ct)
    {
        var now = DateTime.UtcNow;
        return new(await db.ActivationCodes.AsNoTracking().Include(x => x.Installation).OrderByDescending(x => x.CreatedAt).Take(300).ToListAsync(ct),
            await db.Licenses.AsNoTracking().Where(x => x.Status == LicenseStatus.Active && x.ValidFrom <= now && x.ValidUntil > now).OrderBy(x => x.Label).ToListAsync(ct));
    }
    [HttpPost]
    public async Task<IActionResult> ApproveCode(ApproveCodeForm form, CancellationToken ct)
    {
        if (form.Id == Guid.Empty || form.LicenseId == Guid.Empty) ModelState.AddModelError("", "اختر رمزاً وترخيصاً صالحين");
        if (!ModelState.IsValid) return View("Codes", await CodeModel(ct));
        try { await admin.ApproveActivationAsync(form.Id, form.LicenseId, ct); }
        catch (DomainException e) { if (e.StatusCode == 404) return NotFound(); AddError(e); return View("Codes", await CodeModel(ct)); }
        return RedirectToAction(nameof(Codes));
    }
    [HttpGet]
    public async Task<IActionResult> Diagnostics([FromQuery] DiagnosticsViewModel filter, CancellationToken ct)
    {
        if (!ModelState.IsValid) return View(filter);
        var query = db.DiagnosticEvents.AsNoTracking().AsQueryable();
        if (!string.IsNullOrWhiteSpace(filter.Level)) query = query.Where(x => x.Level == filter.Level);
        if (!string.IsNullOrWhiteSpace(filter.Category)) query = query.Where(x => x.Category == filter.Category);
        if (filter.InstallationId.HasValue) query = query.Where(x => x.InstallationId == filter.InstallationId.Value);
        filter.Events = Clean(await query.OrderByDescending(x => x.OccurredAt).Take(300).ToListAsync(ct));
        return View(filter);
    }
    [HttpGet]
    public async Task<IActionResult> DiagnosticDetails(long id, CancellationToken ct)
    {
        var entry = await db.DiagnosticEvents.AsNoTracking().SingleOrDefaultAsync(x => x.Id == id, ct);
        if (entry is null) return NotFound();
        var payload = await db.Set<MegaStream.Server.V1.V1DiagnosticReceipt>().AsNoTracking()
            .Where(x => x.InstallationId == entry.InstallationId && x.EventId == entry.EventId)
            .Select(x => x.PayloadJson).SingleOrDefaultAsync(ct);
        return View(new DiagnosticDetailsViewModel(Clean([entry])[0], Sanitizer.CleanDiagnostic(payload, 16384)));
    }
    private static IReadOnlyList<DiagnosticEvent> Clean(List<DiagnosticEvent> entries)
    {
        foreach (var e in entries)
        {
            e.Level = Sanitizer.CleanDiagnostic(e.Level, 16); e.Category = Sanitizer.CleanDiagnostic(e.Category, 64);
            e.Message = Sanitizer.CleanDiagnostic(e.Message, 2048); e.StackTrace = Sanitizer.CleanDiagnostic(e.StackTrace, 4096);
            e.SessionId = Sanitizer.CleanDiagnostic(e.SessionId, 64); e.ChannelName = Sanitizer.CleanDiagnostic(e.ChannelName, 128);
            e.SourceType = Sanitizer.CleanDiagnostic(e.SourceType, 32); e.Container = Sanitizer.CleanDiagnostic(e.Container, 32);
            e.VideoCodec = Sanitizer.CleanDiagnostic(e.VideoCodec, 32); e.AudioCodec = Sanitizer.CleanDiagnostic(e.AudioCodec, 32);
            e.AppVersion = Sanitizer.CleanDiagnostic(e.AppVersion, 64); e.DeviceVersion = Sanitizer.CleanDiagnostic(e.DeviceVersion, 64);
        }
        return entries;
    }
    private void AddError(DomainException error) => ModelState.AddModelError("", error.Code switch
    {
        "capacity_exceeded" => "تم بلوغ الحد الأقصى للأجهزة",
        "capacity_in_use" => "السعة مستخدمة؛ انتظر انتهاء حجوزات الأجهزة الملغاة قبل خفض الحد",
        "activation_unavailable" => "رمز التفعيل منتهي أو غير متاح",
        "license_unavailable" => "الترخيص غير نشط أو خارج فترة الصلاحية",
        "already_licensed" => "الجهاز مرتبط بترخيص بالفعل",
        "installation_disabled" => "الجهاز ملغى ولا يمكن ترخيصه",
        "invalid_metadata" => "لا يسمح بروابط المزود أو بيانات الدخول داخل الاسم",
        _ => "تعذر تنفيذ العملية. تحقق من القيم والحالة ثم أعد المحاولة"
    });
}
