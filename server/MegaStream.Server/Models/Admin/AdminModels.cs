using System.ComponentModel.DataAnnotations;
using MegaStream.Server.Models;

namespace MegaStream.Server.Models.Admin;

public sealed class LoginForm
{
    [Required(ErrorMessage = "أدخل اسم المستخدم"), StringLength(256)] public string UserName { get; set; } = "";
    [Required(ErrorMessage = "أدخل كلمة المرور"), StringLength(1024), DataType(DataType.Password)] public string Password { get; set; } = "";
    public string? ReturnUrl { get; set; }
}
public sealed class CreateLicenseForm : IValidatableObject
{
    [Required(ErrorMessage = "أدخل اسم الترخيص"), StringLength(128)] public string Label { get; set; } = "";
    public DateTime ValidFrom { get; set; } = DateTime.UtcNow.Date;
    public DateTime ValidUntil { get; set; } = DateTime.UtcNow.Date.AddYears(1);
    [Range(1, 10000, ErrorMessage = "عدد الأجهزة بين 1 و10000")] public int MaxInstallations { get; set; } = 1;
    public IEnumerable<ValidationResult> Validate(ValidationContext context)
    {
        if (ValidFrom.Year < 2000 || ValidFrom.Year > 2100 || ValidUntil.Year < 2000 || ValidUntil.Year > 2100 || ValidUntil <= ValidFrom)
            yield return new ValidationResult("اختر فترة صالحة بين عامي 2000 و2100، والنهاية بعد البداية", [nameof(ValidUntil)]);
    }
}
public sealed class LicenseLimitsForm : IValidatableObject
{
    public Guid Id { get; set; }
    public DateTime ValidUntil { get; set; }
    [Range(1, 10000, ErrorMessage = "عدد الأجهزة بين 1 و10000")] public int MaxInstallations { get; set; }
    public IEnumerable<ValidationResult> Validate(ValidationContext context)
    {
        if (Id == Guid.Empty) yield return new ValidationResult("ترخيص غير صالح", [nameof(Id)]);
        if (ValidUntil.Year < 2000 || ValidUntil.Year > 2100) yield return new ValidationResult("التاريخ خارج النطاق المسموح", [nameof(ValidUntil)]);
    }
}
public sealed class LicenseStatusForm
{
    public Guid Id { get; set; }
    [EnumDataType(typeof(LicenseStatus))] public LicenseStatus Status { get; set; }
}
public sealed class ApproveCodeForm { public Guid Id { get; set; } public Guid LicenseId { get; set; } }
public sealed class AssignDeviceLicenseForm { public Guid Id { get; set; } public Guid LicenseId { get; set; } }
public sealed class RevokeDeviceForm { public Guid Id { get; set; } }
public sealed class SubscriptionDetailsPolicyForm
{
    public Guid Id { get; set; }
    [Required] public bool? AllowSubscriptionDetails { get; set; }
}
public sealed class UiStyleForm
{
    public Guid Id { get; set; }
    [RegularExpression("^(classic|modern|studio)$", ErrorMessage = "اختر تصميماً صالحاً")]
    public string? UiStyle { get; set; }
}
public sealed class DevicePlaybackQualityForm
{
    public Guid Id { get; set; }
    [RegularExpression("^(auto|480|720|1080|2160)$")]
    public string? PlaybackQuality { get; set; }
}
public sealed class GlobalPlaybackQualityForm
{
    [Required, RegularExpression("^(auto|480|720|1080|2160)$")]
    public string PlaybackQuality { get; set; } = "";
}
public sealed class BulkPlaybackQualityForm : IValidatableObject
{
    [Required, RegularExpression("^all$")] public string Scope { get; set; } = "";
    [Required, RegularExpression("^(apply|inherit)$")] public string Mode { get; set; } = "";
    [RegularExpression("^(auto|480|720|1080|2160)$")] public string? PlaybackQuality { get; set; }
    public bool Confirm { get; set; }
    public IEnumerable<ValidationResult> Validate(ValidationContext context)
    {
        if (!Confirm) yield return new ValidationResult("أكد تطبيق السياسة على جميع الأجهزة النشطة", [nameof(Confirm)]);
        if (Mode == "apply" && PlaybackQuality is null)
            yield return new ValidationResult("اختر جودة التشغيل", [nameof(PlaybackQuality)]);
        if (Mode == "inherit" && PlaybackQuality is not null)
            yield return new ValidationResult("الوراثة لا تقبل تجاوزاً للجودة", [nameof(PlaybackQuality)]);
    }
}
public sealed class AdminNotificationForm : IValidatableObject
{
    public Guid? TargetInstallationId { get; set; }
    [Required, StringLength(128)] public string Title { get; set; } = "";
    [Required, StringLength(2000)] public string Message { get; set; } = "";
    public DateTime? ExpiresAt { get; set; }
    public IEnumerable<ValidationResult> Validate(ValidationContext context)
    {
        if (ExpiresAt is { } expires && (expires <= DateTime.UtcNow || expires.Year > 2100))
            yield return new ValidationResult("اختر تاريخ انتهاء مستقبلي حتى عام 2100", [nameof(ExpiresAt)]);
    }
}
public sealed record NotificationsViewModel(AdminNotificationForm Form, IReadOnlyList<Installation> Devices,
    IReadOnlyList<AdminNotification> Notifications);
public sealed record DashboardViewModel(int LicenseCount, int DeviceCount, int OnlineDeviceCount, int IncidentCount, int OnlineWindowMinutes, IReadOnlyList<DiagnosticEvent> RecentIncidents);
public sealed record LicenseCreatedViewModel(License License, string FullKey);
public sealed record LicenseDetailsViewModel(License License, IReadOnlyList<Installation> Devices, LicenseLimitsForm Limits);
public sealed record DevicesViewModel(IReadOnlyList<Installation> Devices, DateTime OnlineCutoff)
{
    public string GlobalPlaybackQuality { get; init; } = "1080";
}
public sealed record DeviceDetailsViewModel(Installation Device, IReadOnlyList<DiagnosticEvent> Diagnostics, DateTime OnlineCutoff, IReadOnlyList<DeviceSession> Sessions)
{
    public string GlobalPlaybackQuality { get; init; } = "1080";
    public MegaStream.Server.V1.V1InstallationMetadata? VersionReport { get; init; }
    public IReadOnlyList<MegaStream.Server.Updates.UpdateReleaseSummary> AvailableUpdates { get; init; } = [];
    public IReadOnlyList<MegaStream.Server.Updates.DeviceUpdateCommand> UpdateCommands { get; init; } = [];
    public IReadOnlyList<MegaStream.Server.Contracts.LocalSubscription> LocalSubscriptions { get; init; } = [];
    public IReadOnlySet<long> LocalCredentialIds { get; init; } = new HashSet<long>();
    public bool ManagedDevice { get; init; }
    public IReadOnlyList<License> AvailableLicenses { get; init; } = [];
    public IReadOnlyList<DevicePolicyAudit> PolicyAudit { get; init; } = [];
}
public enum AdminKioskMode { off, playback, always }
public sealed class DevicePolicyForm
{
    public Guid Id { get; set; }
    [Required(ErrorMessage = "اختر وضع الكشك"), EnumDataType(typeof(AdminKioskMode), ErrorMessage = "وضع الكشك غير صالح")]
    public AdminKioskMode? KioskMode { get; set; }
    public bool AllowLocalExit { get; set; } = true;
}
public sealed record DiagnosticDetailsViewModel(DiagnosticEvent Event, string? PayloadJson);
public sealed class OfflineGraceForm
{
    public Guid Id { get; set; }
    [Range(1, 3, ErrorMessage = "المهلة بين يوم واحد وثلاثة أيام")] public int OfflineGraceDays { get; set; } = 3;
}
public sealed record CodesViewModel(IReadOnlyList<ActivationCode> Codes, IReadOnlyList<License> Licenses);
public sealed class DiagnosticsViewModel
{
    public IReadOnlyList<DiagnosticEvent> Events { get; set; } = [];
    [StringLength(16)] public string? Level { get; set; }
    [StringLength(64)] public string? Category { get; set; }
    public Guid? InstallationId { get; set; }
}
public static class AdminDisplay
{
    public static string UiStyleName(string? style) => style switch
    { "classic" => "الحالي", "modern" => "الحديث", "studio" => "Studio", _ => "اختيار الجهاز المحلي" };
    public static string DeviceConnectionClass(Installation device, DateTime cutoff) => device.Status == InstallationStatus.Revoked ? "danger" : device.LastSeenAt >= cutoff ? "online" : "offline";
    public static string DeviceLicenseState(Installation device)
    {
        if (device.Status == InstallationStatus.Revoked) return "الجهاز ملغى";
        if (device.License is not { } license) return "غير مرخص";
        if (license.Status != LicenseStatus.Active) return LicenseState(license.Status);
        var now = DateTime.UtcNow;
        return license.ValidUntil <= now ? "منتهي" : license.ValidFrom > now ? "لم تبدأ الصلاحية" : "مرخص";
    }
    public static string KioskState(string mode) => mode switch { "off" => "متوقف", "playback" => "أثناء التشغيل", "always" => "دائماً", _ => "غير معروف" };
    public static string LicenseState(LicenseStatus status) => status switch { LicenseStatus.Active => "نشط", LicenseStatus.Suspended => "معلق", _ => "ملغى" };
    public static string CodeState(ActivationCodeStatus status) => status switch { ActivationCodeStatus.Pending => "بانتظار الموافقة", ActivationCodeStatus.Approved => "معتمد", ActivationCodeStatus.Consumed => "مستخدم", ActivationCodeStatus.Cancelled => "ملغى", _ => "منتهي" };
    public static string DeviceState(Installation device, DateTime cutoff) => device.Status == InstallationStatus.Revoked
        ? device.LastLeaseExpiresAt > DateTime.UtcNow ? "ملغى — جارٍ تحرير السعة" : "ملغى"
        : device.LastSeenAt >= cutoff ? "متصل" : "غير متصل";
}
